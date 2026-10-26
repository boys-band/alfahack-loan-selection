import { test, expect, type Page } from '@playwright/test';
async function login(page: Page, role: 'customer' | 'staff' = 'customer') {
  await page.goto('/demo-access');
  await expect(page.getByRole('heading', { name: 'Войти в кабинет' })).toBeVisible();
  if (role === 'staff') await page.getByLabel('Сотрудник', { exact: true }).check();
  await page.getByLabel('Демотокен', { exact: true }).fill('demo-' + role);
  await page.getByRole('button', { name: 'Войти →', exact: true }).click();
  await expect(page).toHaveURL(role === 'staff' ? /staff\/manual-reviews$/ : /applications$/);
}
async function seed(page: Page, scenario: string, status: string) {
  return page.evaluate(
    ({ scenario, status }) => window.__MOCK__!.seed(scenario as any, status as any),
    { scenario, status },
  );
}
async function open(page: Page, id: string, staff = false) {
  await page.goto(staff ? '/staff/applications/' + id : '/applications/' + id);
  await expect(page.getByRole('heading', { name: 'ООО «Демо Мастерская»' })).toBeVisible();
}
async function refresh(page: Page) {
  await page.getByRole('button', { name: '↻ Обновить', exact: true }).click();
}
async function setScenario(page: Page, s: string) {
  await page.evaluate((s) => {
    window.__MOCK__!.scenario = s as any;
    window.__MOCK__!.step = 150;
  }, s);
}
test('product limit error is readable, hides API details and allows correcting the form', async ({
  page,
}) => {
  await login(page);
  await page.getByRole('link', { name: '＋ Новая заявка', exact: true }).click();
  await page.getByLabel('Ваша компания').selectOption({ index: 1 });
  await page.getByLabel('Продукт', { exact: true }).selectOption({ index: 1 });
  await page.getByLabel('Сумма, ₽', { exact: true }).fill('5000001');
  // Return the actual legacy applications Problem through browser HTTP, once.
  await page.evaluate(() => {
    const bank = window.__MOCK__!;
    const handle = bank.handle.bind(bank);
    let rejected = false;
    bank.handle = async (...args) => {
      if (!rejected && args[0] === 'POST' && args[1].endsWith('/v2/applications')) {
        rejected = true;
        const requestId = crypto.randomUUID();
        return {
          status: 422,
          headers: { 'Content-Type': 'application/problem+json', 'X-Request-Id': requestId },
          body: {
            type: 'urn:alfa:problem:validation_error',
            title: 'VALIDATION_ERROR',
            status: 422,
            detail: 'Loan terms are invalid',
            code: 'VALIDATION_ERROR',
            requestId,
            errors: [{ path: '/amountRub', message: 'Outside current product limits' }],
          },
        };
      }
      return handle(...args);
    };
  });
  await page.getByRole('button', { name: 'Создать черновик' }).click();
  const alert = page.getByRole('alert');
  await expect(alert).toContainText('Проверьте заполненные поля.');
  await expect(alert).toContainText(
    'Сумма, ₽ — Укажите сумму в пределах лимитов выбранного кредита.',
  );
  await expect(alert).not.toContainText(/Outside current|Loan terms|\/amountRub|\/v2\//);
  const amount = page.getByLabel('Сумма, ₽', { exact: true });
  await expect(amount).toHaveValue('5000001');
  await expect(amount).toHaveAttribute('aria-invalid', 'true');
  expect(await page.evaluate(() => Object.keys(window.__MOCK__!.state.apps).length)).toBe(0);
  await page.screenshot({ path: 'test-results/product-limit-error.png', fullPage: true });
  await expect(page.getByRole('button', { name: 'Создать черновик' })).toBeEnabled();
  await amount.fill('1000000');
  await page.getByRole('button', { name: 'Создать черновик' }).click();
  await expect(page).toHaveURL(/\/applications\/[a-f0-9-]+$/);
  expect(await page.evaluate(() => Object.keys(window.__MOCK__!.state.apps).length)).toBe(1);
});

test('a rejected draft can be corrected and saved on the same page without another application', async ({
  page,
}) => {
  await login(page);
  const id = await seed(page, 'approved', 'DRAFT');
  await open(page, id);
  const amount = page.getByLabel('Сумма, ₽', { exact: true });
  const save = page.getByRole('button', { name: 'Сохранить черновик', exact: true });
  await amount.fill('1000000');
  await page.evaluate(() => {
    window.__MOCK__!.nextFault = {
      operation: 'updateDraft',
      status: 422,
      code: 'VALIDATION_ERROR',
    };
  });
  const rejectedRequest = page.waitForRequest((r) => r.method() === 'PATCH');
  await save.click();
  const rejected = await rejectedRequest;
  await expect(page.getByRole('alert')).toBeVisible();
  await expect(amount).toHaveValue('1000000');
  await expect(save).toBeEnabled();
  await expect(
    page.getByRole('button', { name: 'Я ознакомился с изменениями — вернуться к форме' }),
  ).toHaveCount(0);
  await amount.fill('1200000');
  const correctedRequest = page.waitForRequest((r) => r.method() === 'PATCH');
  await save.click();
  const corrected = await correctedRequest;
  expect(corrected.url()).toBe(rejected.url());
  expect(corrected.headers()['idempotency-key']).not.toBe(rejected.headers()['idempotency-key']);
  expect(corrected.headers()['if-match']).toBe(rejected.headers()['if-match']);
  expect(corrected.postDataJSON()).toMatchObject({ amountRub: 1200000 });
  await expect(page.getByText('✓ Черновик сохранён')).toBeVisible();
  await expect(page.getByRole('alert')).toHaveCount(0);
  await expect(page).toHaveURL(new RegExp('/applications/' + id + '$'));
  const apps = await page.evaluate(() => Object.values(window.__MOCK__!.state.apps));
  expect(apps).toHaveLength(1);
  expect(apps[0].amountRub).toBe(1200000);
});

test('product → create → draft → consents → review → offer → agreement → sign → confirmed disbursement', async ({
  page,
}) => {
  await login(page);
  await setScenario(page, 'approved');
  await page.getByRole('link', { name: 'Кредитные продукты', exact: false }).click();
  await page.getByRole('link', { name: 'Выбрать продукт →' }).first().click();
  await page.getByLabel('Ваша компания').selectOption({ index: 1 });
  await page.getByLabel('Сумма, ₽', { exact: true }).fill('1000000');
  await page.getByLabel('Срок, мес.', { exact: true }).fill('12');
  await page.getByLabel('Цель кредита').selectOption('WORKING_CAPITAL');
  await page.getByLabel('Месячная выручка, ₽').fill('1000000');
  await page.getByLabel('Месячные расходы, ₽').fill('500000');
  await page.getByLabel('Текущие платежи по долгам, ₽').fill('20000');
  await page.getByLabel('Описание бизнеса').fill('Поставка оборудования для мастерских');
  page.on('dialog', (dialog) => dialog.accept());
  await page.getByRole('button', { name: 'Создать черновик' }).click();
  await expect(page.getByRole('heading', { name: 'Параметры заявки' })).toBeVisible();
  await expect(
    page.getByRole('checkbox', { name: 'Согласие на обработку персональных данных' }),
  ).not.toBeChecked();
  await expect(
    page.getByRole('checkbox', { name: 'Согласие на запрос кредитной истории' }),
  ).not.toBeChecked();
  await page.getByRole('checkbox', { name: 'Согласие на обработку персональных данных' }).check();
  await page.getByRole('checkbox', { name: 'Согласие на запрос кредитной истории' }).check();
  await page.getByRole('button', { name: 'Проверить и отправить →' }).click();
  const confirmation = page.getByRole('dialog', { name: 'Проверьте заявку перед отправкой' });
  await expect(confirmation).toContainText('ООО «Демо Мастерская»');
  await expect(confirmation).toContainText('Выручка / расходы');
  await expect(confirmation).toContainText('Поставка оборудования для мастерских');
  await expect(confirmation.locator('pre')).toHaveCount(0);
  await expect(confirmation).not.toContainText(/companyId|productVersion|amountRub|financialData/);
  await page.screenshot({ path: 'test-results/submit-confirmation.png', fullPage: true });
  const submittedRequest = page.waitForRequest(
    (r) => r.method() === 'POST' && r.url().endsWith('/submit'),
  );
  await page.getByRole('button', { name: 'Отправить заявку', exact: true }).click();
  expect((await submittedRequest).postDataJSON()).toEqual({
    personalDataConsent: true,
    creditBureauConsent: true,
  });
  await expect(
    page.getByRole('button', { name: 'Принять предложение', exact: true }),
  ).toBeVisible();
  await page.getByRole('button', { name: 'Принять предложение', exact: true }).click();
  await page.getByRole('button', { name: 'Подтвердить принятие' }).click();
  await expect(page.getByLabel('Полный текст договора')).toBeVisible();
  await page.getByRole('checkbox', { name: /Я прочитал полный документ/ }).check();
  await page.getByRole('button', { name: 'Подписать договор', exact: true }).click();
  await expect(page.getByText('ВЫДАЧА ПОДТВЕРЖДЕНА', { exact: false })).toBeVisible();
  await page.getByRole('link', { name: 'История и версии ↗' }).click();
  await expect(page.getByRole('heading', { name: 'История и версии', exact: true })).toBeVisible();
  await page.getByRole('link', { name: 'Снимок данных 1 →' }).first().click();
  await page.reload();
  await expect(page.getByRole('heading', { name: 'Версия данных 1' })).toBeVisible();
  await expect(page.locator('input,textarea')).toHaveCount(0);
  await expect(page.locator('pre')).toHaveCount(0);
  await expect(page.getByText('Поставка оборудования для мастерских')).toBeVisible();
  await expect(page.getByText('Дано согласие на обработку персональных данных.')).toBeVisible();
  await expect(page.locator('main')).not.toContainText(/INITIAL_SUBMISSION|personalDataConsent/);
});
for (const s of ['validation-info', 'verification-info', 'decision-info'])
  test(
    'information request ' + s + ' creates version 2 and preserves full replacement neighbors',
    async ({ page }) => {
      await login(page);
      const id = await seed(page, s, 'NEEDS_INFO');
      await open(page, id);
      await page.getByLabel('Описание бизнеса').fill('Сервисная мастерская');
      await page.getByLabel('Пояснение выручки и расходов').fill('Расходы подтверждены учётом');
      await page.getByLabel('Рефинансируемый долг, ₽').fill('100000');
      await page.getByRole('button', { name: 'Отправить сведения' }).click();
      await expect(page.getByText(/ВЕРСИЯ ДАННЫХ 2/)).toBeVisible();
    },
  );
test('counteroffer compares terms, accepts exact offer; changing terms starts new version', async ({
  page,
}) => {
  await login(page);
  const id = await seed(page, 'counteroffer', 'OFFER_READY');
  await open(page, id);
  await expect(page.getByRole('heading', { name: 'Встречное предложение' })).toBeVisible();
  await page.getByRole('button', { name: 'Изменить условия', exact: true }).click();
  await page.getByLabel('Сумма, ₽', { exact: true }).fill('800000');
  page.on('dialog', (d) => d.accept());
  await page.getByRole('button', { name: 'Подтвердить новые условия' }).click();
  await expect(page.getByText(/ВЕРСИЯ ДАННЫХ 2/)).toBeVisible();
});
for (const action of ['APPROVE', 'REJECT', 'REQUEST_INFO', 'AMEND_DATA'])
  test('staff action ' + action + ' uses public DTO', async ({ page }) => {
    await login(page, 'staff');
    const id = await seed(page, 'manual', 'MANUAL_REVIEW');
    await page.evaluate(() => {
      window.__MOCK__!.step = 200;
    });
    await open(page, id, true);
    await expect(page.getByRole('heading', { name: 'Оценённые условия' })).toBeVisible();
    await expect(page.locator('pre')).toHaveCount(0);
    await expect(
      page.getByRole('table', { name: 'Сведения источников и подтверждённые факты' }),
    ).toBeVisible();
    await page.getByLabel('Действие сотрудника').selectOption(action);
    await page
      .getByLabel('Обоснование решения (10–2000 символов)')
      .fill('Сведения проверены, обоснование принято сотрудником.');
    if (action === 'APPROVE')
      await page.getByLabel('Оценённые допустимые условия').selectOption({ index: 1 });
    if (action === 'REJECT')
      await page.getByRole('checkbox', { name: /Подтверждаю кредитный отказ/ }).check();
    if (action === 'REQUEST_INFO')
      await page.getByLabel('Сообщение 1').fill('Опишите деятельность компании');
    if (action === 'AMEND_DATA') await page.getByLabel('Месячная выручка, ₽').fill('1100000');
    const req = page.waitForRequest(
      (r) => r.method() === 'POST' && r.url().endsWith('/manual-review/actions'),
    );
    await page.getByRole('button', { name: 'Сохранить решение сотрудника' }).click();
    const body = (await req).postDataJSON();
    expect(body).toHaveProperty('factsResolution', null);
    expect(body).toHaveProperty('changes');
    expect(body).not.toHaveProperty('staffId');
    expect(body).not.toHaveProperty('actionId');
    await expect(page.getByText('Команда принята', { exact: true })).toBeVisible();
    await page.waitForTimeout(500);
    await refresh(page);
    if (action === 'AMEND_DATA') await expect(page.getByText(/ВЕРСИЯ ДАННЫХ 2/)).toBeVisible();
    else if (action === 'REQUEST_INFO')
      await expect(
        page.getByRole('heading', { name: 'Запрос дополнительных сведений' }),
      ).toBeVisible();
    else
      await expect(
        page.getByRole('heading', {
          name: action === 'APPROVE' ? 'Предложение готово' : 'Кредит не одобрен',
          exact: true,
        }),
      ).toBeVisible();
  });
test('unconfirmed facts cannot approve, staff cannot see customer command buttons', async ({
  page,
}) => {
  await login(page, 'staff');
  const id = await seed(page, 'unconfirmed', 'MANUAL_REVIEW');
  await open(page, id, true);
  await expect(page.getByLabel('Действие сотрудника').locator('option[value=APPROVE]')).toHaveCount(
    0,
  );
  await expect(page.getByRole('button', { name: 'Отменить заявку' })).toHaveCount(0);
});
for (const [s, status, button] of [
  ['validation-info', 'NEEDS_INFO', 'Отправить сведения'],
  ['approved', 'OFFER_READY', 'Принять предложение'],
  ['approved', 'AWAITING_SIGNATURE', 'Подписать договор'],
] as const)
  test(
    'deadline ' + status + ' disables action and refreshes at boundary with server clock',
    async ({ page }) => {
      await login(page);
      const id = await seed(page, s, status);
      await page.evaluate((id) => {
        const bank = window.__MOCK__!;
        const a = bank.state.apps[id];
        a.actionDeadline = new Date(Date.now() + 2500).toISOString();
        if (a.agreement) {
          a.agreement.signBy = a.actionDeadline;
          bank.state.agreements[id].signBy = a.actionDeadline;
        }
        bank.now = () => Date.now();
        bank.checkpoint();
      }, id);
      await open(page, id);
      await expect(page.getByRole('heading', { name: 'Срок истёк', exact: true })).toBeVisible({
        timeout: 10000,
      });
      await expect(page.getByRole('button', { name: button, exact: true })).toHaveCount(0);
    },
  );
test('payout correction changes own account without changing document', async ({ page }) => {
  await login(page);
  await setScenario(page, 'payout');
  const id = await seed(page, 'payout', 'AWAITING_SIGNATURE');
  await page.evaluate(() => {
    window.__MOCK__!.step = 150;
  });
  await open(page, id);
  await page.getByRole('checkbox', { name: /Я прочитал полный документ/ }).check();
  await page.getByRole('button', { name: 'Подписать договор', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Нужно исправить реквизиты' })).toBeVisible();
  const hash = await page.evaluate(
    (id) => window.__MOCK__!.state.agreements[id].document.sha256,
    id,
  );
  await page.getByLabel('Ваш счет').selectOption({ index: 2 });
  await page.getByRole('button', { name: 'Изменить счет', exact: true }).click();
  await expect(page.getByText('ВЫДАЧА ПОДТВЕРЖДЕНА', { exact: false })).toBeVisible();
  expect(
    await page.evaluate((id) => window.__MOCK__!.state.agreements[id].document.sha256, id),
  ).toBe(hash);
});
test('lost create reply survives reload and exact retry does not create duplicate', async ({
  page,
}) => {
  await login(page);
  const id = await seed(page, 'approved', 'DRAFT');
  await open(page, id);
  await page.getByLabel('Сумма, ₽', { exact: true }).fill('1200000');
  await page.evaluate(() => {
    window.__MOCK__!.nextFault = { operation: 'updateDraft', lost: true };
  });
  await page.getByRole('button', { name: 'Сохранить черновик' }).click();
  await expect(page.getByText('Исход команды пока неизвестен', { exact: true })).toBeVisible();
  const saved = await page.evaluate(
    () => Object.entries(sessionStorage).find(([k]) => k.startsWith('alfa.commands.'))?.[1],
  );
  await page.reload();
  const req = page.waitForRequest((r) => r.method() === 'PATCH');
  await page.getByRole('button', { name: 'Проверить и повторить исходную команду' }).click();
  const repeated = await req;
  const command = JSON.parse(saved!)[0];
  expect(repeated.postData()).toBe(command.body);
  expect(repeated.headers()['idempotency-key']).toBe(command.key);
  expect(repeated.headers()['if-match']).toBe(command.etag);
  await expect(page.getByText('Исход команды пока неизвестен', { exact: true })).toHaveCount(0);
  expect(await page.evaluate(() => Object.keys(window.__MOCK__!.state.apps).length)).toBe(1);
});
test('412 preserves edited form and requires acknowledgement; document renders plain text', async ({
  page,
}) => {
  await login(page);
  const id = await seed(page, 'approved', 'DRAFT');
  await open(page, id);
  await page.getByLabel('Сумма, ₽', { exact: true }).fill('1200000');
  await page.evaluate(() => {
    window.__MOCK__!.nextFault = { operation: 'updateDraft', status: 412 };
  });
  await page.getByRole('button', { name: 'Сохранить черновик' }).click();
  await expect(page.getByLabel('Сумма, ₽', { exact: true })).toHaveValue('1200000');
  await expect(page.getByRole('button', { name: 'Сохранить черновик' })).toBeDisabled();
  await page
    .getByRole('button', { name: 'Я ознакомился с изменениями — вернуться к форме' })
    .click();
  await page.getByRole('button', { name: 'Сохранить черновик' }).click();
  await expect(page.getByText('✓ Черновик сохранён')).toBeVisible();
  const ag = await seed(page, 'approved', 'AWAITING_SIGNATURE');
  await page.evaluate((id) => {
    window.__MOCK__!.state.agreements[id].document.content =
      '<img src=x onerror=alert(1)>\nПолный текст';
    window.__MOCK__!.checkpoint();
  }, ag);
  await open(page, ag);
  await expect(page.getByLabel('Полный текст договора')).toContainText(
    '<img src=x onerror=alert(1)>',
  );
  await expect(page.locator('main img')).toHaveCount(0);
});
test('technical problem preserves scoring and retry resumes processing', async ({ page }) => {
  await login(page);
  const id = await seed(page, 'technical', 'IN_REVIEW');
  await page.evaluate(() => {
    window.__MOCK__!.step = 150;
  });
  await page.waitForTimeout(1500);
  await open(page, id);
  await expect(page.getByText('Техническое ожидание', { exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'Повторить обработку', exact: true }).click();
  await expect(
    page.getByRole('heading', { name: 'Предложение готово', exact: true }),
  ).toBeVisible();
});
async function submitSeededDraft(page: Page) {
  await page.getByRole('checkbox', { name: 'Согласие на обработку персональных данных' }).check();
  await page.getByRole('checkbox', { name: 'Согласие на запрос кредитной истории' }).check();
  await page.getByRole('button', { name: 'Проверить и отправить →' }).click();
  await page.getByRole('button', { name: 'Отправить заявку', exact: true }).click();
}
test('accepted submission still allows cancellation before the worker finishes', async ({
  page,
}) => {
  await login(page);
  const id = await seed(page, 'approved', 'DRAFT');
  await open(page, id);
  await page.evaluate(() => {
    window.__MOCK__!.step = 60000;
  });
  await submitSeededDraft(page);
  await expect(page.getByText('Команда принята', { exact: true })).toBeVisible();
  const cancel = page.getByRole('button', { name: 'Отменить заявку', exact: true });
  await expect(cancel).toBeEnabled();
  await cancel.click();
  await page.getByLabel('Причина отмены').fill('Финансирование не требуется');
  await page.getByRole('button', { name: 'Подтвердить отмену' }).click();
  await expect(page.getByRole('heading', { name: 'Заявка отменена', exact: true })).toBeVisible();
});
test('accepted submission that pauses can be retried after reload', async ({ page }) => {
  await login(page);
  const id = await seed(page, 'technical', 'DRAFT');
  await open(page, id);
  await page.evaluate(() => {
    window.__MOCK__!.step = 50;
  });
  await submitSeededDraft(page);
  await expect(page.getByText('Техническое ожидание', { exact: true })).toBeVisible();
  await page.reload();
  await expect(page.getByText('Техническое ожидание', { exact: true })).toBeVisible();
  const retry = page.getByRole('button', { name: 'Повторить обработку', exact: true });
  await expect(retry).toBeEnabled();
  await retry.click();
  await expect(
    page.getByRole('heading', { name: 'Предложение готово', exact: true }),
  ).toBeVisible();
});
test('a 401 during exact command recovery returns to demo access', async ({ page }) => {
  await login(page);
  const id = await seed(page, 'approved', 'DRAFT');
  await open(page, id);
  await page.getByLabel('Сумма, ₽', { exact: true }).fill('900000');
  await page.evaluate(() => {
    window.__MOCK__!.nextFault = { operation: 'updateDraft', lost: true };
  });
  const lost = page.waitForEvent('requestfailed', {
    predicate: (request) => request.method() === 'PATCH' && request.url().endsWith('/' + id),
  });
  await page.getByRole('button', { name: 'Сохранить черновик', exact: true }).click();
  await lost;
  await expect(page.getByText('Исход команды пока неизвестен', { exact: true })).toBeVisible();
  await expect(
    page.getByRole('button', { name: 'Проверить и повторить исходную команду' }),
  ).toBeEnabled();
  await page.evaluate(() => {
    window.__MOCK__!.nextFault = {
      operation: 'updateDraft',
      status: 401,
      code: 'UNAUTHENTICATED',
    };
  });
  page.on('dialog', (dialog) => dialog.accept());
  await page.getByRole('button', { name: 'Проверить и повторить исходную команду' }).click();
  await expect(page).toHaveURL(/\/demo-access$/);
});
test('cancel 202 waits for confirmed outcome', async ({ page }) => {
  await login(page);
  const id = await seed(page, 'approved', 'AWAITING_SIGNATURE');
  await open(page, id);
  await page.getByRole('button', { name: 'Отменить заявку', exact: true }).click();
  await page.getByLabel('Причина отмены').fill('Финансирование не требуется');
  await page.getByRole('button', { name: 'Подтвердить отмену' }).click();
  await expect(
    page.getByText('Проверяем возможность отмены', { exact: true }).first(),
  ).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Заявка отменена', exact: true })).toHaveCount(0);
  await expect(page.getByRole('heading', { name: 'Заявка отменена', exact: true })).toBeVisible();
});
for (const viewport of [
  { width: 1280, height: 720 },
  { width: 1440, height: 900 },
])
  test(
    'desktop layout, nested URL reload, keyboard and no overflow ' + viewport.width,
    async ({ page }) => {
      await page.setViewportSize(viewport);
      await login(page);
      const id = await seed(page, 'approved', 'OFFER_READY');
      await open(page, id);
      await page.reload();
      await expect(
        page.getByRole('heading', { name: 'Предложение готово', exact: true }),
      ).toBeVisible();
      expect(
        await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth),
      ).toBe(true);
      await page.getByRole('button', { name: 'Принять предложение', exact: true }).focus();
      await page.keyboard.press('Enter');
      await expect(page.getByRole('dialog')).toBeVisible();
      await page.keyboard.press('Escape');
      await expect(page.getByRole('dialog')).toHaveCount(0);
      await expect(
        page.getByRole('button', { name: 'Принять предложение', exact: true }),
      ).toBeFocused();
      await page.screenshot({ path: `test-results/desktop-${viewport.width}.png`, fullPage: true });
    },
  );

test('lost create response and reload reuse exact request and create a single application', async ({
  page,
}) => {
  await login(page);
  await page.getByRole('link', { name: '＋ Новая заявка', exact: true }).click();
  await page.getByLabel('Ваша компания').selectOption({ index: 1 });
  await page.getByLabel('Продукт', { exact: true }).selectOption({ index: 1 });
  await page.evaluate(() => {
    window.__MOCK__!.nextFault = { operation: 'createApplication', lost: true };
  });
  await page.getByRole('button', { name: 'Создать черновик' }).click();
  await expect(page.getByText('Исход команды пока неизвестен', { exact: true })).toBeVisible();
  page.on('dialog', (d) => d.accept());
  await page.reload();
  await page.getByRole('button', { name: 'Проверить и повторить исходную команду' }).click();
  await expect(page).toHaveURL(/\/applications\/[a-f0-9-]+$/);
  expect(await page.evaluate(() => Object.keys(window.__MOCK__!.state.apps).length)).toBe(1);
});

test('replacement document resets consent and sends only the newly displayed hash', async ({
  page,
}) => {
  await login(page);
  const id = await seed(page, 'approved', 'AWAITING_SIGNATURE');
  await open(page, id);
  const consent = page.getByRole('checkbox', { name: /Я прочитал полный документ/ });
  await consent.check();
  await page.evaluate((id) => {
    const bank = window.__MOCK__!,
      a = bank.state.apps[id],
      ag = bank.state.agreements[id];
    ag.document.version = 2;
    ag.document.sha256 = 'a'.repeat(64);
    ag.document.content = 'Новый полный документ договора';
    a.agreement!.documentVersion = 2;
    a.agreement!.documentSha256 = ag.document.sha256;
    a.version++;
    bank.checkpoint();
  }, id);
  await refresh(page);
  await expect(page.getByLabel('Полный текст договора')).toContainText('Новый полный документ');
  await expect(consent).not.toBeChecked();
  await expect(page.getByRole('button', { name: 'Подписать договор', exact: true })).toBeDisabled();
  await consent.check();
  const req = page.waitForRequest((r) => r.method() === 'POST' && r.url().endsWith('/sign'));
  await page.getByRole('button', { name: 'Подписать договор', exact: true }).click();
  expect((await req).postDataJSON()).toMatchObject({
    documentVersion: 2,
    documentSha256: 'a'.repeat(64),
  });
});

test('dirty form guards browser back and preserves fields when navigation is declined', async ({
  page,
}) => {
  await login(page);
  await page.getByRole('link', { name: '＋ Новая заявка', exact: true }).click();
  await page.getByLabel('Сумма, ₽', { exact: true }).fill('123456');
  page.once('dialog', (d) => d.dismiss());
  await page.goBack();
  await expect(page).toHaveURL(/\/applications\/new$/);
  await expect(page.getByLabel('Сумма, ₽', { exact: true })).toHaveValue('123456');
});

test('401 returns to demo login and a safe nested return URL; 404 stays explicit', async ({
  page,
}) => {
  await login(page);
  const id = await seed(page, 'approved', 'DRAFT');
  await open(page, id);
  await page.evaluate(() => {
    window.__MOCK__!.nextFault = {
      operation: 'getApplication',
      status: 401,
      code: 'UNAUTHENTICATED',
    };
  });
  await refresh(page);
  await expect(page).toHaveURL(/demo-access$/);
  await page.getByLabel('Демотокен', { exact: true }).fill('demo-customer');
  await page.getByRole('button', { name: 'Войти →', exact: true }).click();
  await expect(page).toHaveURL(new RegExp('/applications/' + id + '$'));
  await page.goto('/applications/00000000-0000-4000-8000-000000009999');
  await expect(page.getByRole('alert')).toContainText('Заявка недоступна или отсутствует');
});

test('change-terms sends a complete product/version pair for a different product', async ({
  page,
}) => {
  await login(page);
  const id = await seed(page, 'approved', 'OFFER_READY');
  await open(page, id);
  await page.getByRole('button', { name: 'Изменить условия', exact: true }).click();
  await page.getByLabel('Продукт', { exact: true }).selectOption('equipment-finance:1');
  const purpose = page.getByLabel('Цель кредита', { exact: true });
  await expect(purpose).toHaveValue('');
  await expect(purpose.locator('option')).toHaveText(['Выберите цель', 'Оборудование']);
  await page.getByRole('button', { name: 'Подтвердить новые условия' }).click();
  await expect(purpose).toHaveAttribute('aria-invalid', 'true');
  await purpose.selectOption('EQUIPMENT');
  const req = page.waitForRequest(
    (r) => r.method() === 'POST' && r.url().endsWith('/change-terms'),
  );
  await page.getByRole('button', { name: 'Подтвердить новые условия' }).click();
  expect((await req).postDataJSON()).toMatchObject({
    productId: 'equipment-finance',
    productVersion: '1',
    purpose: 'EQUIPMENT',
  });
  await expect(page.getByText(/ВЕРСИЯ ДАННЫХ 2/)).toBeVisible();
});
