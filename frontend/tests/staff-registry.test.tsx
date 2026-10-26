import { beforeEach, afterEach, it, expect, vi } from 'vitest';
import { render, screen, fireEvent, waitFor, cleanup, act } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { SessionProvider } from '../src/core/session';
import { App } from '../src/app';
import { Api, ApiError, type Reply } from '../src/api/client';
import type { Application } from '../src/api/generated';
import examples from '../contract/examples.json';
import { approvalLabel, staffSource } from '../src/core/staff-navigation';

const draft = examples.approved.find((e) => e.operationId === 'createApplication')!.response
  .body as Application;
const approved = examples.approved.find(
  (e) =>
    e.operationId === 'getApplication' && (e.response.body as Application).status === 'OFFER_READY',
)!.response.body as Application;
const reply = (data: unknown): Reply<any> => ({
  data,
  status: 200,
  etag: '"test"',
  requestId: null,
  date: null,
});
const show = (path = '/staff/applications') =>
  render(
    <MemoryRouter initialEntries={[path]}>
      <SessionProvider>
        <App />
      </SessionProvider>
    </MemoryRouter>,
  );
beforeEach(() => {
  sessionStorage.clear();
  sessionStorage.setItem(
    'alfa.session',
    JSON.stringify({ role: 'staff', token: 'synthetic', identity: 'staff' }),
  );
});
afterEach(cleanup);

it('shows all-status registry with snapshot version, nulls and explicit current approval', async () => {
  vi.spyOn(Api.prototype, 'call').mockResolvedValue(
    reply({
      items: [{ ...draft, id: '00000000-0000-4000-8000-000000009999' }, approved],
      nextCursor: null,
    }),
  );
  show();
  await screen.findByText('Автоматически одобрено');
  expect(screen.getByRole('heading', { name: 'Все заявки' })).toBeTruthy();
  expect(screen.getAllByText('Версия 1')).toHaveLength(2);
  expect(screen.queryByText('＋ Новая заявка')).toBeNull();
  expect(approvalLabel({ ...approved, dataVersion: 2 })).toBe('—');
  expect(approvalLabel(draft)).toBe('—');
});
it('resets cursor when a filter changes and preserves the filtered registry through card and history', async () => {
  const calls = vi
    .spyOn(Api.prototype, 'call')
    .mockImplementation(((name: string) =>
      Promise.resolve(
        reply(
          name === 'listStaffApplications'
            ? { items: [approved], nextCursor: 'opaque-cursor' }
            : name === 'getApplication'
              ? approved
              : { items: [] },
        ),
      )) as any);
  show('/staff/applications?status=OFFER_READY&approvalType=AUTO&cursor=old');
  await screen.findByText('Автоматически одобрено');
  fireEvent.change(screen.getByLabelText('Статус заявки'), { target: { value: 'DISBURSED' } });
  await waitFor(() =>
    expect(calls).toHaveBeenCalledWith(
      'listStaffApplications',
      expect.objectContaining({ status: 'DISBURSED', approvalType: 'AUTO', cursor: undefined }),
      undefined,
      expect.any(AbortSignal),
    ),
  );
  await screen.findByText('Автоматически одобрено');
  fireEvent.click(screen.getByRole('link', { name: /Открыть заявку/ }));
  await screen.findByRole('heading', { name: approved.company.legalName });
  fireEvent.click(screen.getByRole('link', { name: 'История и версии ↗' }));
  await screen.findByRole('heading', { name: 'История и версии' });
  fireEvent.click(screen.getByRole('link', { name: '← Актуальная заявка' }));
  await screen.findByRole('heading', { name: approved.company.legalName });
  expect(screen.getByRole('link', { name: '← Все заявки' }).getAttribute('href')).toContain(
    'status=DISBURSED',
  );
  expect(calls.mock.calls.some(([name]) => name === 'getStaffManualReview')).toBe(false);
  expect(screen.queryByRole('button', { name: 'Принять предложение' })).toBeNull();
});
it('ignores delayed data from the previous filter', async () => {
  let finish!: (v: Reply<any>) => void;
  vi.spyOn(Api.prototype, 'call').mockImplementation(((name: string, params: any) =>
    params.status === 'DRAFT'
      ? Promise.resolve(
          reply({
            items: [{ ...draft, company: { ...draft.company, legalName: 'Новая выборка' } }],
            nextCursor: null,
          }),
        )
      : new Promise((resolve) => {
          finish = resolve;
        })) as any);
  show();
  fireEvent.change(screen.getByLabelText('Статус заявки'), { target: { value: 'DRAFT' } });
  await screen.findByText('Новая выборка');
  await act(async () => finish(reply({ items: [approved], nextCursor: null })));
  expect(screen.queryByText('Автоматически одобрено')).toBeNull();
  expect(screen.getByText('Новая выборка')).toBeTruthy();
});
it('shows retryable error, empty result, and expires unauthorized session', async () => {
  const calls = vi
    .spyOn(Api.prototype, 'call')
    .mockRejectedValueOnce(new Error('Временный сбой'))
    .mockResolvedValueOnce(reply({ items: [], nextCursor: null }))
    .mockRejectedValue(new ApiError(401, null));
  show();
  await screen.findByText('Временный сбой');
  fireEvent.click(screen.getByRole('button', { name: /Повторить/ }));
  await screen.findByText('Заявок по выбранным фильтрам нет.');
  fireEvent.click(screen.getByRole('button', { name: '↻ Обновить реестр' }));
  await waitFor(() => expect(sessionStorage.getItem('alfa.session')).toBeNull());
  expect(calls).toHaveBeenCalledTimes(3);
});
it('role guards cover direct child URLs and unsafe return locations are rejected', async () => {
  sessionStorage.setItem(
    'alfa.session',
    JSON.stringify({ role: 'customer', token: 'customer', identity: 'customer' }),
  );
  const calls = vi.spyOn(Api.prototype, 'call');
  show('/staff/applications/' + approved.id + '/history');
  expect(screen.getByRole('heading', { name: 'Недостаточно прав' })).toBeTruthy();
  expect(calls).not.toHaveBeenCalled();
  expect(staffSource('https://other.example')).toBe('/staff/applications');
  expect(staffSource('/staff/applications/other')).toBe('/staff/applications');
});

it.each([
  'DRAFT',
  'SUBMITTED',
  'VALIDATION',
  'IN_REVIEW',
  'NEEDS_INFO',
  'MANUAL_REVIEW',
  'OFFER_READY',
  'OFFER_ACCEPTED',
  'AWAITING_SIGNATURE',
  'SIGNED',
  'DISBURSING',
  'DISBURSED',
  'REJECTED',
  'CANCELLED',
  'EXPIRED',
] as const)(
  'opens staff card for %s without an open manual case or customer commands',
  async (status) => {
    const app: Application = { ...draft, status, manualReview: null };
    const calls = vi.spyOn(Api.prototype, 'call').mockResolvedValue(reply(app));
    show('/staff/applications/' + app.id);
    await screen.findByRole('heading', { name: app.company.legalName });
    expect(calls.mock.calls.some(([name]) => name === 'getStaffManualReview')).toBe(false);
    for (const name of [
      'Отправить заявку',
      'Принять предложение',
      'Подписать договор',
      'Отменить заявку',
    ])
      expect(screen.queryByRole('button', { name })).toBeNull();
  },
);
