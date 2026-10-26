import {
  test,
  expect,
  login,
  open,
  refresh,
  visibleStatus,
  accept,
  sign,
  company,
} from './fixtures';

const reason = 'Данные и допустимые условия проверены сотрудником в E2E';

for (const action of ['APPROVE', 'REJECT', 'REQUEST_INFO', 'AMEND_DATA'] as const) {
  test(`staff ${action}: role checks, explanation, real result and server audit`, async ({
    page,
    bank,
    context,
  }) => {
    const id = await bank.manual();
    const before = await bank.app(id),
      v1 = await bank.version(id, 1);
    await login(page, 'staff');
    await open(page, id, 'staff');
    await expect(
      page.getByRole('button', { name: 'Принять предложение', exact: true }),
    ).toHaveCount(0);
    await page.getByLabel('Действие сотрудника', { exact: true }).selectOption(action);
    const save = page.getByRole('button', { name: 'Сохранить решение сотрудника', exact: true });
    await expect(save).toBeDisabled();
    await page
      .getByLabel('Обоснование решения (10–2000 символов)', { exact: true })
      .fill('Коротко');
    await expect(save).toBeDisabled();
    await page.getByLabel('Обоснование решения (10–2000 символов)', { exact: true }).fill(reason);
    if (action === 'APPROVE')
      await page
        .getByLabel('Оценённые допустимые условия', { exact: true })
        .selectOption(before.decision!.selectedTermsId!);
    if (action === 'REJECT') {
      await expect(save).toBeDisabled();
      await page.getByRole('checkbox', { name: /Подтверждаю кредитный отказ/ }).check();
    }
    if (action === 'REQUEST_INFO') {
      await page
        .getByLabel('Сообщение 1', { exact: true })
        .fill('Опишите деятельность и основные товары');
      await page.getByRole('button', { name: '＋ Добавить требование', exact: true }).click();
      await page.getByLabel('Сообщение 2', { exact: true }).fill('Поясните структуру расходов');
      await page.getByRole('button', { name: '＋ Добавить требование', exact: true }).click();
      await page.getByRole('button', { name: 'Удалить требование 3', exact: true }).click();
      await expect(page.getByLabel('Сообщение 3', { exact: true })).toHaveCount(0);
    }
    if (action === 'AMEND_DATA')
      await page.getByLabel('Месячная выручка, ₽', { exact: true }).fill('2000000');
    const sent = page.waitForRequest(
      (r) => r.method() === 'POST' && r.url().endsWith('/manual-review/actions'),
    );
    await save.click();
    const body = (await sent).postDataJSON();
    expect(body).not.toHaveProperty('staffId');
    expect(body).not.toHaveProperty('actionId');
    expect(body).toHaveProperty('factsResolution', null);
    expect(body.requirements).toHaveLength(action === 'REQUEST_INFO' ? 2 : 0);
    const status =
      action === 'REJECT' ? 'REJECTED' : action === 'REQUEST_INFO' ? 'NEEDS_INFO' : 'OFFER_READY';
    const after = await bank.wait(id, status);
    await refresh(page);
    await visibleStatus(
      page,
      status === 'REJECTED'
        ? 'Кредит не одобрен'
        : status === 'NEEDS_INFO'
          ? 'Нужны дополнительные сведения'
          : 'Предложение готово',
    );
    const history = await bank.timeline(id);
    expect(history.items.some((e) => e.actor.type === 'STAFF' && e.actor.id === company(2))).toBe(
      true,
    );
    expect(await bank.version(id, 1)).toEqual(v1);
    if (action === 'APPROVE' || action === 'REJECT') {
      expect(after.decision?.staffAudit).toMatchObject({ staffId: company(2), reason });
    }
    if (action === 'AMEND_DATA') {
      expect(after.dataVersion).toBe(2);
      expect((await bank.version(id, 2)).origin).toBe('STAFF_AMENDMENT');
      expect(after.financialData?.monthlyRevenueRub).toBe(2000000);
    }
    // A customer in an independent tab sees the actual staff outcome, not a mock receipt.
    const customerPage = await context.newPage();
    await login(customerPage);
    await open(customerPage, id);
    if (action === 'REQUEST_INFO') {
      await expect(
        customerPage.getByText('Опишите деятельность и основные товары', { exact: true }),
      ).toBeVisible();
      await customerPage
        .getByLabel('Описание бизнеса', { exact: true })
        .fill('Мастерская ремонта промышленного оборудования');
      await customerPage
        .getByLabel('Пояснение выручки и расходов', { exact: true })
        .fill('Расходы подтверждены отчетностью');
      // Higher verified revenue allows automatic approval after the requested new data version.
      await customerPage.getByLabel('Месячная выручка, ₽', { exact: true }).fill('2000000');
      await customerPage.getByRole('button', { name: 'Отправить сведения', exact: true }).click();
      expect((await bank.wait(id, 'OFFER_READY')).dataVersion).toBe(2);
    } else if (action === 'APPROVE') {
      await accept(customerPage);
      await sign(customerPage);
      expect((await bank.app(id)).disbursement?.amountRub).toBe(1000000);
    }
    await customerPage.close();
  });
}

test('disputed facts cannot be approved; audited attestation creates version 2 and completes scoring', async ({
  page,
  bank,
}) => {
  const id = await bank.manual(true),
    old = await bank.app(id),
    snapshot = await bank.version(id, 1);
  expect(old.verification?.confirmed).toBe(false);
  await login(page, 'staff');
  await open(page, id, 'staff');
  await expect(
    page.getByLabel('Действие сотрудника', { exact: true }).locator('option[value=APPROVE]'),
  ).toHaveCount(0);
  await page.getByLabel('Действие сотрудника', { exact: true }).selectOption('AMEND_DATA');
  await page.getByRole('checkbox', { name: /Подтвердить уточнённые факты/ }).check();
  await page
    .getByLabel('Обоснование решения (10–2000 символов)', { exact: true })
    .fill('Подтвержденные документы устраняют расхождения источников');
  await page.getByRole('button', { name: 'Сохранить решение сотрудника', exact: true }).click();
  const current = await bank.wait(id, 'OFFER_READY');
  expect(current.dataVersion).toBe(2);
  expect(current.verification?.confirmed).toBe(true);
  expect(current.decision?.risk?.score).toBe(900);
  const v2 = await bank.version(id, 2);
  expect(v2.data.staffAttestation).toMatchObject({
    staffId: company(2),
    reason: 'Подтвержденные документы устраняют расхождения источников',
  });
  expect(await bank.version(id, 1)).toEqual(snapshot);
  await refresh(page);
  await visibleStatus(page, 'Предложение готово');
});

test('staff can reject disputed facts without a completed risk assessment', async ({
  page,
  bank,
}) => {
  const id = await bank.manual(true);
  await login(page, 'staff');
  await open(page, id, 'staff');
  await page.getByLabel('Действие сотрудника', { exact: true }).selectOption('REJECT');
  await page
    .getByLabel('Обоснование решения (10–2000 символов)', { exact: true })
    .fill('Расхождения не устранены предоставленными документами');
  await page.getByRole('checkbox', { name: /Подтверждаю кредитный отказ/ }).check();
  await page.getByRole('button', { name: 'Сохранить решение сотрудника', exact: true }).click();
  const app = await bank.wait(id, 'REJECTED');
  expect(app.decision?.assessmentId).toBeNull();
  expect(app.decision?.staffAudit?.staffId).toBe(company(2));
});

test('stale staff decision conflicts when another staff session already resolved the case', async ({
  page,
  bank,
}) => {
  const id = await bank.manual();
  await login(page, 'staff');
  await open(page, id, 'staff');
  await page.getByLabel('Действие сотрудника', { exact: true }).selectOption('REJECT');
  await page.getByLabel('Обоснование решения (10–2000 символов)', { exact: true }).fill(reason);
  await page.getByRole('checkbox', { name: /Подтверждаю кредитный отказ/ }).check();
  const before = await bank.app(id);
  await bank.mutate(
    id,
    'manual-review/actions',
    {
      action: 'APPROVE',
      reason: 'Первый сотрудник проверил и согласовал условия',
      termsId: before.decision!.selectedTermsId,
      requirements: [],
      changes: null,
      factsResolution: null,
    },
    'staff',
  );
  await bank.wait(id, 'OFFER_READY');
  await page.getByRole('button', { name: 'Сохранить решение сотрудника', exact: true }).click();
  await expect(page.getByText('Заявка изменилась', { exact: true })).toBeVisible();
  expect((await bank.app(id)).status).toBe('OFFER_READY');
  await visibleStatus(page, 'Предложение готово');
  await expect(
    page
      .getByRole('button', { name: 'Сохранить решение сотрудника', exact: true })
      .and(page.locator(':enabled')),
  ).toHaveCount(0);
});
