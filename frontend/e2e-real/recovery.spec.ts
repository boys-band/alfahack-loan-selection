import { randomUUID } from 'node:crypto';
import {
  test,
  expect,
  login,
  open,
  submit,
  accept,
  sign,
  refresh,
  visibleStatus,
  loseReply,
  wire,
  customerToken,
  company,
  financial,
  type Wire,
} from './fixtures';
import type { Application } from '../src/api/generated';

test('lost create reply survives reload and exact replay returns the single committed application', async ({
  page,
  bank,
}) => {
  await login(page);
  await page.getByRole('link', { name: '＋ Новая заявка', exact: true }).click();
  await page.getByLabel('Ваша компания', { exact: true }).selectOption(company(101));
  await page.getByLabel('Продукт', { exact: true }).selectOption('working-capital');
  const lost = await loseReply(page, '/applications');
  await page.getByRole('button', { name: 'Создать черновик', exact: true }).click();
  await expect(page.getByText('Исход команды пока неизвестен', { exact: true })).toBeVisible();
  await expect
    .poll(() => lost.committed(), { message: 'The real create request committed' })
    .toBeTruthy();
  const committed = lost.committed() as Application;
  bank.ids.add(committed.id);
  await page.reload();
  await page
    .getByRole('button', { name: 'Проверить и повторить исходную команду', exact: true })
    .click();
  await expect(page).toHaveURL(new RegExp('/applications/' + committed.id + '$'));
  expect(lost.attempts).toHaveLength(2);
  expect(lost.attempts[1]).toEqual(lost.attempts[0]);
  const timeline = await bank.timeline(committed.id);
  expect(timeline.items.filter((e) => e.eventType === 'DRAFT_CREATED')).toHaveLength(1);
});

test('lost saved draft reply replays the original body, key and ETag after reload', async ({
  page,
  bank,
}) => {
  const id = await bank.create();
  await login(page);
  await open(page, id);
  const lost = await loseReply(page, '/applications/' + id, 'PATCH');
  await page.getByLabel('Сумма, ₽', { exact: true }).fill('1350000');
  await page.getByRole('button', { name: 'Сохранить черновик', exact: true }).click();
  await expect(page.getByText('Исход команды пока неизвестен', { exact: true })).toBeVisible();
  await page.reload();
  await page
    .getByRole('button', { name: 'Проверить и повторить исходную команду', exact: true })
    .click();
  await expect(page.getByText('Исход команды пока неизвестен', { exact: true })).toHaveCount(0);
  expect(lost.attempts).toHaveLength(2);
  expect(lost.attempts[1]).toEqual(lost.attempts[0]);
  expect((await bank.app(id)).amountRub).toBe(1350000);
  expect(
    (await bank.timeline(id)).items.filter((e) => e.eventType === 'DRAFT_UPDATED'),
  ).toHaveLength(1);
});

for (const operation of ['submit', 'accept-offer', 'sign'] as const) {
  test(`lost ${operation} reply: refreshed final state never rolls back to the replayed 202`, async ({
    page,
    bank,
  }) => {
    const id =
      operation === 'submit'
        ? await bank.create()
        : operation === 'accept-offer'
          ? await bank.offered()
          : await bank.awaiting();
    await login(page);
    await open(page, id);
    const lost = await loseReply(page, `/applications/${id}/${operation}`);
    if (operation === 'submit') await submit(page);
    if (operation === 'accept-offer') {
      await page.getByRole('button', { name: 'Принять предложение', exact: true }).click();
      await page.getByRole('button', { name: 'Подтвердить принятие', exact: true }).click();
    }
    if (operation === 'sign') {
      await page.getByRole('checkbox', { name: /Я прочитал полный документ/ }).check();
      await page.getByRole('button', { name: 'Подписать договор', exact: true }).click();
    }
    await expect(page.getByText('Исход команды пока неизвестен', { exact: true })).toBeVisible();
    const expected =
      operation === 'submit'
        ? 'OFFER_READY'
        : operation === 'accept-offer'
          ? 'AWAITING_SIGNATURE'
          : 'DISBURSED';
    await bank.wait(id, expected);
    await page.reload();
    await page
      .getByRole('button', { name: 'Проверить и повторить исходную команду', exact: true })
      .click();
    await expect(page.getByText('Исход команды пока неизвестен', { exact: true })).toHaveCount(0);
    expect(lost.attempts).toHaveLength(2);
    expect(lost.attempts[1]).toEqual(lost.attempts[0]);
    await visibleStatus(
      page,
      operation === 'submit'
        ? 'Предложение готово'
        : operation === 'accept-offer'
          ? 'Договор готов к подписи'
          : 'Кредит выдан',
    );
    if (operation === 'sign') {
      const paid = await bank.app(id),
        ledgerId = paid.disbursement!.id;
      await refresh(page);
      expect((await bank.app(id)).disbursement!.id).toBe(ledgerId);
    }
  });
}

test('real competing edit returns 412, preserves unsaved fields and requires a new confirmed intent', async ({
  page,
  bank,
}) => {
  const id = await bank.create();
  await login(page);
  await open(page, id);
  const requests: Wire[] = [];
  page.on('request', (r) => {
    if (r.method() === 'PATCH' && r.url().endsWith('/applications/' + id)) requests.push(wire(r));
  });
  await page.getByLabel('Сумма, ₽', { exact: true }).fill('1450000');
  await bank.mutate(id, '', { amountRub: 1100000 });
  await page.getByRole('button', { name: 'Сохранить черновик', exact: true }).click();
  await expect(page.getByText('Заявка изменилась', { exact: true })).toBeVisible();
  await expect(page.getByLabel('Сумма, ₽', { exact: true })).toHaveValue('1450000');
  await expect(
    page.getByRole('button', { name: 'Сохранить черновик', exact: true }),
  ).toBeDisabled();
  expect((await bank.app(id)).amountRub).toBe(1100000);
  await page
    .getByRole('button', { name: 'Я ознакомился с изменениями — вернуться к форме', exact: true })
    .click();
  await page.getByRole('button', { name: 'Сохранить черновик', exact: true }).click();
  await expect(page.getByText('✓ Черновик сохранён', { exact: true })).toBeVisible();
  expect(requests).toHaveLength(2);
  expect(requests[1].key).not.toBe(requests[0].key);
  expect(requests[1].etag).not.toBe(requests[0].etag);
  expect(requests[1].body).toBe(requests[0].body);
  expect((await bank.app(id)).amountRub).toBe(1450000);
});

for (const [status, code] of [
  [429, 'RATE_LIMITED'],
  [409, 'IDEMPOTENCY_IN_PROGRESS'],
] as const) {
  test(`${status} ${code}: Retry-After gates exact retry and keeps the original key`, async ({
    page,
    bank,
  }) => {
    const id = await bank.create();
    await login(page);
    await open(page, id);
    const attempts: Wire[] = [];
    await page.route(`**/api/v2/applications/${id}`, async (route) => {
      if (route.request().method() !== 'PATCH') return route.continue();
      attempts.push(wire(route.request()));
      if (attempts.length === 1)
        return route.fulfill({
          status,
          contentType: 'application/problem+json',
          headers: { 'Retry-After': '2' },
          body: JSON.stringify({
            type: 'about:blank',
            title: code,
            status,
            detail: 'Повторите команду после задержки',
            code,
            requestId: randomUUID(),
            errors: [],
          }),
        });
      await route.continue();
    });
    await page.getByLabel('Сумма, ₽', { exact: true }).fill('1150000');
    await page.getByRole('button', { name: 'Сохранить черновик', exact: true }).click();
    await expect(page.getByRole('button', { name: /^Повтор через/ })).toBeDisabled();
    const retry = page.getByRole('button', {
      name: 'Проверить и повторить исходную команду',
      exact: true,
    });
    await retry.click();
    await expect(page.getByText('Исход команды пока неизвестен', { exact: true })).toHaveCount(0);
    expect(attempts).toHaveLength(2);
    expect(attempts[1]).toEqual(attempts[0]);
    expect((await bank.app(id)).amountRub).toBe(1150000);
  });
}

test('failed GET retains the last card, disables dangerous commands and recovers explicitly', async ({
  page,
  bank,
}) => {
  const id = await bank.offered();
  await login(page);
  await open(page, id);
  await page.route(`**/api/v2/applications/${id}`, async (route) => {
    if (route.request().method() === 'GET')
      await route.fulfill({
        status: 503,
        contentType: 'application/problem+json',
        body: JSON.stringify({
          type: 'about:blank',
          title: 'DEPENDENCY_UNAVAILABLE',
          status: 503,
          detail: 'Временная недоступность чтения',
          code: 'DEPENDENCY_UNAVAILABLE',
          requestId: randomUUID(),
          errors: [],
        }),
      });
    else await route.continue();
  });
  await refresh(page);
  await expect(page.getByText(/Показаны последние полученные данные/)).toBeVisible();
  await visibleStatus(page, 'Предложение готово');
  await expect(
    page.getByRole('button', { name: 'Принять предложение', exact: true }),
  ).toBeDisabled();
  await expect(page.getByText(/Техническое ожидание/)).toHaveCount(0);
  await page.unroute(`**/api/v2/applications/${id}`);
  await page.getByRole('button', { name: 'Повторить запрос', exact: true }).click();
  await expect(
    page.getByRole('button', { name: 'Принять предложение', exact: true }),
  ).toBeEnabled();
});

test('double click sends one acceptance command and one server acceptance event', async ({
  page,
  bank,
}) => {
  const id = await bank.offered();
  await login(page);
  await open(page, id);
  const attempts: Wire[] = [];
  await page.route(`**/api/v2/applications/${id}/accept-offer`, async (route) => {
    attempts.push(wire(route.request()));
    const result = await route.fetch();
    await route.fulfill({ response: result });
  });
  await page.getByRole('button', { name: 'Принять предложение', exact: true }).click();
  await page.getByRole('button', { name: 'Подтвердить принятие', exact: true }).dblclick();
  await bank.wait(id, 'AWAITING_SIGNATURE');
  expect(attempts).toHaveLength(1);
  expect(
    (await bank.timeline(id)).items.filter((e) => e.reason === 'Предложение принято'),
  ).toHaveLength(1);
});

test('sign/cancel concurrency has one authoritative outcome and a consistent immutable agreement', async ({
  page,
  bank,
}) => {
  const id = await bank.awaiting(),
    agreement = await bank.agreement(id),
    etag = await bank.etag(id);
  const headers = { Authorization: `Bearer ${customerToken}`, 'If-Match': etag };
  const responses = await Promise.all([
    bank.request.post(`/api/v2/applications/${id}/sign`, {
      headers: { ...headers, 'Idempotency-Key': randomUUID() },
      data: {
        agreementId: agreement.id,
        documentVersion: agreement.document.version,
        documentSha256: agreement.document.sha256,
        consent: true,
      },
    }),
    bank.request.post(`/api/v2/applications/${id}/cancel`, {
      headers: { ...headers, 'Idempotency-Key': randomUUID() },
      data: { reason: 'Проверка конкурирующей отмены' },
    }),
  ]);
  expect(responses.some((r) => r.ok())).toBe(true);
  for (const r of responses) expect([200, 202, 409, 412]).toContain(r.status());
  let app: Application | undefined;
  await expect
    .poll(
      async () => {
        app = await bank.app(id);
        return app.status;
      },
      { timeout: 25000 },
    )
    .toMatch(/^(CANCELLED|DISBURSED)$/);
  const actual = await bank.agreement(id);
  if (app!.status === 'CANCELLED') {
    expect(actual.status).toBe('CLOSED');
    expect(actual.signature).toBeNull();
    expect(app!.disbursement).toBeNull();
  } else {
    expect(actual.status).toBe('SIGNED');
    expect(app!.disbursement?.agreementId).toBe(actual.id);
  }
  await login(page);
  await open(page, id);
  await visibleStatus(page, app!.status === 'CANCELLED' ? 'Заявка отменена' : 'Кредит выдан');
});
