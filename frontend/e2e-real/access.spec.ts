import {
  test,
  expect,
  login,
  open,
  refresh,
  visibleStatus,
  customerToken,
  staffToken,
  company,
} from './fixtures';
import { randomUUID } from 'node:crypto';

for (const [role, token] of [
  ['customer', 'incorrect-demo-token'],
  ['customer', staffToken],
  ['staff', customerToken],
] as const) {
  test(`rejects ${role} login with ${token === staffToken ? 'staff' : token === customerToken ? 'customer' : 'invalid'} token`, async ({
    page,
    bank,
  }) => {
    await page.goto('/demo-access');
    await page
      .getByLabel(role === 'staff' ? 'Сотрудник' : 'Предприниматель', { exact: true })
      .check();
    await page.getByLabel('Демотокен', { exact: true }).fill(token);
    await page.getByRole('button', { name: 'Войти →', exact: true }).click();
    await expect(page.getByRole('alert')).toBeVisible();
    await expect(page).toHaveURL(/\/demo-access$/);
    await expect(page.getByRole('heading', { name: 'Войти в кабинет', exact: true })).toBeVisible();
  });
}

test('unauthenticated nested navigation returns to the exact requested route after login', async ({
  page,
  bank,
}) => {
  const id = await bank.create();
  await page.goto(`/applications/${id}/history`);
  await expect(page).toHaveURL(/\/demo-access$/);
  await page.getByLabel('Демотокен', { exact: true }).fill(customerToken);
  await page.getByRole('button', { name: 'Войти →', exact: true }).click();
  await expect(page).toHaveURL(new RegExp(`/applications/${id}/history$`));
  await expect(page.getByRole('heading', { name: 'История и версии', exact: true })).toBeVisible();
});

test('401 during refresh logs out safely and restores the card after reauthentication', async ({
  page,
  bank,
}) => {
  const id = await bank.offered();
  await login(page);
  await open(page, id);
  await page.route(
    `**/api/v2/applications/${id}`,
    (route) =>
      route.fulfill({
        status: 401,
        contentType: 'application/problem+json',
        body: JSON.stringify({
          type: 'about:blank',
          title: 'UNAUTHENTICATED',
          status: 401,
          detail: 'Демотокен не принят',
          code: 'UNAUTHENTICATED',
          requestId: randomUUID(),
          errors: [],
        }),
      }),
    { times: 1 },
  );
  await refresh(page);
  await expect(page).toHaveURL(/\/demo-access$/);
  await page.getByLabel('Демотокен', { exact: true }).fill(customerToken);
  await page.getByRole('button', { name: 'Войти →', exact: true }).click();
  await expect(page).toHaveURL(new RegExp(`/applications/${id}$`));
  await visibleStatus(page, 'Предложение готово');
});

test('missing application has explicit 404 and exposes no commands', async ({ page, bank }) => {
  await login(page);
  await page.goto('/applications/' + randomUUID());
  await expect(page.getByRole('alert')).toContainText(
    /not found|не.*найден|отсутствует|недоступна/i,
  );
  await expect(
    page.getByRole('button', { name: /Подписать договор|Принять предложение|Отменить заявку/ }),
  ).toHaveCount(0);
});

test('role navigation and public API reject customer staff commands and staff customer mutations', async ({
  page,
  bank,
}) => {
  const id = await bank.manual();
  await login(page);
  await open(page, id);
  await expect(page.getByLabel('Действие сотрудника', { exact: true })).toHaveCount(0);
  const customerStaff = await bank.request.get(`/api/v2/staff/applications/${id}/manual-review`, {
    headers: { Authorization: `Bearer ${customerToken}` },
  });
  expect(customerStaff.status()).toBe(403);
  const staffMutation = await bank.request.post(`/api/v2/applications/${id}/cancel`, {
    headers: {
      Authorization: `Bearer ${staffToken}`,
      'Idempotency-Key': randomUUID(),
      'If-Match': await bank.etag(id, 'staff'),
    },
    data: { reason: 'Действие чужой роли запрещено' },
  });
  expect(staffMutation.status()).toBe(403);
  await page.getByRole('button', { name: 'Выйти из кабинета ↗', exact: true }).click();
  await expect(page).toHaveURL(/\/demo-access$/);
  await login(page, 'staff');
  await open(page, id, 'staff');
  await expect(page.getByLabel('Действие сотрудника', { exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Отменить заявку', exact: true })).toHaveCount(0);
});

test('same-origin public routes only; runtime mocks disabled and internal routes inaccessible', async ({
  page,
  bank,
}) => {
  const seen: string[] = [];
  page.on('request', (request) => {
    if (request.resourceType() === 'fetch' || request.resourceType() === 'xhr')
      seen.push(request.url());
  });
  const id = await bank.awaiting();
  await login(page);
  await open(page, id);
  await page.getByRole('link', { name: 'Документ договора ↗', exact: true }).click();
  await expect(page.getByLabel('Полный текст договора')).toBeVisible();
  await page.reload();
  await expect(page.getByLabel('Полный текст договора')).toBeVisible();
  expect(seen.length).toBeGreaterThan(2);
  const origin = new URL(page.url()).origin;
  expect(seen.every((url) => url.startsWith(origin + '/api/v2/'))).toBe(true);
  expect(seen.some((url) => /verification|disbursements|agreements|:800[1-6]/.test(url))).toBe(
    false,
  );
  expect(await page.evaluate(() => '__MOCK__' in window)).toBe(false);
  expect((await bank.request.get('/api/v2/agreements')).status()).toBe(404);
  expect((await bank.request.get('/api/v2/disbursements')).status()).toBe(404);
  expect((await bank.request.get('/assets/missing-e2e.js')).status()).toBe(404);
});

for (const viewport of [
  { width: 1280, height: 720 },
  { width: 1440, height: 900 },
]) {
  test(`desktop ${viewport.width}: no overflow, keyboard dialog and focus restoration`, async ({
    page,
    bank,
  }) => {
    const id = await bank.offered();
    await page.setViewportSize(viewport);
    await login(page);
    await open(page, id);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(
      true,
    );
    const button = page.getByRole('button', { name: 'Принять предложение', exact: true });
    await button.focus();
    await page.keyboard.press('Enter');
    await expect(page.getByRole('dialog')).toBeVisible();
    await page.keyboard.press('Escape');
    await expect(page.getByRole('dialog')).toHaveCount(0);
    await expect(button).toBeFocused();
    expect((await bank.app(id)).status).toBe('OFFER_READY');
  });
}
