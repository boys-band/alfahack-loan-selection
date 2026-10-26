import { expect, test } from '@playwright/test';

for (const [role, token, destination] of [
  ['Предприниматель', 'demo-customer', '/applications'],
  ['Сотрудник', 'demo-staff', '/staff/manual-reviews'],
  ['Администратор продуктов', 'demo-admin', '/admin/products'],
] as const)
  test(`${role} sees a clear invalid-token error and can correct it`, async ({ page }) => {
    await page.goto('/demo-access');
    await expect(page.getByRole('complementary', { name: 'Тестовые токены' })).toContainText(token);
    await page.getByLabel(role, { exact: true }).check();
    const input = page.getByLabel('Демотокен', { exact: true });
    await input.fill('invalid-demo-token');
    await page.getByRole('button', { name: 'Войти →' }).click();
    await expect(page.getByRole('alert')).toContainText(
      'Демотокен не принят. Проверьте его и попробуйте снова.',
    );
    await expect(input).toHaveAttribute('aria-invalid', 'true');
    expect(await page.evaluate(() => sessionStorage.getItem('alfa.session'))).toBeNull();
    if (role === 'Предприниматель')
      await page.screenshot({ path: 'test-results/demo-access-invalid-token.png', fullPage: true });
    await input.fill(` ${token} `);
    await expect(page.getByRole('alert')).toHaveCount(0);
    await page.getByRole('button', { name: 'Войти →' }).click();
    await expect(page).toHaveURL(new RegExp(destination + '$'));
  });

test('explains a role mismatch and allows switching to the correct role', async ({ page }) => {
  await page.goto('/demo-access');
  await page.getByLabel('Демотокен', { exact: true }).fill('demo-staff');
  await page.getByRole('button', { name: 'Войти →' }).click();
  await expect(page.getByRole('alert')).toContainText('Этот токен не подходит для выбранной роли.');
  await page.getByLabel('Сотрудник', { exact: true }).check();
  await expect(page.getByRole('alert')).toHaveCount(0);
  await expect(page.getByLabel('Демотокен', { exact: true })).toHaveValue('');
  await page.getByLabel('Демотокен', { exact: true }).fill('demo-staff');
  await page.getByRole('button', { name: 'Войти →' }).click();
  await expect(page).toHaveURL(/\/staff\/manual-reviews$/);
});

test('does not blame the token for a service outage and allows retry', async ({ page }) => {
  await page.goto('/demo-access');
  const input = page.getByLabel('Демотокен', { exact: true });
  await expect(input).toBeVisible();
  await page.evaluate(() => {
    window.__MOCK__!.nextFault = { operation: 'listCompanies', status: 503 };
  });
  await input.fill('demo-customer');
  await page.getByRole('button', { name: 'Войти →' }).click();
  await expect(page.getByRole('alert')).toContainText('Сервис входа временно недоступен.');
  await expect(input).not.toHaveAttribute('aria-invalid', 'true');
  await expect(input).toHaveValue('demo-customer');
  await page.getByRole('button', { name: 'Войти →' }).click();
  await expect(page).toHaveURL(/\/applications$/);
});

test('API mode shows the documented local demo tokens with no horizontal overflow', async ({
  page,
}) => {
  await page.setViewportSize({ width: 1280, height: 720 });
  await page.route('**/config.js', (route) =>
    route.fulfill({
      contentType: 'application/javascript',
      body: 'window.__CONFIG__={API_BASE_URL:"/api",MOCK_API:false};',
    }),
  );
  await page.goto('/demo-access');
  const hints = page.getByRole('complementary', { name: 'Тестовые токены' });
  await expect(hints).toContainText('Для стандартного локального стенда');
  for (const token of ['demo-customer-only', 'demo-staff-only', 'demo-product-admin-only'])
    await expect(hints.getByText(token, { exact: true })).toBeVisible();
  await expect(page.getByText('◈ Режим HTTP-стабов', { exact: true })).toHaveCount(0);
  await expect(page.getByLabel('Демотокен', { exact: true })).toHaveValue('');
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await page.screenshot({ path: 'test-results/demo-access-api-mode.png', fullPage: true });
});
