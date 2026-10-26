import { test, expect, login, open, accept, sign, refresh } from './fixtures';

test('global staff registry follows automatic approval through history, signature and disbursement', async ({
  page,
  bank,
  context,
}) => {
  const id = await bank.offered();
  const original = await bank.version(id, 1);
  let manualRequests = 0;
  page.on('request', (r) => {
    if (r.url().endsWith('/manual-review')) manualRequests++;
  });
  await login(page, 'staff');
  await page.getByRole('link', { name: 'Все заявки', exact: true }).click();
  await page.getByLabel('Статус заявки', { exact: true }).selectOption('OFFER_READY');
  await page.getByLabel('Тип одобрения', { exact: true }).selectOption('AUTO');
  const row = page.getByRole('row').filter({ hasText: id });
  await expect(row).toBeVisible();
  await expect(row).toContainText('Автоматически одобрено');
  await page.reload();
  await expect(row).toBeVisible();
  await row.getByRole('link', { name: /Открыть заявку/ }).click();
  await expect(page.getByText('Автоматически одобрено', { exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Принять предложение', exact: true })).toHaveCount(
    0,
  );
  await page.getByRole('link', { name: 'История и версии ↗' }).click();
  await expect(page.getByRole('heading', { name: 'История и версии' })).toBeVisible();
  await page.getByRole('link', { name: 'Снимок данных 1 →' }).first().click();
  await expect(page.getByRole('heading', { name: 'Версия данных 1' })).toBeVisible();
  await page.goBack();
  await page.getByRole('link', { name: '← Актуальная заявка' }).click();
  await expect(page.getByRole('link', { name: '← Все заявки' })).toHaveAttribute(
    'href',
    /approvalType=AUTO/,
  );
  const customer = await context.newPage();
  await login(customer);
  await open(customer, id);
  await accept(customer);
  await sign(customer);
  await bank.wait(id, 'DISBURSED');
  await refresh(page);
  await expect(page.getByText('Автоматически одобрено', { exact: true })).toBeVisible();
  await page.getByRole('link', { name: 'Документ договора ↗' }).click();
  await expect(page.getByLabel('Полный текст договора', { exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Подписать договор', exact: true })).toHaveCount(0);
  expect(manualRequests).toBe(0);
  const list = await bank.get<{ items: { id: string }[] }>(
    '/staff/applications?approvalType=AUTO&status=DISBURSED',
    'staff',
  );
  expect(list.items.some((a) => a.id === id)).toBe(true);
  expect(await bank.version(id, 1)).toEqual(original);
  await customer.close();
});

test('manual queue remains independent and staff-approved results enter MANUAL registry', async ({
  page,
  bank,
}) => {
  const id = await bank.manual();
  await login(page, 'staff');
  await open(page, id, 'staff');
  const app = await bank.app(id);
  await bank.mutate(
    id,
    'manual-review/actions',
    {
      action: 'APPROVE',
      reason: 'Проверка сотрудником в сквозном тесте реестра',
      termsId: app.decision!.selectedTermsId,
      requirements: [],
      changes: null,
      factsResolution: null,
    },
    'staff',
  );
  await bank.wait(id, 'OFFER_READY');
  await refresh(page);
  await expect(page.getByText('Одобрено сотрудником', { exact: true }).first()).toBeVisible();
  await expect(
    page.getByRole('button', { name: 'Сохранить решение сотрудника', exact: true }),
  ).toHaveCount(0);
  await page.goto('/staff/applications?approvalType=MANUAL');
  await expect(page.getByRole('row').filter({ hasText: id })).toBeVisible();
  const queue = await bank.get<{ items: { applicationId: string }[] }>(
    '/staff/manual-reviews',
    'staff',
  );
  expect(queue.items.some((a) => a.applicationId === id)).toBe(false);
});
