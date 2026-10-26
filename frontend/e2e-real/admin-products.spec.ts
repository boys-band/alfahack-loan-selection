import { test, expect, login, staffToken } from './fixtures';
import type { AdminProduct, Product } from '../src/api/generated';
import { randomUUID } from 'node:crypto';

const productId = 'equipment-finance';
const adminToken = process.env.E2E_ADMIN_TOKEN || 'demo-product-admin-only';
test.skip(
  process.env.E2E_ADMIN_ISOLATED !== '1',
  'Admin publication changes the persistent product pointer; use its dedicated Compose project.',
);

test('admin publication preserves old signed agreement and draft, offers new v2, and rejects stale editor', async ({
  page,
  bank,
  browser,
  request,
}) => {
  const adminHeaders = { Authorization: `Bearer ${adminToken}` };
  const beforeResponse = await request.get(`/api/v2/admin/products/${productId}`, {
    headers: adminHeaders,
  });
  expect(beforeResponse.status()).toBe(200);
  const before = (await beforeResponse.json()) as AdminProduct;
  const previous = before.product;
  const draftId = await bank.create({
    productId,
    productVersion: previous.version,
    purpose: 'EQUIPMENT',
  });
  const signedId = await bank.offered({
    productId,
    productVersion: previous.version,
    purpose: 'EQUIPMENT',
  });
  const offeredBefore = await bank.app(signedId);
  expect(offeredBefore.product).toEqual(previous);
  const oldOffer = offeredBefore.decision!.offer!;
  await bank.mutate(signedId, 'accept-offer', {
    offerId: oldOffer.id,
    offerVersion: oldOffer.version,
    dataVersion: offeredBefore.dataVersion,
  });
  await bank.wait(signedId, 'AWAITING_SIGNATURE');
  const agreementBefore = await bank.agreement(signedId);
  await bank.mutate(signedId, 'sign', {
    agreementId: agreementBefore.id,
    documentVersion: agreementBefore.document.version,
    documentSha256: agreementBefore.document.sha256,
    consent: true,
  });
  await bank.wait(signedId, 'DISBURSED');
  const signedBefore = await bank.agreement(signedId);
  expect(signedBefore.signature?.documentSha256).toBe(agreementBefore.document.sha256);

  async function adminLogin(adminPage: typeof page) {
    await adminPage.goto('/demo-access');
    await adminPage.getByLabel('Администратор продуктов', { exact: true }).check();
    await adminPage.getByLabel('Демотокен', { exact: true }).fill(adminToken);
    await adminPage.getByRole('button', { name: 'Войти →', exact: true }).click();
    await expect(adminPage).toHaveURL(/\/admin\/products$/);
    await adminPage.goto(`/admin/products/${productId}`);
    await expect(adminPage.getByText(`текущая версия ${previous.version}`)).toBeVisible();
  }

  await adminLogin(page);
  const secondContext = await browser.newContext();
  const second = await secondContext.newPage();
  second.on('dialog', (dialog) => dialog.accept());
  try {
    await adminLogin(second);
    const nextRate = previous.annualRateBps === 2300 ? 2200 : 2300;
    const staleRate = previous.annualRateBps === 2400 ? 2500 : 2400;
    await second.getByLabel('Годовая ставка, б.п.').fill(String(staleRate));
    await second
      .getByLabel('Причина изменения (10–2000 символов)')
      .fill('Проверка конкурентной административной публикации');

    await page.getByLabel('Годовая ставка, б.п.').fill(String(nextRate));
    await page
      .getByLabel('Причина изменения (10–2000 символов)')
      .fill('Проверка новой версии условий в полном стеке');
    await page.getByRole('button', { name: 'Проверить и опубликовать новую версию' }).click();
    await expect(
      page.getByText(`Версия ${Number(previous.version) + 1} опубликована.`),
    ).toBeVisible();

    const current = await request.get(`/api/v2/products/${productId}`, {
      headers: { Authorization: 'Bearer demo-customer-only' },
    });
    expect(current.status()).toBe(200);
    expect(((await current.json()) as Product).annualRateBps).toBe(nextRate);
    const old = await request.get(`/api/v2/products/${productId}?version=${previous.version}`, {
      headers: { Authorization: 'Bearer demo-customer-only' },
    });
    expect(old.status()).toBe(200);
    expect(await old.json()).toEqual(previous);
    expect((await bank.app(draftId)).product).toEqual(previous);
    await bank.mutate(draftId, 'submit', {
      personalDataConsent: true,
      creditBureauConsent: true,
    });
    const existingApplication = await bank.wait(draftId, 'OFFER_READY');
    expect(existingApplication.dataVersion).toBe(1);
    expect(existingApplication.product).toEqual(previous);
    const agreementAfter = await bank.agreement(signedId);
    expect(agreementAfter.offer).toEqual(signedBefore.offer);
    expect(agreementAfter.document.sha256).toBe(signedBefore.document.sha256);
    expect(agreementAfter.document.content).toBe(signedBefore.document.content);
    expect(agreementAfter.signature).toEqual(signedBefore.signature);
    expect((await bank.app(signedId)).product).toEqual(previous);

    const newId = await bank.create({
      productId,
      productVersion: String(Number(previous.version) + 1),
      purpose: 'EQUIPMENT',
    });
    const newDraft = await bank.app(newId);
    expect(newDraft.product.version).toBe(String(Number(previous.version) + 1));
    expect(newDraft.product.annualRateBps).toBe(nextRate);
    await bank.mutate(newId, 'submit', {
      personalDataConsent: true,
      creditBureauConsent: true,
    });
    const newOffer = await bank.wait(newId, 'OFFER_READY');
    expect(newOffer.product.annualRateBps).toBe(nextRate);
    expect(newOffer.decision?.offer?.productVersion).toBe(newDraft.product.version);

    await second.getByRole('button', { name: 'Проверить и опубликовать новую версию' }).click();
    await expect(second.getByText('Продукт изменился. Ознакомьтесь')).toBeVisible();
    await expect(second.getByLabel('Годовая ставка, б.п.')).toHaveValue(String(staleRate));
  } finally {
    await secondContext.close();
  }
});

test('archive blocks a draft while preserving signed agreement and disbursement', async ({
  page,
  bank,
  request,
}) => {
  test.setTimeout(120000);
  const id = 'e2e-program-' + randomUUID().slice(0, 8);
  const adminHeaders = { Authorization: `Bearer ${adminToken}` };
  await page.goto('/demo-access');
  await page.getByLabel('Администратор продуктов', { exact: true }).check();
  await page.getByLabel('Демотокен', { exact: true }).fill(adminToken);
  await page.getByRole('button', { name: 'Войти →', exact: true }).click();
  await page.getByRole('link', { name: 'Новая программа' }).click();
  await page.getByLabel('Идентификатор').fill(id);
  await page.getByLabel('Название').fill('Новая тестовая программа');
  await page.getByLabel('Описание').fill('Проверка полного жизненного цикла');
  await page.getByLabel('Минимальная сумма, ₽').fill('100000');
  await page.getByLabel('Максимальная сумма, ₽').fill('10000000');
  await page.getByLabel('Минимальный срок, мес.').fill('12');
  await page.getByLabel('Максимальный срок, мес.').fill('60');
  await page.getByLabel('Годовая ставка, б.п.').fill('2200');
  await page.getByLabel('Минимальный возраст бизнеса, мес.').fill('12');
  await page.getByLabel('Предельная нагрузка, б.п.').fill('5000');
  await page.getByLabel('Оборудование').check();
  await page.getByLabel('Обоснование (10–2000 символов)').fill('Сквозная проверка новой программы');
  await page.getByRole('button', { name: 'Создать программу' }).click();
  await expect(page).toHaveURL(new RegExp(`/admin/products/${id}$`));
  const createdResponse = await request.get(`/api/v2/products/${id}`, {
    headers: { Authorization: 'Bearer demo-customer-only' },
  });
  expect(createdResponse.status()).toBe(200);
  const created = (await createdResponse.json()) as Product;
  expect(created.version).toBe('1');
  const draftId = await bank.create({ productId: id, productVersion: '1', purpose: 'EQUIPMENT' });
  const signedId = await bank.offered({ productId: id, productVersion: '1', purpose: 'EQUIPMENT' });
  const offered = await bank.app(signedId);
  await bank.mutate(signedId, 'accept-offer', {
    offerId: offered.decision!.offer!.id,
    offerVersion: offered.decision!.offer!.version,
    dataVersion: offered.dataVersion,
  });
  await bank.wait(signedId, 'AWAITING_SIGNATURE');
  const beforeAgreement = await bank.agreement(signedId);
  await bank.mutate(signedId, 'sign', {
    agreementId: beforeAgreement.id,
    documentVersion: beforeAgreement.document.version,
    documentSha256: beforeAgreement.document.sha256,
    consent: true,
  });
  await page
    .getByLabel('Обоснование архивирования (10–2000 символов)')
    .fill('Завершение продаж тестовой программы');
  await page.getByRole('button', { name: 'Архивировать', exact: true }).click();
  await expect(page.getByText('Программа архивирована.')).toBeVisible();
  expect(
    (
      await request.get(`/api/v2/products/${id}`, {
        headers: { Authorization: 'Bearer demo-customer-only' },
      })
    ).status(),
  ).toBe(404);
  expect(
    (
      await request.get(`/api/v2/products/${id}?version=1`, {
        headers: { Authorization: 'Bearer demo-customer-only' },
      })
    ).status(),
  ).toBe(200);
  await bank.wait(signedId, 'DISBURSED');
  const afterAgreement = await bank.agreement(signedId);
  expect(afterAgreement.document.sha256).toBe(beforeAgreement.document.sha256);
  expect(afterAgreement.document.content).toBe(beforeAgreement.document.content);
  expect(afterAgreement.signature?.documentSha256).toBe(beforeAgreement.document.sha256);
  expect((await bank.app(signedId)).product).toEqual(created);
  const draftResponse = await request.get(`/api/v2/applications/${draftId}`, {
    headers: { Authorization: 'Bearer demo-customer-only' },
  });
  const staleSubmit = await request.post(`/api/v2/applications/${draftId}/submit`, {
    headers: {
      Authorization: 'Bearer demo-customer-only',
      'If-Match': draftResponse.headers()['etag'],
      'Idempotency-Key': randomUUID(),
    },
    data: { personalDataConsent: true, creditBureauConsent: true },
  });
  expect(staleSubmit.status()).toBe(409);
  expect((await staleSubmit.json()).code).toBe('PRODUCT_NOT_CURRENT');
  expect((await bank.app(draftId)).status).toBe('DRAFT');

  // Cross-feature acceptance: archived products remain visible to staff through stored snapshots.
  const registry = await bank.get<{ items: Array<{ id: string; product: Product }> }>(
    '/staff/applications?limit=100',
    'staff',
  );
  expect(registry.items.find((app) => app.id === signedId)?.product).toEqual(created);
  expect(registry.items.find((app) => app.id === draftId)?.product).toEqual(created);
  const staffAgreement = await request.get(`/api/v2/applications/${signedId}/agreement`, {
    headers: { Authorization: `Bearer ${staffToken}` },
  });
  expect(staffAgreement.status()).toBe(200);
  expect((await staffAgreement.json()).document).toEqual(afterAgreement.document);
  await page.getByRole('button', { name: 'Выйти из кабинета ↗' }).click();
  await login(page, 'staff');
  await page.goto('/staff/applications');
  const registryRow = page.getByRole('row').filter({ hasText: signedId });
  await expect(registryRow).toContainText(created.name);
  await registryRow.getByRole('link', { name: /Открыть заявку/ }).click();
  await expect(page.getByText(created.name + ' · версия 1', { exact: false })).toBeVisible();
  await page.getByRole('link', { name: 'Документ договора ↗' }).click();
  await expect(page.getByLabel('Полный текст договора', { exact: true })).toHaveText(
    afterAgreement.document.content,
  );

  const rejected = await request.post('/api/v2/applications', {
    headers: {
      Authorization: 'Bearer demo-customer-only',
      'Idempotency-Key': randomUUID(),
    },
    data: {
      companyId: '00000000-0000-4000-8000-000000000101',
      productId: id,
      productVersion: '1',
      amountRub: 1000000,
      termMonths: 12,
      purpose: 'EQUIPMENT',
      financialData: {
        monthlyRevenueRub: 1000000,
        monthlyExpensesRub: 500000,
        existingMonthlyDebtPaymentsRub: 20000,
      },
      additionalInfo: {},
    },
  });
  expect(rejected.status()).toBe(409);
  expect((await rejected.json()).code).toBe('PRODUCT_NOT_CURRENT');
  const activeId = await bank.offered();
  const change = await request.post(`/api/v2/applications/${activeId}/change-terms`, {
    headers: {
      Authorization: 'Bearer demo-customer-only',
      'Idempotency-Key': randomUUID(),
      'If-Match': await bank.etag(activeId),
    },
    data: { productId: id, productVersion: '1', purpose: 'EQUIPMENT' },
  });
  expect(change.status()).toBe(409);
  expect((await change.json()).code).toBe('PRODUCT_NOT_CURRENT');
  const adminCard = await request.get(`/api/v2/admin/products/${id}`, { headers: adminHeaders });
  expect(((await adminCard.json()) as AdminProduct).active).toBe(false);
});
