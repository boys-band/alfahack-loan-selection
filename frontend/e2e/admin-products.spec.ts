import { test, expect, type Page } from '@playwright/test';

const unicodeReason = '🙂'.repeat(1001);

async function login(page: Page, role: 'admin' | 'customer') {
  await page.goto('/demo-access');
  await page
    .getByLabel(role === 'admin' ? 'Администратор продуктов' : 'Предприниматель', { exact: true })
    .check();
  await page.getByLabel('Демотокен', { exact: true }).fill('demo-' + role);
  await page.getByRole('button', { name: 'Войти →' }).click();
  await expect(page).toHaveURL(role === 'admin' ? /admin\/products$/ : /applications$/);
}

test('administrator publishes v2 and customer sees it while v1 remains readable', async ({
  page,
}) => {
  await login(page, 'admin');
  await page.getByRole('link', { name: 'Изменить условия →' }).first().click();
  await expect(page.getByText('текущая версия 1')).toBeVisible();
  await page.getByLabel('Годовая ставка, б.п.').fill('2100');
  await page.getByLabel('Причина изменения (10–2000 символов)').fill('🙂'.repeat(5));
  await page.getByRole('button', { name: 'Проверить и опубликовать новую версию' }).click();
  await expect(
    page.getByText('Обоснование должно содержать от 10 до 2000 символов.'),
  ).toBeVisible();
  await page.getByLabel('Причина изменения (10–2000 символов)').fill(unicodeReason);
  await expect(page.getByLabel('Причина изменения (10–2000 символов)')).toHaveValue(unicodeReason);
  await expect(page.getByText('2400 → 2100')).toBeVisible();
  page.on('dialog', (dialog) => dialog.accept());
  await page.getByRole('button', { name: 'Проверить и опубликовать новую версию' }).click();
  await expect(page.getByText('Версия 2 опубликована.')).toBeVisible();
  await page.reload();
  await expect(page.getByText('текущая версия 2')).toBeVisible();
  await page.getByRole('button', { name: 'Выйти из кабинета' }).click();
  await login(page, 'customer');
  await page.getByRole('link', { name: 'Кредитные продукты' }).click();
  await expect(page.getByText('21%').first()).toBeVisible();
  const old = await page.evaluate(async () => {
    const response = await fetch('/api/v2/products/working-capital?version=1', {
      headers: { Authorization: 'Bearer demo-customer' },
    });
    return response.json();
  });
  expect(old.annualRateBps).toBe(2400);
});

test('administrator creates then archives a program while version 1 stays readable', async ({
  page,
}) => {
  await login(page, 'admin');
  await page.getByRole('link', { name: 'Новая программа' }).click();
  await page.getByLabel('Идентификатор').fill('seasonal-growth');
  await page.getByLabel('Название').fill('Сезонное развитие');
  await page.getByLabel('Описание').fill('Кредит для сезонного развития');
  await page.getByLabel('Минимальная сумма, ₽').fill('100000');
  await page.getByLabel('Максимальная сумма, ₽').fill('5000000');
  await page.getByLabel('Минимальный срок, мес.').fill('6');
  await page.getByLabel('Максимальный срок, мес.').fill('36');
  await page.getByLabel('Годовая ставка, б.п.').fill('2000');
  await page.getByLabel('Минимальный возраст бизнеса, мес.').fill('6');
  await page.getByLabel('Предельная нагрузка, б.п.').fill('5000');
  await page.getByLabel('Оборотные средства').check();
  await page.getByLabel('Обоснование (10–2000 символов)').fill(unicodeReason);
  await expect(page.getByLabel('Обоснование (10–2000 символов)')).toHaveValue(unicodeReason);
  await expect(page.getByText('Итоговые условия')).toBeVisible();
  page.on('dialog', (dialog) => dialog.accept());
  await page.getByRole('button', { name: 'Создать программу' }).click();
  await expect(page).toHaveURL(/admin\/products\/seasonal-growth$/);
  await expect(page.getByText('текущая версия 1')).toBeVisible();
  await page.getByLabel('Обоснование архивирования (10–2000 символов)').fill(unicodeReason);
  await expect(page.getByLabel('Обоснование архивирования (10–2000 символов)')).toHaveValue(
    unicodeReason,
  );
  await page.getByRole('button', { name: 'Архивировать', exact: true }).click();
  await expect(page.getByText('Программа архивирована.')).toBeVisible();
  await page.reload();
  await expect(page.getByText('в архиве')).toBeVisible();
  await expect(
    page.getByRole('button', { name: 'Проверить и опубликовать новую версию' }),
  ).toHaveCount(0);
  await page.getByRole('link', { name: 'Все продукты' }).click();
  await expect(page.getByText('Сезонное развитие')).toHaveCount(0);
  await page.getByLabel('Показать программы').selectOption('archived');
  await expect(page.getByText('Сезонное развитие')).toBeVisible();
});

test('lost create and archive responses replay exact commands after reload', async ({ page }) => {
  await login(page, 'admin');
  await page.getByRole('link', { name: 'Новая программа' }).click();
  await page.getByLabel('Идентификатор').fill('repeat-program');
  await page.getByLabel('Название').fill('Программа для повтора');
  await page.getByLabel('Описание').fill('Проверка повтора команды');
  await page.getByLabel('Минимальная сумма, ₽').fill('100000');
  await page.getByLabel('Максимальная сумма, ₽').fill('5000000');
  await page.getByLabel('Минимальный срок, мес.').fill('6');
  await page.getByLabel('Максимальный срок, мес.').fill('36');
  await page.getByLabel('Годовая ставка, б.п.').fill('2000');
  await page.getByLabel('Минимальный возраст бизнеса, мес.').fill('6');
  await page.getByLabel('Предельная нагрузка, б.п.').fill('5000');
  await page.getByLabel('Оборотные средства').check();
  await page
    .getByLabel('Обоснование (10–2000 символов)')
    .fill('Новая программа для проверки повтора');
  await page.evaluate(() => {
    window.__MOCK__!.nextFault = { operation: 'createAdminProduct', lost: true };
  });
  page.on('dialog', (dialog) => dialog.accept());
  await page.getByRole('button', { name: 'Создать программу' }).click();
  await expect(page.getByText('Исход создания неизвестен')).toBeVisible();
  await page.reload();
  await expect(page.getByLabel('Идентификатор')).toHaveValue('repeat-program');
  await page.getByRole('button', { name: 'Проверить и повторить' }).click();
  await expect(page).toHaveURL(/admin\/products\/repeat-program$/);
  await page
    .getByLabel('Обоснование архивирования (10–2000 символов)')
    .fill('Программа завершена после проверки');
  await page.evaluate(() => {
    window.__MOCK__!.nextFault = { operation: 'archiveAdminProduct', lost: true };
  });
  await page.getByRole('button', { name: 'Архивировать', exact: true }).click();
  await expect(page.getByText('Исход архивирования неизвестен')).toBeVisible();
  await page.reload();
  await page.getByRole('button', { name: 'Проверить и повторить архивирование' }).click();
  await expect(page.getByText('Программа архивирована.')).toBeVisible();
});

test('reserved ID new opens its card and retry unlocks after Retry-After', async ({ page }) => {
  await login(page, 'admin');
  await page.getByRole('link', { name: 'Новая программа' }).click();
  await page.getByLabel('Идентификатор').fill('new');
  await page.getByLabel('Название').fill('Программа new');
  await page.getByLabel('Описание').fill('Проверка маршрута программы');
  await page.getByLabel('Минимальная сумма, ₽').fill('100000');
  await page.getByLabel('Максимальная сумма, ₽').fill('5000000');
  await page.getByLabel('Минимальный срок, мес.').fill('6');
  await page.getByLabel('Максимальный срок, мес.').fill('36');
  await page.getByLabel('Годовая ставка, б.п.').fill('2000');
  await page.getByLabel('Минимальный возраст бизнеса, мес.').fill('6');
  await page.getByLabel('Предельная нагрузка, б.п.').fill('5000');
  await page.getByLabel('Оборотные средства').check();
  await page.getByLabel('Обоснование (10–2000 символов)').fill('Проверка повторной отправки');
  await page.evaluate(() => {
    window.__MOCK__!.nextFault = { operation: 'createAdminProduct', status: 503, retryAfter: 1 };
  });
  page.on('dialog', (dialog) => dialog.accept());
  await page.getByRole('button', { name: 'Создать программу' }).click();
  const retry = page.getByRole('button', { name: 'Проверить и повторить' });
  await expect(retry).toBeDisabled();
  await expect(retry).toBeEnabled({ timeout: 3000 });
  await retry.click();
  await expect(page).toHaveURL(/admin\/products\/id\/new$/);
  await expect(page.getByRole('heading', { name: 'Условия продукта' })).toBeVisible();
  await page
    .getByLabel('Обоснование архивирования (10–2000 символов)')
    .fill('Архив после временного сбоя');
  await page.evaluate(() => {
    window.__MOCK__!.nextFault = { operation: 'archiveAdminProduct', status: 503, retryAfter: 1 };
  });
  await page.getByRole('button', { name: 'Архивировать', exact: true }).click();
  const archiveRetry = page.getByRole('button', { name: 'Проверить и повторить архивирование' });
  await expect(archiveRetry).toBeDisabled();
  await expect(archiveRetry).toBeEnabled({ timeout: 3000 });
  await archiveRetry.click();
  await expect(page.getByText('Программа архивирована.')).toBeVisible();
});

test('lost response keeps exact publication command after reload and replay does not create v3', async ({
  page,
}) => {
  await login(page, 'admin');
  await page.getByRole('link', { name: 'Изменить условия →' }).first().click();
  await page.evaluate(() => {
    window.__MOCK__!.nextFault = { operation: 'publishProductVersion', lost: true };
  });
  await page.getByLabel('Годовая ставка, б.п.').fill('2100');
  await page
    .getByLabel('Причина изменения (10–2000 символов)')
    .fill('Повтор после потерянного ответа');
  page.on('dialog', (dialog) => dialog.accept());
  await page.getByRole('button', { name: 'Проверить и опубликовать новую версию' }).click();
  await expect(page.getByText('Исход публикации неизвестен')).toBeVisible();
  await page.reload();
  await page.getByRole('button', { name: 'Проверить и повторить' }).click();
  await expect(page.getByText('Версия 2 опубликована.')).toBeVisible();
  await expect(page.getByText('текущая версия 2')).toBeVisible();
  const versions = await page.locator('section.panel li').allTextContents();
  expect(versions).toHaveLength(2);
});

test('412 preserves editor state and requires a new confirmation', async ({ page }) => {
  await login(page, 'admin');
  await page.getByRole('link', { name: 'Изменить условия →' }).first().click();
  await page.evaluate(() => {
    window.__MOCK__!.nextFault = { operation: 'publishProductVersion', status: 412 };
  });
  await page.getByLabel('Годовая ставка, б.п.').fill('2100');
  await page
    .getByLabel('Причина изменения (10–2000 символов)')
    .fill('Сохранить данные при конфликте');
  page.on('dialog', (dialog) => dialog.accept());
  await page.getByRole('button', { name: 'Проверить и опубликовать новую версию' }).click();
  await expect(page.getByText('Продукт изменился. Ознакомьтесь')).toBeVisible();
  await expect(page.getByLabel('Годовая ставка, б.п.')).toHaveValue('2100');
  await page.getByRole('button', { name: 'Ознакомился, редактировать заново' }).click();
  await page.getByRole('button', { name: 'Проверить и опубликовать новую версию' }).click();
  await expect(page.getByText('Версия 2 опубликована.')).toBeVisible();
});

test('unknown publication reload retains original edits after another admin command wins and replay gets 412', async ({
  page,
}) => {
  await login(page, 'admin');
  await page.getByRole('link', { name: 'Изменить условия →' }).first().click();
  await page.getByLabel('Годовая ставка, б.п.').fill('2100');
  await page
    .getByLabel('Причина изменения (10–2000 символов)')
    .fill('Исходный проект ставки для проверки конфликта');
  await page.evaluate(() => {
    window.__MOCK__!.nextFault = { operation: 'publishProductVersion', status: 503 };
  });
  page.on('dialog', (dialog) => dialog.accept());
  const unavailable = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      response.url().endsWith('/v2/admin/products/working-capital/versions') &&
      response.status() === 503,
  );
  await page.getByRole('button', { name: 'Проверить и опубликовать новую версию' }).click();
  await unavailable;
  await page.reload();
  await expect(page.getByLabel('Годовая ставка, б.п.')).toHaveValue('2100');
  await expect(page.getByLabel('Причина изменения (10–2000 символов)')).toHaveValue(
    'Исходный проект ставки для проверки конфликта',
  );

  const otherStatus = await page.evaluate(async () => {
    const path = '/api/v2/admin/products/working-capital';
    const headers = { Authorization: 'Bearer demo-admin' };
    const current = await fetch(path, { headers });
    const detail = await current.json();
    const { id: _id, version: _version, ...terms } = detail.product;
    const published = await fetch(path + '/versions', {
      method: 'POST',
      headers: {
        ...headers,
        'Content-Type': 'application/json',
        'If-Match': current.headers.get('ETag')!,
        'Idempotency-Key': crypto.randomUUID(),
      },
      body: JSON.stringify({
        ...terms,
        annualRateBps: 2200,
        reason: 'Вторая административная команда',
      }),
    });
    return published.status;
  });
  expect(otherStatus).toBe(201);

  await page.reload();
  await expect(page.getByText('текущая версия 2')).toBeVisible();
  await expect(page.getByLabel('Годовая ставка, б.п.')).toHaveValue('2100');
  await expect(page.getByText('2200 → 2100')).toBeVisible();
  await page.getByRole('button', { name: 'Проверить и повторить' }).click();
  await expect(page.getByText('Продукт изменился. Ознакомьтесь')).toBeVisible();
  await expect(page.getByLabel('Годовая ставка, б.п.')).toHaveValue('2100');
  await expect(page.getByLabel('Причина изменения (10–2000 символов)')).toHaveValue(
    'Исходный проект ставки для проверки конфликта',
  );
  await expect(page.getByText('2200 → 2100')).toBeVisible();
});

test('catalog validation identifies the program field and correction creates exactly one program', async ({
  page,
}) => {
  await login(page, 'admin');
  await page.getByRole('link', { name: 'Новая программа', exact: true }).click();
  for (const [label, value] of [
    ['Идентификатор', 'rejected-program'],
    ['Название', 'Проверка понятной ошибки'],
    ['Описание', 'Создание после исправления ошибки каталога'],
    ['Минимальная сумма, ₽', '100000'],
    ['Максимальная сумма, ₽', '5000000'],
    ['Минимальный срок, мес.', '6'],
    ['Максимальный срок, мес.', '36'],
    ['Годовая ставка, б.п.', '2000'],
    ['Минимальный возраст бизнеса, мес.', '6'],
    ['Предельная нагрузка, б.п.', '5000'],
    ['Обоснование (10–2000 символов)', 'Новая программа для проверки сообщения'],
  ])
    await page.getByLabel(label, { exact: true }).fill(value);
  await page.getByLabel('Оборотные средства', { exact: true }).check();
  // Return the actual catalog Problem through HTTP once, then let the corrected request succeed.
  await page.evaluate(async () => {
    const modulePath = '/src/mocks/admin-catalog.ts';
    const { MockAdminCatalog } = await import(modulePath);
    const handle = MockAdminCatalog.prototype.handle;
    MockAdminCatalog.prototype.handle = async function (
      this: unknown,
      method: string,
      url: string,
      body: string | undefined,
      headers: Headers,
    ) {
      if (method === 'POST' && url.endsWith('/v2/admin/products')) {
        MockAdminCatalog.prototype.handle = handle;
        const requestId = crypto.randomUUID();
        return {
          status: 422,
          headers: { 'Content-Type': 'application/problem+json', 'X-Request-Id': requestId },
          body: {
            type: 'urn:alfa:problem:validation_error',
            title: 'VALIDATION_ERROR',
            status: 422,
            detail: 'Request validation failed',
            code: 'VALIDATION_ERROR',
            requestId,
            errors: [{ path: '/id', message: 'Invalid product ID' }],
          },
        };
      }
      return handle.call(this, method, url, body, headers);
    };
  });
  page.on('dialog', (dialog) => dialog.accept());
  const firstRequest = page.waitForRequest(
    (r) => r.method() === 'POST' && r.url().endsWith('/v2/admin/products'),
  );
  await page.getByRole('button', { name: 'Создать программу', exact: true }).click();
  const rejected = await firstRequest;
  const alert = page.getByRole('alert');
  await expect(alert).toContainText('Идентификатор программы — От 2 до 64 символов');
  await expect(alert).toContainText('первый символ — буква');
  await expect(alert).not.toContainText(
    /Поле формы|Проверьте значение поля|Invalid|Request validation|\/id|VALIDATION_ERROR/,
  );
  await expect(page.getByLabel('Название', { exact: true })).toHaveValue(
    'Проверка понятной ошибки',
  );
  await page.screenshot({ path: 'test-results/admin-create-field-error.png', fullPage: true });
  await page
    .getByRole('button', { name: 'Ознакомился, редактировать заново', exact: true })
    .click();
  await page.getByLabel('Идентификатор', { exact: true }).fill('corrected-program');
  const nextRequest = page.waitForRequest(
    (r) => r.method() === 'POST' && r.url().endsWith('/v2/admin/products'),
  );
  await page.getByRole('button', { name: 'Создать программу', exact: true }).click();
  const corrected = await nextRequest;
  expect(corrected.headers()['idempotency-key']).not.toBe(rejected.headers()['idempotency-key']);
  await expect(page).toHaveURL(/\/admin\/products\/corrected-program$/);
  await expect(page.getByText('текущая версия 1')).toBeVisible();
  const responses = await page.evaluate(async () => {
    const headers = { Authorization: 'Bearer demo-admin' };
    return Promise.all(
      ['rejected-program', 'corrected-program'].map(async (id) => {
        const response = await fetch('/api/v2/admin/products/' + id, { headers });
        return response.status;
      }),
    );
  });
  expect(responses).toEqual([404, 200]);
});
