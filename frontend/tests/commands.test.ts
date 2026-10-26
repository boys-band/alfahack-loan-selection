import { describe, it, expect, vi, beforeEach } from 'vitest';
import { Api, ApiError } from '../src/api/client';
import { Commands } from '../src/core/commands';
import examples from '../contract/examples.json';
import type { Application, Problem } from '../src/api/generated';
const draft = examples.approved.find((e) => e.operationId === 'createApplication')!.response
  .body as Application;
let api: Api;
beforeEach(() => {
  sessionStorage.clear();
  api = new Api('/api', 'synthetic');
});
describe('durable exact command journal', () => {
  for (const name of ['createApplication', 'updateDraft'] as const)
    it(`allows correcting ${name} after a confirmed validation rejection`, async () => {
      const journal = new Commands(sessionStorage, 'customer');
      const body =
        name === 'createApplication'
          ? {
              companyId: draft.company.id,
              productId: draft.product.id,
              productVersion: draft.product.version,
              amountRub: 5000001,
            }
          : { amountRub: 5000001 };
      const params = name === 'createApplication' ? {} : { applicationId: draft.id };
      const scope = name === 'createApplication' ? 'new' : draft.id;
      const rejected = journal.prepare(name, params, body, scope, '"etag"');
      const problem: Problem = {
        type: 'urn:alfa:problem:validation_error',
        title: 'VALIDATION_ERROR',
        status: 422,
        code: 'VALIDATION_ERROR',
        detail: 'Проверьте сумму.',
        requestId: crypto.randomUUID(),
        errors: [{ path: '/amountRub', message: 'Сумма превышает лимит.' }],
      };
      const raw = vi.spyOn(api, 'raw').mockRejectedValueOnce(new ApiError(422, problem));
      await expect(journal.send(api, rejected)).rejects.toThrow('Проверьте заполненные поля.');
      expect(journal.get(scope)).toBeUndefined();
      const restored = new Commands(sessionStorage, 'customer');
      const corrected = restored.prepare(
        name,
        params,
        { ...body, amountRub: 1000000 },
        scope,
        '"etag"',
      );
      expect(corrected.key).not.toBe(rejected.key);
      expect(JSON.parse(corrected.body!)).toMatchObject({ amountRub: 1000000 });
      raw.mockResolvedValueOnce({
        data: draft,
        status: name === 'createApplication' ? 201 : 200,
        etag: '"new"',
        requestId: null,
        date: null,
      });
      await restored.send(api, corrected);
      expect(restored.get(scope)).toBeUndefined();
      expect(raw.mock.calls[0][3]?.['Idempotency-Key']).not.toBe(
        raw.mock.calls[1][3]?.['Idempotency-Key'],
      );
    });

  it.each([
    [409, 'IDEMPOTENCY_CONFLICT'],
    [422, 'SNAPSHOT_MISMATCH'],
    [422, null],
  ] as const)(
    'retains a draft command for %s / %s instead of guessing it was rejected',
    async (status, code) => {
      const journal = new Commands(sessionStorage, 'customer');
      const command = journal.prepare(
        'updateDraft',
        { applicationId: draft.id },
        { amountRub: 1000000 },
        draft.id,
        '"etag"',
      );
      const problem: Problem | null = code
        ? {
            type: 'urn:alfa:problem:error',
            title: code,
            status,
            code,
            detail: 'Обновите заявку.',
            requestId: crypto.randomUUID(),
            errors: [],
          }
        : null;
      vi.spyOn(api, 'raw').mockRejectedValueOnce(new ApiError(status, problem));
      await expect(journal.send(api, command)).rejects.toThrow();
      expect(journal.get(draft.id)?.key).toBe(command.key);
      expect(journal.get(draft.id)?.state).toBe('blocked');
    },
  );

  it('persists before sending, blocks double click, and restores exact request after lost response/reload', async () => {
    let calls = 0;
    const raw = vi.spyOn(api, 'raw').mockImplementation(async () => {
      calls++;
      throw new ApiError(0, null);
    });
    const journal = new Commands(sessionStorage, 'customer');
    const c = journal.prepare(
      'submitApplication',
      { applicationId: draft.id },
      { personalDataConsent: true, creditBureauConsent: true },
      draft.id,
      '"opaque-etag"',
    );
    expect(journal.all()).toHaveLength(1);
    expect(() =>
      journal.prepare(
        'cancelApplication',
        { applicationId: draft.id },
        { reason: 'Другое намерение' },
        draft.id,
        '"new"',
      ),
    ).toThrow();
    await expect(journal.send(api, c)).rejects.toThrow();
    const restored = new Commands(sessionStorage, 'customer');
    await expect(restored.send(api, restored.get(draft.id)!)).rejects.toThrow();
    expect(calls).toBe(2);
    expect(raw.mock.calls[0].slice(0, 4)).toEqual(raw.mock.calls[1].slice(0, 4));
  });
  it('keeps absent body absent and isolates identities', async () => {
    const j = new Commands(sessionStorage, 'staff');
    const c = j.prepare(
      'retryProcessing',
      { applicationId: draft.id },
      undefined,
      draft.id,
      '"tag"',
    );
    expect('body' in c).toBe(false);
    expect(new Commands(sessionStorage, 'customer').all()).toEqual([]);
  });
  it('requires explicit resolution of 412; old key cannot acquire a new ETag', async () => {
    const j = new Commands(sessionStorage, 'customer');
    const c = j.prepare(
      'updateDraft',
      { applicationId: draft.id },
      { amountRub: 123 },
      draft.id,
      '"old"',
    );
    vi.spyOn(api, 'raw').mockRejectedValue(new ApiError(412, null));
    await expect(j.send(api, c)).rejects.toThrow();
    expect(j.get(draft.id)?.state).toBe('conflict');
    await expect(j.send(api, c)).rejects.toThrow('подтверждение');
    expect(j.get(draft.id)?.etag).toBe('"old"');
    j.acknowledge(c.key);
    const next = j.prepare(
      'updateDraft',
      { applicationId: draft.id },
      { amountRub: 123 },
      draft.id,
      '"fresh"',
    );
    expect(next.key).not.toBe(c.key);
  });
  it('202 is acceptance only, old receipt never becomes displayed data', async () => {
    const j = new Commands(sessionStorage, 'customer');
    const c = j.prepare(
      'cancelApplication',
      { applicationId: draft.id },
      { reason: 'Отмена' },
      draft.id,
      '"tag"',
    );
    vi.spyOn(api, 'raw').mockResolvedValue({
      data: { ...draft, version: 10 },
      status: 202,
      etag: '"receipt"',
      requestId: null,
      date: null,
    });
    await j.send(api, c);
    j.reconcile({ ...draft, version: 10, processing: null });
    expect(j.get(draft.id)).toBeDefined();
    j.reconcile({ ...draft, version: 11, processing: null });
    expect(j.get(draft.id)).toBeUndefined();
  });
  it('honors Retry-After and retains fingerprint for idempotency in progress', async () => {
    const j = new Commands(sessionStorage, 'customer');
    const c = j.prepare(
      'retryProcessing',
      { applicationId: draft.id },
      undefined,
      draft.id,
      '"tag"',
    );
    vi.spyOn(api, 'raw').mockRejectedValue(new ApiError(429, null, null, 10000));
    await expect(j.send(api, c)).rejects.toThrow();
    expect(j.get(draft.id)?.retryAt).toBeGreaterThan(Date.now());
    await expect(j.send(api, c)).rejects.toThrow('подождать');
  });
  it('blocks overlapping sends', async () => {
    const j = new Commands(sessionStorage, 'customer');
    const c = j.prepare(
      'retryProcessing',
      { applicationId: draft.id },
      undefined,
      draft.id,
      '"tag"',
    );
    let finish!: () => void;
    vi.spyOn(api, 'raw').mockImplementation(
      () =>
        new Promise((resolve) => {
          finish = () =>
            resolve({ data: draft, status: 200, etag: null, date: null, requestId: null });
        }),
    );
    const first = j.send(api, c);
    await expect(j.send(api, c)).rejects.toThrow('уже');
    finish();
    await first;
  });
  for (const name of ['cancelApplication', 'retryProcessing'] as const)
    it(`allows ${name} after a confirmed 202 while processing is pending or paused`, async () => {
      const j = new Commands(sessionStorage, 'customer');
      const submitted = j.prepare(
        'submitApplication',
        { applicationId: draft.id },
        { personalDataConsent: true, creditBureauConsent: true },
        draft.id,
        '"before-submit"',
      );
      vi.spyOn(api, 'raw').mockResolvedValue({
        data: { ...draft, version: 2 },
        status: 202,
        etag: '"accepted"',
        requestId: null,
        date: null,
      });
      await j.send(api, submitted);
      const restored = new Commands(sessionStorage, 'customer');
      expect(restored.get(draft.id)?.state).toBe('accepted');
      expect(() =>
        restored.prepare(
          'submitApplication',
          { applicationId: draft.id },
          { personalDataConsent: true, creditBureauConsent: true },
          draft.id,
          '"current-get"',
        ),
      ).toThrow('Сначала разрешите исход');
      const next = restored.prepare(
        name,
        { applicationId: draft.id },
        name === 'cancelApplication' ? { reason: 'Финансирование не требуется' } : undefined,
        draft.id,
        '"current-get"',
      );
      expect(next.key).not.toBe(submitted.key);
      expect(restored.all()).toEqual([next]);
      expect(next.etag).toBe('"current-get"');
    });
});
