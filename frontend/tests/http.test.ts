// @vitest-environment node
import { beforeAll, afterAll, beforeEach, describe, it, expect } from 'vitest';
import { createServer, type Server } from 'node:http';
import { MockBank } from '../src/mocks/bank';
import { Api, ApiError } from '../src/api/client';
import { validate } from '../src/api/schema';
let bank: MockBank, api: Api, server: Server, now: number;
beforeAll(async () => {
  server = createServer(async (req, res) => {
    const chunks: Buffer[] = [];
    for await (const chunk of req) chunks.push(chunk);
    const body = chunks.length ? Buffer.concat(chunks).toString() : undefined;
    const headers = new Headers();
    for (const [k, v] of Object.entries(req.headers)) if (typeof v === 'string') headers.set(k, v);
    try {
      const reply = await bank.handle(req.method!, req.url!, body, headers);
      res.writeHead(reply.status, { 'Content-Type': 'application/json', ...reply.headers });
      res.end(JSON.stringify(reply.body));
    } catch (error) {
      res.writeHead(500);
      res.end(JSON.stringify({ error: String(error) }));
    }
  });
  await new Promise<void>((r) => server.listen(0, '127.0.0.1', r));
  const address = server.address() as { port: number };
  api = new Api('http://127.0.0.1:' + address.port + '/api', 'demo-customer');
});
afterAll(async () => {
  await new Promise<void>((r) => server.close(() => r()));
});
beforeEach(() => {
  now = Date.parse('2026-10-24T12:00:00Z');
  bank = new MockBank(undefined, () => now, 100);
});
async function cmd(
  id: string,
  path: string,
  body: unknown,
  key = crypto.randomUUID(),
  etag?: string,
) {
  const a = await api.call('getApplication', { applicationId: id }, undefined);
  return api.raw<any>(
    'POST',
    '/v2/applications/' + id + '/' + path,
    body === undefined ? undefined : JSON.stringify(body),
    { 'If-Match': etag || a.etag!, 'Idempotency-Key': key },
    undefined,
    'Application',
  );
}
function step() {
  now += 101;
  bank.tick();
}
describe('HTTP through real fetch and schema-valid stateful contract server', () => {
  it('progresses both review stages and replays old 202 without regression or duplicate', async () => {
    const id = bank.seed('approved', 'DRAFT');
    const start = await api.call('getApplication', { applicationId: id }, undefined);
    const key = crypto.randomUUID();
    const response = await cmd(
      id,
      'submit',
      { personalDataConsent: true, creditBureauConsent: true },
      key,
      start.etag!,
    );
    expect(response.status).toBe(202);
    expect(response.data.status).toBe('SUBMITTED');
    const statuses = [];
    for (let i = 0; i < 4; i++) {
      step();
      const a = await api.call('getApplication', { applicationId: id }, undefined);
      statuses.push([a.data.status, a.data.reviewStage]);
    }
    expect(statuses).toEqual([
      ['VALIDATION', null],
      ['IN_REVIEW', 'ENRICHMENT'],
      ['IN_REVIEW', 'SCORING'],
      ['OFFER_READY', null],
    ]);
    const repeat = await cmd(
      id,
      'submit',
      { personalDataConsent: true, creditBureauConsent: true },
      key,
      start.etag!,
    );
    expect(repeat.data).toEqual(response.data);
    expect((await api.call('getApplication', { applicationId: id }, undefined)).data.status).toBe(
      'OFFER_READY',
    );
    expect(Object.keys(bank.state.apps)).toHaveLength(1);
  });
  it('rejects a modified idempotency fingerprint, changed ETag and public role misuse', async () => {
    const id = bank.seed('approved', 'DRAFT');
    const first = await api.call('getApplication', { applicationId: id }, undefined);
    const key = crypto.randomUUID();
    await cmd(
      id,
      'submit',
      { personalDataConsent: true, creditBureauConsent: true },
      key,
      first.etag!,
    );
    await expect(
      cmd(id, 'submit', { personalDataConsent: true, creditBureauConsent: true }, key, '"changed"'),
    ).rejects.toMatchObject({ status: 409, problem: { code: 'IDEMPOTENCY_CONFLICT' } });
    await expect(
      cmd(id, 'cancel', { reason: 'Отмена' }, crypto.randomUUID(), first.etag!),
    ).rejects.toMatchObject({ status: 412 });
    await expect(api.call('listStaffManualReviews', {}, undefined)).rejects.toMatchObject({
      status: 403,
    });
  });
  it('all three deadlines expire at the exact boundary and ETag changes independently of version', async () => {
    for (const status of ['NEEDS_INFO', 'OFFER_READY', 'AWAITING_SIGNATURE'] as const) {
      const id = bank.seed('approved', status);
      const a = bank.state.apps[id];
      a.actionDeadline = new Date(now + 100).toISOString();
      if (a.agreement) a.agreement.signBy = a.actionDeadline;
      const before = await api.call('getApplication', { applicationId: id }, undefined);
      now += 100;
      const boundary = await api.call('getApplication', { applicationId: id }, undefined);
      expect(boundary.data.version).toBe(before.data.version);
      expect(boundary.etag).not.toBe(before.etag);
      expect(boundary.data.availableActions).toEqual([]);
      bank.tick();
      expect((await api.call('getApplication', { applicationId: id }, undefined)).data.status).toBe(
        'EXPIRED',
      );
    }
  });
  it('signed → account correction → disbursed keeps the original document', async () => {
    const id = bank.seed('payout', 'AWAITING_SIGNATURE');
    const ag = bank.state.agreements[id];
    await cmd(id, 'sign', {
      agreementId: ag.id,
      documentVersion: ag.document.version,
      documentSha256: ag.document.sha256,
      consent: true,
    });
    step();
    step();
    expect(bank.state.apps[id].payoutCheck?.outcome).toBe('NEEDS_CORRECTION');
    const doc = structuredClone(ag.document);
    await cmd(id, 'payout-account', {
      settlementAccountId: '00000000-0000-4000-8000-000000001198',
    });
    step();
    step();
    const end = (await api.call('getApplication', { applicationId: id }, undefined)).data;
    expect(end.status).toBe('DISBURSED');
    expect(bank.state.agreements[id].document).toEqual(doc);
    expect(end.payoutInstruction.payoutVersion).toBe(2);
  });
  it('retry-processing has no body and preserves the business status before worker completion', async () => {
    const id = bank.seed('technical', 'IN_REVIEW');
    step();
    const a = await api.call('getApplication', { applicationId: id }, undefined);
    expect(a.data.technicalError?.step).toBe('SCORING');
    const r = await cmd(id, 'retry-processing', undefined);
    expect(r.status).toBe(202);
    expect(r.data.status).toBe('IN_REVIEW');
    step();
    expect(bank.state.apps[id].status).toBe('OFFER_READY');
  });
  it('429/503 do not mutate the application', async () => {
    const id = bank.seed('approved', 'DRAFT');
    for (const status of [429, 503]) {
      bank.nextFault = { operation: 'getApplication', status };
      await expect(
        api.call('getApplication', { applicationId: id }, undefined),
      ).rejects.toMatchObject({ status, retryAfter: 1000 });
      expect(bank.state.apps[id].status).toBe('DRAFT');
    }
  });
});
