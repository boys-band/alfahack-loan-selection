import { beforeEach, expect, it, vi } from 'vitest';
import { Api, ApiError } from '../src/api/client';
import { AdminCommands } from '../src/core/admin-commands';
import { MockBank } from '../src/mocks/bank';
import { MockAdminCatalog } from '../src/mocks/admin-catalog';
import type { CreateAdminProductRequest, PublishProductVersionRequest } from '../src/api/generated';

const productId = 'working-capital';
const body: PublishProductVersionRequest = {
  name: 'На развитие бизнеса',
  description: 'Синтетический кредит для демонстрации конвейера',
  purposeCodes: ['WORKING_CAPITAL', 'EQUIPMENT', 'REFINANCING'],
  minAmountRub: 100000,
  maxAmountRub: 5000000,
  minTermMonths: 6,
  maxTermMonths: 36,
  annualRateBps: 2100,
  minBusinessAgeMonths: 6,
  maxDebtServiceRatioBps: 5000,
  reason: 'Демонстрационное снижение ставки',
};

beforeEach(() => sessionStorage.clear());

it('keeps the exact admin command after a lost response and isolates application commands', async () => {
  const api = new Api('/api', 'demo-admin');
  const raw = vi.spyOn(api, 'raw').mockRejectedValue(new ApiError(0, null));
  const journal = new AdminCommands(sessionStorage, 'admin-identity');
  const command = journal.prepare(productId, body, '"original-etag"');
  await expect(journal.send(api, command)).rejects.toThrow();
  const restored = new AdminCommands(sessionStorage, 'admin-identity');
  await expect(restored.send(api, restored.get(productId)!)).rejects.toThrow();
  expect(raw.mock.calls[0].slice(0, 4)).toEqual(raw.mock.calls[1].slice(0, 4));
  expect(new AdminCommands(sessionStorage, 'another-admin').all()).toEqual([]);
  expect(() => restored.prepare(productId, body, '"new-etag"')).toThrow();
});

it('requires explicit new intent after 412 and retains the edited body', async () => {
  const api = new Api('/api', 'demo-admin');
  vi.spyOn(api, 'raw').mockRejectedValue(new ApiError(412, null));
  const journal = new AdminCommands(sessionStorage, 'admin-identity');
  const command = journal.prepare(productId, body, '"old"');
  await expect(journal.send(api, command)).rejects.toThrow();
  expect(journal.get(productId)?.state).toBe('conflict');
  expect(journal.get(productId)?.body).toBe(JSON.stringify(body));
  journal.acknowledge(productId);
  expect(journal.prepare(productId, body, '"fresh"').key).not.toBe(command.key);
});

it('persists create, publish and archive commands independently with exact replay data', async () => {
  const api = new Api('/api', 'demo-admin');
  const raw = vi.spyOn(api, 'raw').mockRejectedValue(new ApiError(0, null));
  const journal = new AdminCommands(sessionStorage, 'admin-identity');
  const creation = journal.prepareCreate({
    ...body,
    id: 'new-program',
  } as CreateAdminProductRequest);
  const publication = journal.prepare(productId, body, '"before"');
  const archive = journal.prepareArchive(productId, 'Причина архивирования', '"after"');
  expect(journal.all()).toHaveLength(3);
  await expect(journal.send(api, creation)).rejects.toThrow();
  await expect(journal.send(api, archive)).rejects.toThrow();
  const restored = new AdminCommands(sessionStorage, 'admin-identity');
  expect(restored.get('new-program', 'create')?.body).toBe(
    JSON.stringify({ ...body, id: 'new-program' }),
  );
  expect(restored.get(productId)?.key).toBe(publication.key);
  expect(restored.get(productId, 'archive')?.key).toBe(archive.key);
  expect(creation.headers?.['X-Request-Id']).toMatch(/^[0-9a-f-]{36}$/);
  expect(raw.mock.calls[0][3]).toEqual(creation.headers);
  expect(raw.mock.calls[0].slice(0, 4)).toEqual([
    'POST',
    '/v2/admin/products',
    creation.body,
    creation.headers,
  ]);
  expect(raw.mock.calls[1].slice(0, 4)).toEqual([
    'POST',
    archive.path,
    archive.body,
    archive.headers,
  ]);
});

it('publishes once in the HTTP stub and retains the old product version', async () => {
  const bank = new MockBank();
  const admin = new MockAdminCatalog(bank);
  const path = '/api/v2/admin/products/' + productId;
  const auth = new Headers({
    Authorization: 'Bearer demo-admin',
    'X-Request-Id': crypto.randomUUID(),
  });
  const before = await admin.handle('GET', path, undefined, auth);
  expect(before.status).toBe(200);
  const headers = new Headers(auth);
  headers.set('If-Match', before.headers.ETag);
  headers.set('Idempotency-Key', 'publish-v2-admin');
  const first = await admin.handle('POST', path + '/versions', JSON.stringify(body), headers);
  expect(first.status).toBe(201);
  expect(bank.currentProducts().find((item) => item.id === productId)?.version).toBe('2');
  expect(bank.productVersions(productId).map((item) => item.version)).toEqual(['1', '2']);
  const replay = await admin.handle('POST', path + '/versions', JSON.stringify(body), headers);
  expect(replay.body).toEqual(first.body);
  headers.set('Idempotency-Key', 'publish-v3-stale');
  expect(
    (await admin.handle('POST', path + '/versions', JSON.stringify(body), headers)).status,
  ).toBe(412);
  const forbidden = await admin.handle(
    'GET',
    path,
    undefined,
    new Headers({ Authorization: 'Bearer demo-customer' }),
  );
  expect(forbidden.status).toBe(403);
});

it('creates and archives a program in the HTTP stub while retaining version 1', async () => {
  const bank = new MockBank();
  const admin = new MockAdminCatalog(bank);
  const path = '/api/v2/admin/products';
  const headers = new Headers({
    Authorization: 'Bearer demo-admin',
    'Idempotency-Key': 'create-new-program',
  });
  const created = await admin.handle(
    'POST',
    path,
    JSON.stringify({ ...body, id: 'new-program' }),
    headers,
  );
  expect(created.status).toBe(201);
  expect(bank.currentProducts().find((p) => p.id === 'new-program')?.version).toBe('1');
  expect(
    (await admin.handle('POST', path, JSON.stringify({ ...body, id: 'new-program' }), headers))
      .body,
  ).toEqual(created.body);
  headers.set('Idempotency-Key', 'archive-new-program');
  headers.set('If-Match', created.headers.ETag);
  const archived = await admin.handle(
    'POST',
    path + '/new-program/archive',
    JSON.stringify({ reason: 'Завершение программы' }),
    headers,
  );
  expect(archived.status).toBe(200);
  expect(bank.currentProducts().some((p) => p.id === 'new-program')).toBe(false);
  expect(bank.productVersions('new-program')).toHaveLength(1);
  expect((await admin.handle('GET', path + '/new-program', undefined, headers)).body).toMatchObject(
    { active: false },
  );
});
