import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { Access } from '../src/pages/access';
import { SessionProvider } from '../src/core/session';
import { config } from '../src/core/config';
import { Api, ApiError } from '../src/api/client';

const initialMock = config.mock;
beforeEach(() => sessionStorage.clear());
afterEach(() => {
  cleanup();
  config.mock = initialMock;
});
function show() {
  return render(
    <MemoryRouter>
      <SessionProvider>
        <Access />
      </SessionProvider>
    </MemoryRouter>,
  );
}

for (const mock of [true, false])
  it(`shows role-specific synthetic token hints above the form in ${mock ? 'mock' : 'API'} mode`, () => {
    config.mock = mock;
    show();
    const hints = screen.getByRole('complementary', { name: 'Тестовые токены' });
    for (const token of mock
      ? ['demo-customer', 'demo-staff', 'demo-admin']
      : ['demo-customer-only', 'demo-staff-only', 'demo-product-admin-only'])
      expect(within(hints).getByText(token, { exact: true })).toBeInTheDocument();
    expect(
      hints.compareDocumentPosition(screen.getByLabelText('Демотокен')) &
        Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy();
    if (!mock) expect(hints).toHaveTextContent('Для стандартного локального стенда');
  });

for (const [status, message, invalid] of [
  [401, 'Демотокен не принят.', true],
  [403, 'Этот токен не подходит для выбранной роли.', true],
  [0, 'Не удалось подключиться к сервису входа.', false],
  [503, 'Сервис входа временно недоступен.', false],
] as const)
  it(`explains login failure ${status} without exposing server details or creating a session`, async () => {
    vi.spyOn(Api.prototype, 'call').mockRejectedValue(
      new ApiError(status, {
        type: 'about:blank',
        title: 'UNAUTHENTICATED',
        status,
        detail: 'Bearer private-token /api/v2/companies INTERNAL_DETAIL',
        code: 'UNAUTHENTICATED',
        requestId: crypto.randomUUID(),
        errors: [],
      }),
    );
    show();
    const input = screen.getByLabelText('Демотокен');
    fireEvent.change(input, { target: { value: 'wrong' } });
    fireEvent.click(screen.getByRole('button', { name: 'Войти →' }));
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent(message);
    expect(alert).not.toHaveTextContent(/private-token|Bearer|api\/v2|INTERNAL_DETAIL/);
    expect(input).toHaveAttribute('type', 'password');
    expect(input.getAttribute('aria-invalid')).toBe(invalid ? 'true' : null);
    expect(sessionStorage.getItem('alfa.session')).toBeNull();
    fireEvent.change(input, { target: { value: 'corrected' } });
    expect(screen.queryByRole('alert')).toBeNull();
    expect(input).not.toHaveAttribute('aria-invalid');
  });

it('rejects whitespace locally without checking access over HTTP', async () => {
  const call = vi.spyOn(Api.prototype, 'call');
  show();
  fireEvent.change(screen.getByLabelText('Демотокен'), { target: { value: '   ' } });
  fireEvent.click(screen.getByRole('button', { name: 'Войти →' }));
  expect(await screen.findByText('Введите демотокен.')).toBeInTheDocument();
  expect(call).not.toHaveBeenCalled();
});

it('keeps the selected role and token fixed while access is being checked', async () => {
  let reject!: (reason: Error) => void;
  const call = vi.spyOn(Api.prototype, 'call').mockImplementation(
    () =>
      new Promise((_, fail) => {
        reject = fail;
      }),
  );
  show();
  fireEvent.change(screen.getByLabelText('Демотокен'), { target: { value: 'wrong' } });
  fireEvent.click(screen.getByRole('button', { name: 'Войти →' }));
  expect(screen.getByLabelText('Демотокен')).toBeDisabled();
  for (const radio of screen.getAllByRole('radio')) expect(radio).toBeDisabled();
  fireEvent.click(screen.getByRole('button', { name: 'Проверяем доступ…' }));
  expect(call).toHaveBeenCalledTimes(1);
  reject(new ApiError(401, null));
  await screen.findByRole('alert');
  expect(screen.getByLabelText('Демотокен')).toBeEnabled();
});
