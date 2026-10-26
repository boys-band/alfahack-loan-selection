import { test, expect } from '@playwright/test';

test('staff registry keeps filters and pagination across card, history and reload', async ({
  page,
}) => {
  await page.goto('/demo-access');
  await page.getByLabel('Сотрудник', { exact: true }).check();
  await page.getByLabel('Демотокен', { exact: true }).fill('demo-staff');
  await page.getByRole('button', { name: 'Войти →', exact: true }).click();
  await expect(page).toHaveURL(/staff\/manual-reviews$/);
  await page.evaluate(() => {
    for (let i = 0; i < 3; i++) window.__MOCK__!.seed('approved', 'OFFER_READY');
    window.__MOCK__!.seed('approved', 'DRAFT');
  });
  await page.goto('/staff/applications?limit=1&approvalType=AUTO&status=OFFER_READY');
  const first = page.getByRole('link', { name: /Открыть заявку/ });
  await expect(first).toHaveCount(1);
  const href = await first.getAttribute('href');
  await page.getByRole('button', { name: /Следующая/ }).click();
  await expect(page).toHaveURL(/cursor=/);
  await expect(first).not.toHaveAttribute('href', href!);
  await page.reload();
  await expect(first).toHaveCount(1);
  await first.click();
  await expect(page.getByText('Автоматически одобрено', { exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Принять предложение', exact: true })).toHaveCount(
    0,
  );
  await page.getByRole('link', { name: 'История и версии ↗' }).click();
  await expect(page.getByRole('heading', { name: 'История и версии' })).toBeVisible();
  await page.getByRole('link', { name: '← Актуальная заявка' }).click();
  await page.getByRole('link', { name: '← Все заявки' }).click();
  await expect(page).toHaveURL(/cursor=/);
  await page.getByLabel('Тип одобрения', { exact: true }).selectOption('MANUAL');
  await expect(page).not.toHaveURL(/cursor=/);
  await expect(page.getByText('Заявок по выбранным фильтрам нет.')).toBeVisible();
});
