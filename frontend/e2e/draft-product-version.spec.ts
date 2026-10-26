import { expect, test, type Page } from '@playwright/test';

async function draft(page: Page) {
  await page.goto('/demo-access');
  await page.getByLabel('Демотокен', { exact: true }).fill('demo-customer');
  await page.getByRole('button', { name: 'Войти →', exact: true }).click();
  await expect(page).toHaveURL(/applications$/);
  const id = await page.evaluate(() => {
    const bank = window.__MOCK__!;
    const id = bank.seed('approved', 'DRAFT');
    bank.state.apps[id].additionalInfo = { businessDescription: 'Сведения для сохранения' };
    bank.checkpoint();
    return id;
  });
  await page.goto('/applications/' + id);
  await expect(page.getByRole('heading', { name: 'Параметры заявки' })).toBeVisible();
  return id;
}
async function publish(page: Page, version: string) {
  await page.evaluate((version) => {
    const bank = window.__MOCK__!;
    const product = bank.currentProducts().find((p) => p.id === 'working-capital')!;
    bank.publishCatalog({
      ...product,
      version,
      maxAmountRub: 800000,
      minTermMonths: 18,
      annualRateBps: 1800,
      purposeCodes: ['EQUIPMENT'],
    });
    bank.checkpoint();
  }, version);
}
async function consents(page: Page) {
  await page.getByLabel('Согласие на обработку персональных данных', { exact: true }).check();
  await page.getByLabel('Согласие на запрос кредитной истории', { exact: true }).check();
}

test('outdated draft requires correction and saving current conditions before submission', async ({
  page,
}) => {
  const id = await draft(page);
  await publish(page, '2');
  await page.reload();
  await expect(page.getByText('Условия программы изменились: версия 1 → 2.')).toBeVisible();
  await expect(page.getByText(/Ставка: 18% годовых/)).toBeVisible();
  await consents(page);
  await expect(
    page.getByRole('button', { name: 'Проверить и отправить →', exact: true }),
  ).toBeDisabled();
  const stale = await page.evaluate(async (id) => {
    const path = '/api/v2/applications/' + id;
    const headers = { Authorization: 'Bearer demo-customer' };
    const current = await fetch(path, { headers });
    const etag = current.headers.get('ETag')!;
    const submitted = await fetch(path + '/submit', {
      method: 'POST',
      headers: {
        ...headers,
        'Content-Type': 'application/json',
        'Idempotency-Key': crypto.randomUUID(),
        'If-Match': etag,
      },
      body: JSON.stringify({ personalDataConsent: true, creditBureauConsent: true }),
    });
    const invalid = await fetch(path, {
      method: 'PATCH',
      headers: {
        ...headers,
        'Content-Type': 'application/json',
        'Idempotency-Key': crypto.randomUUID(),
        'If-Match': etag,
      },
      body: JSON.stringify({ amountRub: 1000000 }),
    });
    const after = await fetch(path, { headers });
    return {
      status: submitted.status,
      code: (await submitted.json()).code,
      invalidStatus: invalid.status,
      unchanged: after.headers.get('ETag') === etag,
      app: await after.json(),
    };
  }, id);
  expect(stale.status).toBe(409);
  expect(stale.code).toBe('PRODUCT_NOT_CURRENT');
  expect(stale.invalidStatus).toBe(422);
  expect(stale.unchanged).toBe(true);
  expect(stale.app.status).toBe('DRAFT');
  expect(stale.app.product.version).toBe('1');
  await page.getByRole('button', { name: 'Сохранить черновик', exact: true }).click();
  await expect(page.getByLabel('Сумма, ₽', { exact: true })).toHaveAttribute(
    'aria-invalid',
    'true',
  );
  await expect(page.getByLabel('Срок, мес.', { exact: true })).toHaveAttribute(
    'aria-invalid',
    'true',
  );
  await page.screenshot({ path: 'test-results/draft-product-changed-1440.png', fullPage: true });
  await page.getByLabel('Сумма, ₽', { exact: true }).fill('700000');
  await page.getByLabel('Срок, мес.', { exact: true }).fill('24');
  await page.getByLabel('Цель кредита', { exact: true }).selectOption('EQUIPMENT');
  await page.getByRole('button', { name: 'Сохранить черновик', exact: true }).click();
  await expect(page.getByText('✓ Черновик сохранён')).toBeVisible();
  await expect(page.getByText('Условия программы изменились: версия 1 → 2.')).toHaveCount(0);
  await expect(page.getByLabel('Описание бизнеса', { exact: true })).toHaveValue(
    'Сведения для сохранения',
  );
  await page.getByRole('button', { name: 'Проверить и отправить →', exact: true }).click();
  await expect(page.getByRole('dialog')).toBeVisible();
  const submitted = page.waitForRequest(
    (r) => r.method() === 'POST' && r.url().endsWith('/submit'),
  );
  await page.getByRole('button', { name: 'Отправить заявку', exact: true }).click();
  await submitted;
  await expect(page.getByText(/ВЕРСИЯ ДАННЫХ 1/)).toBeVisible();
  const snapshot = await page.evaluate(async (id) => {
    const response = await fetch('/api/v2/applications/' + id + '/versions/1', {
      headers: { Authorization: 'Bearer demo-customer' },
    });
    return response.json();
  }, id);
  expect(snapshot.data.product.version).toBe('2');
  expect(snapshot.data.product.annualRateBps).toBe(1800);
});

test('publication during confirmation is rejected and preflight catches another product update', async ({
  page,
}) => {
  const id = await draft(page);
  await consents(page);
  await page.getByRole('button', { name: 'Проверить и отправить →', exact: true }).click();
  await expect(page.getByRole('dialog')).toBeVisible();
  await publish(page, '2');
  await page.getByRole('button', { name: 'Отправить заявку', exact: true }).click();
  await expect(page.getByRole('dialog')).toHaveCount(0);
  await expect(page.getByText('Условия программы изменились: версия 1 → 2.')).toBeVisible();
  await page
    .getByRole('button', { name: 'Я ознакомился с изменениями — вернуться к форме', exact: true })
    .click();
  await page.getByLabel('Сумма, ₽', { exact: true }).fill('700000');
  await page.getByLabel('Срок, мес.', { exact: true }).fill('24');
  await page.getByLabel('Цель кредита', { exact: true }).selectOption('EQUIPMENT');
  await page.getByRole('button', { name: 'Сохранить черновик', exact: true }).click();
  await expect(page.getByText('✓ Черновик сохранён')).toBeVisible();
  await publish(page, '3');
  await page.getByRole('button', { name: 'Проверить и отправить →', exact: true }).click();
  await expect(page.getByRole('dialog')).toHaveCount(0);
  await expect(page.getByText('Условия программы изменились: версия 2 → 3.')).toBeVisible();
  expect(await page.evaluate((id) => window.__MOCK__!.state.apps[id].status, id)).toBe('DRAFT');
});
