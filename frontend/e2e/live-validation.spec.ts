import { expect, test, type Page } from '@playwright/test';

async function login(page: Page, role: 'customer' | 'staff' | 'admin') {
  await page.goto('/demo-access');
  await page
    .getByLabel(
      { customer: 'Предприниматель', staff: 'Сотрудник', admin: 'Администратор продуктов' }[role],
      { exact: true },
    )
    .check();
  await page.getByLabel('Демотокен', { exact: true }).fill('demo-' + role);
  await page.getByRole('button', { name: 'Войти →' }).click();
  await expect(page).not.toHaveURL(/demo-access$/);
}

test('admin sees simultaneous input errors, related bounds and no command before correction', async ({
  page,
}) => {
  await login(page, 'admin');
  await page.goto('/admin/products/working-capital');
  const rate = page.getByLabel('Годовая ставка, б.п.');
  const term = page.getByLabel('Минимальный срок, мес.');
  await rate.fill('-1');
  await term.fill('121');
  await expect(rate).toHaveAttribute('aria-invalid', 'true');
  await expect(term).toHaveAttribute('aria-invalid', 'true');
  await expect(page.locator('#admin-annualRateBps-validation')).toContainText('от 0 до 10000');
  await expect(page.locator('#admin-minTermMonths-validation')).toContainText('от 1 до 120');
  await rate.fill('2100');
  await term.fill('6');
  await expect(rate).not.toHaveAttribute('aria-invalid', 'true');
  await page.getByLabel('Минимальная сумма, ₽').fill('6000000');
  await expect(page.getByLabel('Максимальная сумма, ₽')).toHaveAttribute('aria-invalid', 'true');
  await page.getByLabel('Причина изменения (10–2000 символов)').fill('🙂'.repeat(5));
  await page.getByRole('button', { name: 'Проверить и опубликовать новую версию' }).click();
  await expect(page.locator('#admin-minAmountRub-validation')).toBeVisible();
  await expect(page.locator('#admin-maxAmountRub-validation')).toBeVisible();
  await expect(page.locator('#admin-reason-validation')).toBeVisible();
  expect(
    await page.evaluate(() =>
      Object.keys(sessionStorage).filter((key) => key.startsWith('alfa.admin.commands.')),
    ),
  ).toHaveLength(0);
  await page.screenshot({ path: 'test-results/live-validation-admin.png', fullPage: true });
  await page.getByLabel('Минимальная сумма, ₽').fill('100000');
  await page
    .getByLabel('Причина изменения (10–2000 символов)')
    .fill('Изменение ставки для проверки формы');
  await expect(page.locator('[id$="-validation"]')).toHaveCount(0);
  page.once('dialog', (dialog) => dialog.accept());
  await page.getByRole('button', { name: 'Проверить и опубликовать новую версию' }).click();
  await expect(page.getByText('Версия 2 опубликована.')).toBeVisible();
});

test('new product shows all missing fields at once and validates ID and purposes live', async ({
  page,
}) => {
  await login(page, 'admin');
  await page.goto('/admin/products/new');
  await page.getByRole('button', { name: 'Создать программу' }).click();
  await expect(page.getByLabel('Название', { exact: true })).toHaveAttribute(
    'aria-invalid',
    'true',
  );
  await expect(page.getByLabel('Описание', { exact: true })).toHaveAttribute(
    'aria-invalid',
    'true',
  );
  await expect(page.getByLabel('Годовая ставка, б.п.')).toHaveAttribute('aria-invalid', 'true');
  await expect(page.locator('#admin-purposes-validation')).toContainText(
    'Выберите хотя бы одну цель',
  );
  await expect(page.getByLabel('Идентификатор')).toBeFocused();
  await page.getByLabel('Идентификатор').fill('656');
  await expect(page.locator('#admin-product-id-validation')).toContainText('первый символ — буква');
  await page.getByLabel('Идентификатор').fill('BAD_ID');
  await expect(page.locator('#admin-product-id-validation')).toContainText(
    'строчные латинские буквы',
  );
  await page.getByLabel('Идентификатор').fill('valid-id');
  await expect(page.locator('#admin-product-id-validation')).toHaveCount(0);
  await page.getByLabel('Оборотные средства').check();
  await expect(page.locator('#admin-purposes-validation')).toHaveCount(0);
});

test('customer sees both numeric errors before submit and can still save an incomplete draft', async ({
  page,
}) => {
  await login(page, 'customer');
  await page.goto('/applications/new');
  const amount = page.getByLabel('Сумма, ₽', { exact: true });
  const term = page.getByLabel('Срок, мес.', { exact: true });
  await amount.fill('-1');
  await term.fill('121');
  await expect(amount).toHaveAttribute('aria-invalid', 'true');
  await expect(term).toHaveAttribute('aria-invalid', 'true');
  await amount.fill('100000');
  await expect(amount).not.toHaveAttribute('aria-invalid', 'true');
  await expect(term).toHaveAttribute('aria-invalid', 'true');
  await amount.fill('');
  await term.fill('');
  await page.getByLabel('Ваша компания').selectOption({ index: 1 });
  await page.getByLabel('Продукт', { exact: true }).selectOption({ index: 1 });
  const request = page.waitForRequest(
    (request) => request.method() === 'POST' && request.url().endsWith('/v2/applications'),
  );
  await page.getByRole('button', { name: 'Создать черновик' }).click();
  expect((await request).postDataJSON()).toMatchObject({ amountRub: null, termMonths: null });
  await expect(page).toHaveURL(/\/applications\/[0-9a-f-]+$/);
});

test('staff sees missing requirement and short reason together before sending an action', async ({
  page,
}) => {
  await login(page, 'staff');
  const id = await page.evaluate(() => window.__MOCK__!.seed('manual', 'MANUAL_REVIEW'));
  await page.goto('/staff/applications/' + id);
  await page.getByLabel('Действие сотрудника').selectOption('REQUEST_INFO');
  await page.getByLabel('Сообщение 1').fill(' ');
  await page.getByLabel('Обоснование решения (10–2000 символов)').fill('Коротко');
  await expect(page.locator('#req-message-0-validation')).toBeVisible();
  await expect(page.locator('#reason-validation')).toBeVisible();
  await page.getByLabel('Сообщение 1').fill('Опишите деятельность компании');
  await expect(page.locator('#req-message-0-validation')).toHaveCount(0);
  await expect(page.locator('#reason-validation')).toBeVisible();
});
