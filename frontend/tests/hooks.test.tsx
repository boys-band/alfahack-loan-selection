import { afterEach, beforeEach, it, expect, vi } from 'vitest';
import { renderHook, act, cleanup } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { SessionProvider } from '../src/core/session';
import { useApplication } from '../src/core/hooks';
import { pollingDelay } from '../src/core/state';
import { Api, type Reply } from '../src/api/client';
import type { Application } from '../src/api/generated';
import examples from '../contract/examples.json';
const draft = examples.approved.find((e) => e.operationId === 'createApplication')!.response
  .body as Application;
const reply = (a: Application): Reply<Application> => ({
  data: a,
  status: 200,
  etag: '"opaque"',
  date: null,
  requestId: null,
});
const wrap = ({ children }: { children: React.ReactNode }) => (
  <MemoryRouter>
    <SessionProvider>{children}</SessionProvider>
  </MemoryRouter>
);
beforeEach(() => {
  sessionStorage.clear();
  sessionStorage.setItem(
    'alfa.session',
    JSON.stringify({ role: 'customer', token: 'synthetic', identity: 'test' }),
  );
});
afterEach(() => {
  cleanup();
  vi.useRealTimers();
});
it('ignores delayed GET from previous card', async () => {
  let finish!: (v: Reply<Application>) => void;
  vi.spyOn(Api.prototype, 'call').mockImplementation(((name: string, params: any) =>
    params.applicationId === 'old'
      ? new Promise((r) => {
          finish = r;
        })
      : Promise.resolve(reply({ ...draft, id: 'new', version: 8 }))) as any);
  const h = renderHook(({ id }) => useApplication(id), {
    initialProps: { id: 'old' },
    wrapper: wrap,
  });
  h.rerender({ id: 'new' });
  await act(async () => {
    await Promise.resolve();
  });
  expect(h.result.current.data?.data.id).toBe('new');
  await act(async () => finish(reply({ ...draft, id: 'old', version: 99 })));
  expect(h.result.current.data?.data.id).toBe('new');
});
it('serializes GET refreshes and never regresses aggregate version', async () => {
  let finish!: (v: Reply<Application>) => void;
  let active = 0,
    max = 0;
  const spy = vi.spyOn(Api.prototype, 'call').mockImplementation((() => {
    active++;
    max = Math.max(active, max);
    return new Promise((r) => {
      finish = (x) => {
        active--;
        r(x);
      };
    });
  }) as any);
  const h = renderHook(() => useApplication('card'), { wrapper: wrap });
  let pending!: Promise<void>;
  act(() => {
    pending = h.result.current.refresh();
  });
  expect(spy).toHaveBeenCalledTimes(1);
  await act(async () => {
    finish(reply({ ...draft, version: 10 }));
    await Promise.resolve();
  });
  await act(async () => {
    finish(reply({ ...draft, version: 9 }));
    await pending;
  });
  expect(h.result.current.data?.data.version).toBe(10);
  expect(max).toBe(1);
});
it('polling pauses while hidden, refocus reads immediately, final states do not poll', async () => {
  vi.useFakeTimers();
  vi.spyOn(Api.prototype, 'call').mockResolvedValue(
    reply({ ...draft, status: 'DISBURSED' }) as any,
  );
  renderHook(() => useApplication('card'), { wrapper: wrap });
  await act(async () => {
    await Promise.resolve();
  });
  await act(async () => vi.advanceTimersByTimeAsync(60000));
  expect(Api.prototype.call).toHaveBeenCalledTimes(1);
  await act(async () => window.dispatchEvent(new Event('focus')));
  expect(Api.prototype.call).toHaveBeenCalledTimes(2);
  expect(pollingDelay({ ...draft, status: 'IN_REVIEW', reviewStage: 'SCORING' })).toBe(2000);
  expect(pollingDelay({ ...draft, status: 'NEEDS_INFO' })).toBe(15000);
  expect(pollingDelay(draft, 3, 18000)).toBe(18000);
});

it('a successful create receipt stays resolved even if the follow-up GET fails', async () => {
  const { useMutation } = await import('../src/components/commands');
  vi.spyOn(Api.prototype, 'raw').mockResolvedValue(reply(draft));
  const refresh = vi.fn().mockRejectedValue(new Error('GET temporarily unavailable'));
  const h = renderHook(() => useMutation('new', refresh), { wrapper: wrap });
  let result: unknown;
  await act(async () => {
    result = await h.result.current.run(
      'createApplication',
      {},
      {
        companyId: draft.company.id,
        productId: draft.product.id,
        productVersion: draft.product.version,
      },
    );
  });
  expect(result).toMatchObject({ data: { id: draft.id } });
  expect(JSON.parse(sessionStorage.getItem('alfa.commands.test') || '[]')).toEqual([]);
  expect(Api.prototype.raw).toHaveBeenCalledTimes(1);
});
