import { test, expect, type Page } from '@playwright/test';

async function login(page: Page) {
  await page.goto('/demo-access');
  await page.getByLabel('Демотокен', { exact: true }).fill('demo-customer');
  await page.getByRole('button', { name: 'Войти →', exact: true }).click();
  await expect(page).toHaveURL(/\/applications$/);
}
async function seed(
  page: Page,
  scenario: 'approved' | 'verification-info',
  status: 'NEEDS_INFO' | 'AWAITING_SIGNATURE' | 'DRAFT',
) {
  return page.evaluate(({ scenario, status }) => window.__MOCK__!.seed(scenario, status), {
    scenario,
    status,
  });
}

test('unsupported information requirement is displayed and cannot be silently submitted', async ({
  page,
}) => {
  await login(page);
  const id = await seed(page, 'verification-info', 'NEEDS_INFO');
  await page.evaluate((id) => {
    const bank = window.__MOCK__!,
      app = bank.state.apps[id];
    app.informationRequest!.requirements.push({
      path: '/additionalInfo/unrecognizedDocument',
      message: 'Предоставьте нестандартный документ',
    });
    bank.checkpoint();
  }, id);
  await page.goto('/applications/' + id);
  await expect(
    page.getByText('Предоставьте нестандартный документ', { exact: true }),
  ).toBeVisible();
  await expect(
    page.getByText('Непокрытое требование — требуется уточнение сотрудника.', { exact: true }),
  ).toBeVisible();
  await expect(
    page.getByRole('button', { name: 'Отправить сведения', exact: true }),
  ).toBeDisabled();
});

test('accepted offer expiry does not replace the independent agreement signing deadline', async ({
  page,
}) => {
  await login(page);
  const id = await seed(page, 'approved', 'AWAITING_SIGNATURE');
  await page.evaluate((id) => {
    const bank = window.__MOCK__!,
      app = bank.state.apps[id],
      agreement = bank.state.agreements[id];
    const expired = new Date(Date.now() - 3600000).toISOString();
    app.decision!.offer!.expiresAt = expired;
    agreement.offer.expiresAt = expired;
    bank.step = 100;
    bank.checkpoint();
  }, id);
  await page.goto('/applications/' + id);
  await expect(page.getByLabel('Полный текст договора')).toBeVisible();
  await page.getByRole('checkbox', { name: /Я прочитал полный документ/ }).check();
  await expect(page.getByRole('button', { name: 'Подписать договор', exact: true })).toBeEnabled();
  await page.getByRole('button', { name: 'Подписать договор', exact: true }).click();
  await expect(page.getByText('✓ ВЫДАЧА ПОДТВЕРЖДЕНА', { exact: true })).toBeVisible();
});

test('idempotency conflict blocks automatic new keys until the user explicitly acknowledges it', async ({
  page,
}) => {
  await login(page);
  const id = await seed(page, 'approved', 'DRAFT');
  await page.goto('/applications/' + id);
  await page.getByLabel('Сумма, ₽', { exact: true }).fill('1200000');
  await page.evaluate(() => {
    window.__MOCK__!.nextFault = {
      operation: 'updateDraft',
      status: 409,
      code: 'IDEMPOTENCY_CONFLICT',
    };
  });
  const sent: string[] = [];
  page.on('request', (request) => {
    if (request.method() === 'PATCH') sent.push(request.headers()['idempotency-key']);
  });
  await page.getByRole('button', { name: 'Сохранить черновик', exact: true }).click();
  await expect(page.getByText('Действие не выполнено', { exact: true })).toBeVisible();
  await expect(
    page.getByRole('button', { name: 'Проверить и повторить исходную команду', exact: true }),
  ).toHaveCount(0);
  await expect(
    page.getByRole('button', { name: 'Сохранить черновик', exact: true }),
  ).toBeDisabled();
  await expect(page.getByLabel('Сумма, ₽', { exact: true })).toHaveValue('1200000');
  expect(sent).toHaveLength(1);
  await page
    .getByRole('button', { name: 'Я ознакомился с изменениями — вернуться к форме', exact: true })
    .click();
  await page.getByRole('button', { name: 'Сохранить черновик', exact: true }).click();
  await expect(page.getByText('✓ Черновик сохранён', { exact: true })).toBeVisible();
  expect(sent).toHaveLength(2);
  expect(sent[1]).not.toBe(sent[0]);
});

test('empty list and catalog error have explicit states and retry restores products', async ({
  page,
}) => {
  await login(page);
  await expect(
    page.getByText('Заявок пока нет. Создайте первую заявку или изучите продукты.', {
      exact: true,
    }),
  ).toBeVisible();
  await page.evaluate(() => {
    window.__MOCK__!.nextFault = { operation: 'listProducts', status: 503 };
  });
  await page.getByRole('link', { name: 'Кредитные продукты', exact: false }).click();
  await expect(page.getByRole('alert')).toBeVisible();
  await page.getByRole('button', { name: 'Повторить запрос', exact: true }).click();
  await expect(
    page.getByRole('heading', { name: 'На развитие бизнеса', exact: true }),
  ).toBeVisible();
});
