import { createHash } from 'node:crypto';
import {
  test,
  expect,
  login,
  open,
  submit,
  accept,
  sign,
  visibleStatus,
  refresh,
  company,
  financial,
} from './fixtures';
import type { ApplicationPage } from '../src/api/generated';

test('catalog → draft → consents → offer → document → sign → payout → immutable history', async ({
  page,
  bank,
}, info) => {
  await login(page);
  await page.getByRole('link', { name: 'Кредитные продукты', exact: true }).click();
  const product = page
    .locator('article')
    .filter({ has: page.getByRole('heading', { name: 'На развитие бизнеса', exact: true }) });
  await expect(product).toContainText('24%');
  await product.getByRole('link', { name: 'Выбрать продукт →' }).click();
  await page.getByLabel('Ваша компания', { exact: true }).selectOption(company(101));
  for (const [label, value] of [
    ['Сумма, ₽', '1000000'],
    ['Срок, мес.', '12'],
    ['Месячная выручка, ₽', '1000000'],
    ['Месячные расходы, ₽', '500000'],
    ['Текущие платежи по долгам, ₽', '20000'],
  ])
    await page.getByLabel(label, { exact: true }).fill(value);
  await page.getByLabel('Цель кредита', { exact: true }).selectOption('WORKING_CAPITAL');
  await page.getByRole('button', { name: 'Создать черновик', exact: true }).click();
  await expect(page).toHaveURL(/\/applications\/[a-f0-9-]{36}$/);
  const id = page.url().split('/').pop()!;
  bank.ids.add(id);
  await expect(page.getByRole('button', { name: 'Проверить и отправить →' })).toBeDisabled();
  await page
    .getByRole('checkbox', { name: 'Согласие на обработку персональных данных', exact: true })
    .check();
  await expect(page.getByRole('button', { name: 'Проверить и отправить →' })).toBeDisabled();
  await submit(page);
  await visibleStatus(page, 'Предложение готово');
  await expect(page.getByText(/900.*баллов/)).toBeVisible();
  await accept(page);
  const agreement = await bank.agreement(id);
  expect(createHash('sha256').update(agreement.document.content).digest('hex')).toBe(
    agreement.document.sha256,
  );
  await expect(page.getByLabel('Полный текст договора')).toHaveText(agreement.document.content);
  await expect(page.getByRole('button', { name: 'Подписать договор', exact: true })).toBeDisabled();
  await sign(page);
  const paid = await bank.wait(id, 'DISBURSED');
  expect(paid.disbursement?.amountRub).toBe(1000000);
  expect(paid.disbursement?.settlementAccountId).toBe(company(1101));
  const signed = await bank.agreement(id);
  expect(signed.signature?.documentSha256).toBe(agreement.document.sha256);
  await page.reload();
  await visibleStatus(page, 'Кредит выдан');
  await expect(page.getByRole('button', { name: 'Отменить заявку', exact: true })).toHaveCount(0);
  await info.attach('confirmed-payout', {
    body: await page.screenshot({ fullPage: true }),
    contentType: 'image/png',
  });
  await page.getByRole('link', { name: 'История и версии ↗', exact: true }).click();
  await expect(page.locator('.timeline')).toContainText('Получаем и сверяем сведения');
  const timeline = await bank.timeline(id);
  expect(timeline.items.map((e) => `${e.status}/${e.reviewStage}`)).toEqual(
    expect.arrayContaining(['IN_REVIEW/ENRICHMENT', 'IN_REVIEW/SCORING', 'DISBURSED/null']),
  );
  expect(new Set(timeline.items.map((e) => e.sequence)).size).toBe(timeline.items.length);
  await page.getByRole('link', { name: 'Снимок данных 1 →', exact: true }).first().click();
  await page.reload();
  await expect(page.getByRole('heading', { name: 'Версия данных 1', exact: true })).toBeVisible();
  await expect(page.locator('input, textarea, select')).toHaveCount(0);
});

test('draft edits, zero financial values, optional-field clearing and reload preserve saved values', async ({
  page,
  bank,
}) => {
  const id = await bank.create({
    additionalInfo: {
      businessDescription: 'Описание для очистки',
      revenueExplanation: 'Соседнее пояснение',
    },
  });
  await login(page);
  await open(page, id);
  await page.getByLabel('Месячные расходы, ₽', { exact: true }).fill('0');
  await page.getByLabel('Текущие платежи по долгам, ₽', { exact: true }).fill('0');
  await page.getByLabel('Описание бизнеса', { exact: true }).fill('');
  await page.getByLabel('Сумма, ₽', { exact: true }).fill('1250000');
  await page.getByRole('button', { name: 'Сохранить черновик', exact: true }).click();
  await expect(page.getByText('✓ Черновик сохранён', { exact: true })).toBeVisible();
  await page.reload();
  await expect(page.getByLabel('Месячные расходы, ₽', { exact: true })).toHaveValue('0');
  await expect(page.getByLabel('Описание бизнеса', { exact: true })).toHaveValue('');
  await expect(page.getByLabel('Пояснение выручки и расходов', { exact: true })).toHaveValue(
    'Соседнее пояснение',
  );
  const app = await bank.app(id);
  expect(app.financialData?.existingMonthlyDebtPaymentsRub).toBe(0);
  expect(app.amountRub).toBe(1250000);
  expect(app.dataVersion).toBe(0);
  expect((await bank.timeline(id)).items.some((e) => e.eventType === 'DRAFT_UPDATED')).toBe(true);
});

test('unsaved edits block submission and are not overwritten by refresh; Escape dismisses confirmation', async ({
  page,
  bank,
}) => {
  const id = await bank.create();
  await login(page);
  await open(page, id);
  await page.getByLabel('Сумма, ₽', { exact: true }).fill('1400000');
  await refresh(page);
  await expect(page.getByLabel('Сумма, ₽', { exact: true })).toHaveValue('1400000');
  await page
    .getByRole('checkbox', { name: 'Согласие на обработку персональных данных', exact: true })
    .check();
  await page
    .getByRole('checkbox', { name: 'Согласие на запрос кредитной истории', exact: true })
    .check();
  await expect(page.getByRole('button', { name: 'Проверить и отправить →' })).toBeDisabled();
  await page.getByRole('button', { name: 'Сохранить черновик', exact: true }).click();
  await expect(page.getByText('✓ Черновик сохранён', { exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'Проверить и отправить →' }).click();
  await expect(page.getByRole('dialog')).toContainText('1 400 000');
  await page.keyboard.press('Escape');
  await expect(page.getByRole('dialog')).toHaveCount(0);
  expect((await bank.app(id)).status).toBe('DRAFT');
});

for (const [name, overrides, field, value, source] of [
  [
    'validation',
    { purpose: 'REFINANCING' as const },
    'Рефинансируемый долг, ₽',
    '1000000',
    'VALIDATION',
  ],
  [
    'verification',
    { companyId: company(103) },
    'Описание бизнеса',
    'Производство и ремонт станков',
    'ENRICHMENT',
  ],
  [
    'decision',
    { financialData: { ...financial, monthlyExpensesRub: 0 } },
    'Пояснение выручки и расходов',
    'Расходы подтверждены бухгалтерской отчетностью',
    'SCORING',
  ],
] as const) {
  test(`${name} information request → UI response → version 2 without rewriting version 1`, async ({
    page,
    bank,
  }) => {
    const id = await bank.create(overrides, true);
    const initial = await bank.wait(id, 'NEEDS_INFO');
    const snapshot = await bank.version(id, 1);
    expect(initial.informationRequest?.source).toBe(source);
    await login(page);
    await open(page, id);
    await expect(
      page.getByRole('heading', { name: 'Запрос дополнительных сведений', exact: true }),
    ).toBeVisible();
    await expect(page.getByText(new RegExp('Источник: ' + source))).toBeVisible();
    await page.getByLabel(field, { exact: true }).fill(value);
    await page.getByRole('button', { name: 'Отправить сведения', exact: true }).click();
    await visibleStatus(page, 'Предложение готово');
    const updated = await bank.app(id);
    expect(updated.dataVersion).toBe(2);
    expect(updated.consents).toEqual(initial.consents);
    expect((await bank.version(id, 2)).origin).toBe('CUSTOMER_INFO');
    expect(await bank.version(id, 1)).toEqual(snapshot);
    await page.getByRole('link', { name: 'История и версии ↗', exact: true }).click();
    await expect(
      page.getByRole('link', { name: 'Снимок данных 2 →', exact: true }).first(),
    ).toBeVisible();
  });
}

for (const [name, overrides, reason] of [
  ['current overdue', { companyId: company(102) }, 'CURRENT_OVERDUE'],
  [
    'non-positive cash flow',
    { financialData: { ...financial, monthlyRevenueRub: 400000 } },
    'NON_POSITIVE_CASH_FLOW',
  ],
  [
    'unaffordable debt after all alternatives',
    { financialData: { ...financial, existingMonthlyDebtPaymentsRub: 500000 } },
    'DEBT_BURDEN_LIMIT',
  ],
] as const) {
  test(`business rejection: ${name}, no technical error or further commands`, async ({
    page,
    bank,
  }) => {
    const id = await bank.create(overrides, true);
    const app = await bank.wait(id, 'REJECTED');
    expect(app.decision?.reasons.map((r) => r.code)).toContain(reason);
    expect(app.technicalError).toBeNull();
    await login(page);
    await open(page, id);
    await visibleStatus(page, 'Кредит не одобрен');
    await expect(page.getByText(/Техническое ожидание/)).toHaveCount(0);
    await expect(
      page.getByRole('button', {
        name: /Принять предложение|Изменить условия|Отменить заявку|Повторить обработку/,
      }),
    ).toHaveCount(0);
    await page.reload();
    await visibleStatus(page, 'Кредит не одобрен');
  });
}

test('counteroffer shows actual differences and signs the evaluated amount and term', async ({
  page,
  bank,
}) => {
  const id = await bank.offered({ financialData: { ...financial, monthlyRevenueRub: 600000 } });
  const app = await bank.app(id);
  expect(app.decision?.offer).toMatchObject({ type: 'COUNTER', amountRub: 750000, termMonths: 36 });
  await login(page);
  await open(page, id);
  await expect(
    page.getByRole('heading', { name: 'Встречное предложение', exact: true }),
  ).toBeVisible();
  await expect(page.locator('.comparison')).toContainText('750 000');
  await accept(page);
  await sign(page);
  expect((await bank.app(id)).disbursement?.amountRub).toBe(750000);
  expect((await bank.agreement(id)).offer.termMonths).toBe(36);
});

test('change product and purpose reevaluates version 2 and makes the previous offer unusable', async ({
  page,
  bank,
}) => {
  const id = await bank.offered();
  const before = await bank.app(id),
    oldSnapshot = await bank.version(id, 1);
  await login(page);
  await open(page, id);
  await page.getByRole('button', { name: 'Изменить условия', exact: true }).click();
  await page.getByLabel('Продукт', { exact: true }).selectOption('equipment-finance:1');
  await page.getByLabel('Цель кредита', { exact: true }).selectOption('EQUIPMENT');
  await page.getByLabel('Сумма, ₽', { exact: true }).fill('900000');
  await page.getByRole('button', { name: 'Подтвердить новые условия', exact: true }).click();
  await expect(page.getByRole('dialog')).toHaveCount(0);
  await visibleStatus(page, 'Предложение готово');
  const after = await bank.wait(id, 'OFFER_READY');
  expect(after.dataVersion).toBe(2);
  expect(after.product.id).toBe('equipment-finance');
  expect(after.decision?.offer?.annualRateBps).toBe(2200);
  expect(after.decision?.offer?.id).not.toBe(before.decision?.offer?.id);
  expect(await bank.version(id, 1)).toEqual(oldSnapshot);
  const old = before.decision!.offer!;
  const rejected = await bank.request.post(`/api/v2/applications/${id}/accept-offer`, {
    headers: {
      Authorization: 'Bearer ' + (process.env.E2E_CUSTOMER_TOKEN || 'demo-customer-only'),
      'If-Match': await bank.etag(id),
      'Idempotency-Key': crypto.randomUUID(),
    },
    data: { offerId: old.id, offerVersion: old.version, dataVersion: 1 },
  });
  expect(rejected.status()).toBe(409);
  expect((await rejected.json()).code).toBe('OFFER_NOT_CURRENT');
});

for (const state of [
  'DRAFT',
  'NEEDS_INFO',
  'MANUAL_REVIEW',
  'OFFER_READY',
  'AWAITING_SIGNATURE',
] as const) {
  test(`customer cancels ${state}; terminal state and closed agreement survive reload`, async ({
    page,
    bank,
  }) => {
    const id =
      state === 'DRAFT'
        ? await bank.create()
        : state === 'NEEDS_INFO'
          ? await bank.create({ companyId: company(103) }, true)
          : state === 'MANUAL_REVIEW'
            ? await bank.manual()
            : state === 'OFFER_READY'
              ? await bank.offered()
              : await bank.awaiting();
    await bank.wait(id, state);
    await login(page);
    await open(page, id);
    await page.getByRole('button', { name: 'Отменить заявку', exact: true }).click();
    await page
      .getByLabel('Причина отмены', { exact: true })
      .fill('Финансирование больше не требуется, проверка E2E');
    await page.getByRole('button', { name: 'Подтвердить отмену', exact: true }).click();
    await visibleStatus(page, 'Заявка отменена');
    expect((await bank.app(id)).closureReason).toBe('CUSTOMER_CANCELLED');
    if (state === 'AWAITING_SIGNATURE') expect((await bank.agreement(id)).status).toBe('CLOSED');
    await page.reload();
    await visibleStatus(page, 'Заявка отменена');
    await expect(
      page.getByRole('button', { name: /Подписать договор|Отменить заявку|Принять предложение/ }),
    ).toHaveCount(0);
  });
}

test('cursor pagination visits disjoint pages and returns to the beginning', async ({
  page,
  bank,
}) => {
  for (let i = 0; i < 21; i++) await bank.create({ amountRub: 200000 + i });
  await login(page);
  const links = page.getByRole('link', { name: /^Открыть заявку / });
  await expect(links).toHaveCount(20);
  const first = await links.evaluateAll((nodes) => nodes.map((n) => n.getAttribute('href')));
  const response = page.waitForResponse(
    (r) =>
      r.request().method() === 'GET' &&
      r.url().includes('/api/v2/applications?') &&
      r.url().includes('cursor='),
  );
  await page.getByRole('button', { name: 'Следующая страница →', exact: true }).click();
  const body: ApplicationPage = await (await response).json();
  await expect(links).toHaveCount(body.items.length);
  const second = await links.evaluateAll((nodes) => nodes.map((n) => n.getAttribute('href')));
  expect(second.every((href) => !first.includes(href))).toBe(true);
  await page.getByRole('button', { name: 'К началу', exact: true }).click();
  await expect(links).toHaveCount(20);
  await expect
    .poll(() => links.evaluateAll((nodes) => nodes.map((n) => n.getAttribute('href'))))
    .toEqual(first);
});

test('product limit validation preserves the draft and permits explicit correction', async ({
  page,
  bank,
}) => {
  const id = await bank.create();
  await login(page);
  await open(page, id);
  await page.getByLabel('Сумма, ₽', { exact: true }).fill('99999');
  await page.getByRole('button', { name: 'Сохранить черновик', exact: true }).click();
  await expect(page.getByRole('alert')).toBeVisible();
  await expect(page.getByLabel('Сумма, ₽', { exact: true })).toHaveValue('99999');
  expect((await bank.app(id)).amountRub).toBe(1000000);
  await page
    .getByRole('button', { name: 'Я ознакомился с изменениями — вернуться к форме', exact: true })
    .click();
  await page.getByLabel('Сумма, ₽', { exact: true }).fill('100000');
  await page.getByRole('button', { name: 'Сохранить черновик', exact: true }).click();
  await expect(page.getByText('✓ Черновик сохранён', { exact: true })).toBeVisible();
  expect((await bank.app(id)).amountRub).toBe(100000);
});

test('forged document hash cannot sign; the exact displayed document remains signable', async ({
  page,
  bank,
}) => {
  const id = await bank.awaiting(),
    agreement = await bank.agreement(id);
  const response = await bank.request.post(`/api/v2/applications/${id}/sign`, {
    headers: {
      Authorization: 'Bearer ' + (process.env.E2E_CUSTOMER_TOKEN || 'demo-customer-only'),
      'If-Match': await bank.etag(id),
      'Idempotency-Key': crypto.randomUUID(),
    },
    data: {
      agreementId: agreement.id,
      documentVersion: agreement.document.version,
      documentSha256: '0'.repeat(64),
      consent: true,
    },
  });
  expect(response.status()).toBe(409);
  expect((await response.json()).code).toBe('DOCUMENT_MISMATCH');
  expect((await bank.agreement(id)).signature).toBeNull();
  await login(page);
  await open(page, id);
  await sign(page);
  expect((await bank.agreement(id)).signature?.documentSha256).toBe(agreement.document.sha256);
});
