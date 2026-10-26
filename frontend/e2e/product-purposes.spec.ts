import { expect, test, type Page } from '@playwright/test';

async function login(page: Page, staff = false) {
  await page.goto('/demo-access');
  if (staff) await page.getByLabel('Сотрудник', { exact: true }).check();
  await page.getByLabel('Демотокен', { exact: true }).fill(staff ? 'demo-staff' : 'demo-customer');
  await page.getByRole('button', { name: 'Войти →', exact: true }).click();
  await expect(page).toHaveURL(staff ? /staff\/manual-reviews$/ : /applications$/);
}

test('new draft purpose follows the loaded product and survives saving and reload', async ({
  page,
}) => {
  await login(page);
  await page.getByRole('link', { name: '＋ Новая заявка', exact: true }).click();
  const purpose = page.getByLabel('Цель кредита', { exact: true });
  await expect(purpose).toBeDisabled();
  await expect(purpose.locator('option')).toHaveText(['Сначала выберите продукт']);
  const product = page.getByLabel('Продукт', { exact: true });
  await product.selectOption('working-capital');
  await expect(purpose).toBeEnabled();
  await purpose.selectOption('REFINANCING');
  await page.getByLabel('Сумма, ₽', { exact: true }).fill('1000000');
  await page.evaluate(() => {
    window.__MOCK__!.nextFault = { operation: 'getProduct', delay: 1000 };
  });
  await product.selectOption('equipment-finance');
  await expect(purpose).toBeDisabled();
  await expect(page.getByRole('button', { name: 'Создать черновик', exact: true })).toBeDisabled();
  await expect(purpose).toBeEnabled();
  await expect(purpose).toHaveValue('');
  await expect(purpose.locator('option')).toHaveText(['Выберите цель', 'Оборудование']);
  await expect(page.getByLabel('Сумма, ₽', { exact: true })).toHaveValue('1000000');
  await purpose.selectOption('EQUIPMENT');
  await page.getByLabel('Ваша компания', { exact: true }).selectOption({ index: 1 });
  await page.setViewportSize({ width: 1280, height: 720 });
  await page.screenshot({ path: 'test-results/product-purpose-create-1280.png', fullPage: true });
  const sent = page.waitForRequest(
    (r) => r.method() === 'POST' && r.url().endsWith('/v2/applications'),
  );
  await page.getByRole('button', { name: 'Создать черновик', exact: true }).click();
  expect((await sent).postDataJSON()).toMatchObject({
    productId: 'equipment-finance',
    productVersion: '1',
    purpose: 'EQUIPMENT',
  });
  await expect(page).toHaveURL(/\/applications\/[a-f0-9-]+$/);
  await expect(purpose).toHaveValue('EQUIPMENT');
  await page.reload();
  await expect(purpose).toHaveValue('EQUIPMENT');
  await expect(purpose.locator('option')).toHaveText(['Выберите цель', 'Оборудование']);
});

test('legacy unavailable purpose must be corrected and other forms use the application product snapshot', async ({
  page,
}) => {
  await login(page);
  const ids = await page.evaluate(() => {
    const bank = window.__MOCK__!;
    const draft = bank.seed('approved', 'DRAFT');
    const info = bank.seed('validation-info', 'NEEDS_INFO');
    const manual = bank.seed('manual', 'MANUAL_REVIEW');
    const updatedProduct = {
      ...bank.state.apps[draft].product,
      version: '2',
      purposeCodes: ['EQUIPMENT'] as const,
    };
    bank.publishCatalog({ ...updatedProduct, purposeCodes: [...updatedProduct.purposeCodes] });
    for (const id of [draft, info, manual]) {
      bank.state.apps[id].product.version = '2';
      bank.state.apps[id].product.purposeCodes = ['EQUIPMENT'];
      bank.state.apps[id].purpose = id === draft ? 'WORKING_CAPITAL' : 'EQUIPMENT';
    }
    const app = bank.state.apps[info];
    app.informationRequest!.requirements = [{ path: '/purpose', message: 'Уточните цель кредита' }];
    bank.state.jobs = [];
    bank.checkpoint();
    return { draft, info, manual };
  });
  const purpose = page.getByLabel('Цель кредита', { exact: true });
  await page.goto('/applications/' + ids.draft);
  await expect(purpose).toHaveValue('WORKING_CAPITAL');
  await expect(purpose.locator('option:checked')).toBeDisabled();
  await page.getByLabel('Согласие на обработку персональных данных', { exact: true }).check();
  await page.getByLabel('Согласие на запрос кредитной истории', { exact: true }).check();
  await expect(
    page.getByRole('button', { name: 'Проверить и отправить →', exact: true }),
  ).toBeDisabled();
  let patches = 0;
  page.on('request', (r) => {
    if (r.method() === 'PATCH') patches++;
  });
  await page.getByRole('button', { name: 'Сохранить черновик', exact: true }).click();
  await expect(purpose).toHaveAttribute('aria-invalid', 'true');
  await expect(page.locator('#f-purpose-validation')).toContainText(
    'доступную для выбранного продукта',
  );
  expect(patches).toBe(0);
  await page.screenshot({ path: 'test-results/product-purpose-legacy-1440.png', fullPage: true });
  await purpose.selectOption('EQUIPMENT');
  const saved = page.waitForRequest((r) => r.method() === 'PATCH');
  await page.getByRole('button', { name: 'Сохранить черновик', exact: true }).click();
  expect((await saved).postDataJSON()).toMatchObject({ purpose: 'EQUIPMENT' });
  await expect(page.getByText('✓ Черновик сохранён')).toBeVisible();
  await page.reload();
  await expect(purpose).toHaveValue('EQUIPMENT');
  await expect(purpose.locator('option')).toHaveText(['Выберите цель', 'Оборудование']);
  await page.goto('/applications/' + ids.info);
  await expect(purpose.locator('option')).toHaveText(['Выберите цель', 'Оборудование']);
  await expect(purpose).toHaveValue('EQUIPMENT');
  await page.getByRole('button', { name: 'Выйти из кабинета ↗', exact: true }).click();
  await login(page, true);
  await page.goto('/staff/applications/' + ids.manual);
  await page.getByLabel('Действие сотрудника', { exact: true }).selectOption('AMEND_DATA');
  await expect(purpose.locator('option')).toHaveText(['Выберите цель', 'Оборудование']);
  await expect(purpose).toHaveValue('EQUIPMENT');
});
