import {
  test as base,
  expect,
  type APIRequestContext,
  type Page,
  type Request,
  type Route,
} from '@playwright/test';
import { randomUUID } from 'node:crypto';
import type {
  Application,
  ApplicationStatus,
  CreateApplicationRequest,
  Agreement,
  DataVersion,
  Timeline,
} from '../src/api/generated';

export const customerToken = process.env.E2E_CUSTOMER_TOKEN || 'demo-customer-only';
export const staffToken = process.env.E2E_STAFF_TOKEN || 'demo-staff-only';
export const company = (id: number) => `00000000-0000-4000-8000-${String(id).padStart(12, '0')}`;
export const financial = {
  monthlyRevenueRub: 1000000,
  monthlyExpensesRub: 500000,
  existingMonthlyDebtPaymentsRub: 20000,
};
export type Role = 'customer' | 'staff';
export const tokenFor = (role: Role) => (role === 'staff' ? staffToken : customerToken);

export class Bank {
  readonly ids = new Set<string>();
  constructor(readonly request: APIRequestContext) {}
  async get<T = Application>(path: string, role: Role = 'customer'): Promise<T> {
    const response = await this.request.get('/api/v2' + path, {
      headers: { Authorization: `Bearer ${tokenFor(role)}` },
    });
    expect(response.ok(), `${path}: ${await response.text()}`).toBeTruthy();
    return response.json();
  }
  async app(id: string) {
    return this.get('/applications/' + id);
  }
  async etag(id: string, role: Role = 'customer') {
    const response = await this.request.get('/api/v2/applications/' + id, {
      headers: { Authorization: `Bearer ${tokenFor(role)}` },
    });
    expect(response.status()).toBe(200);
    expect(response.headers().etag).toBeTruthy();
    return response.headers().etag;
  }
  async mutate(id: string, action: string, data?: unknown, role: Role = 'customer') {
    const path =
      (role === 'staff' ? '/staff' : '') + '/applications/' + id + (action ? '/' + action : '');
    const response = await this.request.fetch('/api/v2' + path, {
      method: action ? 'POST' : 'PATCH',
      headers: {
        Authorization: `Bearer ${tokenFor(role)}`,
        'If-Match': await this.etag(id, role),
        'Idempotency-Key': randomUUID(),
      },
      ...(data === undefined ? {} : { data }),
    });
    expect(response.ok(), `${action}: ${await response.text()}`).toBeTruthy();
    return response;
  }
  async create(overrides: Partial<CreateApplicationRequest> = {}, submit = false) {
    const response = await this.request.post('/api/v2/applications', {
      headers: { Authorization: `Bearer ${customerToken}`, 'Idempotency-Key': randomUUID() },
      data: {
        companyId: company(101),
        productId: 'working-capital',
        productVersion: '1',
        amountRub: 1000000,
        termMonths: 12,
        purpose: 'WORKING_CAPITAL',
        financialData: financial,
        additionalInfo: {},
        ...overrides,
      },
    });
    expect(response.status(), await response.text()).toBe(201);
    const app: Application = await response.json();
    this.ids.add(app.id);
    if (submit)
      await this.mutate(app.id, 'submit', { personalDataConsent: true, creditBureauConsent: true });
    return app.id;
  }
  async wait(id: string, status: ApplicationStatus) {
    let last: Application | undefined;
    await expect
      .poll(
        async () => {
          last = await this.app(id);
          if (last.technicalError) throw new Error(JSON.stringify(last.technicalError));
          return last.status;
        },
        { message: `${id} must reach ${status}`, timeout: 25000, intervals: [100, 200, 500] },
      )
      .toBe(status);
    return last!;
  }
  async offered(overrides: Partial<CreateApplicationRequest> = {}) {
    const id = await this.create(overrides, true);
    await this.wait(id, 'OFFER_READY');
    return id;
  }
  async manual(disputed = false) {
    const id = await this.create(
      disputed
        ? { companyId: company(104) }
        : {
            companyId: company(105),
            termMonths: 36,
            financialData: { ...financial, existingMonthlyDebtPaymentsRub: 160000 },
          },
      true,
    );
    await this.wait(id, 'MANUAL_REVIEW');
    return id;
  }
  async awaiting(overrides: Partial<CreateApplicationRequest> = {}) {
    const id = await this.offered(overrides);
    const app = await this.app(id),
      offer = app.decision!.offer!;
    await this.mutate(id, 'accept-offer', {
      offerId: offer.id,
      offerVersion: offer.version,
      dataVersion: app.dataVersion,
    });
    await this.wait(id, 'AWAITING_SIGNATURE');
    return id;
  }
  agreement(id: string) {
    return this.get<Agreement>('/applications/' + id + '/agreement');
  }
  version(id: string, version: number) {
    return this.get<DataVersion>(`/applications/${id}/versions/${version}`);
  }
  timeline(id: string) {
    return this.get<Timeline>(`/applications/${id}/timeline`);
  }
  async cleanup() {
    // Only cancel this test's own still-open synthetic applications, through public commands.
    // Never reset the database or delete immutable history.
    for (const id of this.ids) {
      const app = await this.app(id);
      if (app.availableActions.includes('CANCEL')) {
        await this.mutate(id, 'cancel', { reason: 'Завершение изолированного E2E-сценария' });
      }
    }
  }
}

export const test = base.extend<{ bank: Bank }>({
  bank: async ({ request }, use, info) => {
    const config = await request.get('/config.js');
    expect(await config.text(), 'Real E2E requires MOCK_API=false').toContain('"MOCK_API":false');
    const bank = new Bank(request);
    await use(bank);
    const snapshots = await Promise.all([...bank.ids].map((id) => bank.app(id)));
    await info.attach('applications', {
      body: JSON.stringify(snapshots, null, 2),
      contentType: 'application/json',
    });
    await bank.cleanup();
  },
  page: async ({ page }, use, info) => {
    const errors: string[] = [];
    page.on('pageerror', (error) => errors.push(error.message));
    page.on('dialog', (dialog) => dialog.accept());
    await use(page);
    await info.attach('browser-errors', {
      body: JSON.stringify(errors),
      contentType: 'application/json',
    });
    expect(errors, 'Unhandled browser JavaScript errors').toEqual([]);
  },
});
export { expect };

export async function login(page: Page, role: Role = 'customer') {
  await page.goto('/demo-access');
  await page
    .getByLabel(role === 'staff' ? 'Сотрудник' : 'Предприниматель', { exact: true })
    .check();
  await page.getByLabel('Демотокен', { exact: true }).fill(tokenFor(role));
  await page.getByRole('button', { name: 'Войти →', exact: true }).click();
  await expect(page).toHaveURL(role === 'staff' ? /\/staff\/manual-reviews$/ : /\/applications$/);
  await expect(page.getByText('◈ Режим HTTP-стабов', { exact: true })).toHaveCount(0);
}
export async function open(page: Page, id: string, role: Role = 'customer') {
  await page.goto((role === 'staff' ? '/staff' : '') + '/applications/' + id);
  await expect(page.getByRole('heading', { level: 1 })).toContainText('Демо');
}
export async function refresh(page: Page) {
  await page.getByRole('button', { name: '↻ Обновить', exact: true }).click();
}
export async function visibleStatus(page: Page, text: string) {
  await expect(page.getByRole('heading', { name: text, exact: true, level: 2 })).toBeVisible();
}
export async function submit(page: Page) {
  await page
    .getByRole('checkbox', { name: 'Согласие на обработку персональных данных', exact: true })
    .check();
  await page
    .getByRole('checkbox', { name: 'Согласие на запрос кредитной истории', exact: true })
    .check();
  await page.getByRole('button', { name: 'Проверить и отправить →', exact: true }).click();
  await page.getByRole('button', { name: 'Отправить заявку', exact: true }).click();
}
export async function accept(page: Page) {
  await page.getByRole('button', { name: 'Принять предложение', exact: true }).click();
  await page.getByRole('button', { name: 'Подтвердить принятие', exact: true }).click();
  await expect(page.getByLabel('Полный текст договора', { exact: true })).toBeVisible();
}
export async function sign(page: Page) {
  await page.getByRole('checkbox', { name: /Я прочитал полный документ/ }).check();
  await page.getByRole('button', { name: 'Подписать договор', exact: true }).click();
  await expect(page.getByText('✓ ВЫДАЧА ПОДТВЕРЖДЕНА', { exact: true })).toBeVisible();
}
export type Wire = { method: string; url: string; body: string | null; key: string; etag?: string };
export function wire(request: Request): Wire {
  const h = request.headers();
  return {
    method: request.method(),
    url: request.url(),
    body: request.postData(),
    key: h['idempotency-key'],
    etag: h['if-match'],
  };
}
export async function loseReply(page: Page, path: string, method = 'POST') {
  const attempts: Wire[] = [];
  let committed: unknown;
  await page.route('**/api/v2' + path, async (route: Route) => {
    if (route.request().method() !== method) return route.continue();
    attempts.push(wire(route.request()));
    const response = await route.fetch(); // Real server commits before the browser loses its response.
    expect(response.ok(), await response.text()).toBeTruthy();
    committed = await response.json();
    if (attempts.length === 1) await route.abort('connectionreset');
    else await route.fulfill({ response });
  });
  return { attempts, committed: () => committed };
}
