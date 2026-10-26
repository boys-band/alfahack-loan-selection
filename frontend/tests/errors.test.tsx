import { afterEach, expect, it } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import { ApiError } from '../src/api/client';
import type { Problem } from '../src/api/generated';
import { validate } from '../src/api/schema';
import { ErrorBox, errorsForFields } from '../src/components/common';

afterEach(cleanup);
function problem(path: string, message: string): Problem {
  return {
    type: 'urn:alfa:problem:validation_error',
    title: 'VALIDATION_ERROR',
    status: 422,
    code: 'VALIDATION_ERROR',
    detail: 'POST /v2/applications: Loan terms are invalid',
    requestId: '00000000-0000-4000-8000-000000000001',
    errors: [{ path, message }],
  };
}

it.each([
  ['/amountRub', 'Сумма, ₽', 'Укажите сумму в пределах лимитов выбранного кредита.'],
  ['/changes/termMonths', 'Срок, мес.', 'Укажите срок в пределах лимитов выбранного кредита.'],
])('explains legacy product limit errors for %s without API details', (path, label, message) => {
  const data = problem(path, 'Outside current product limits');
  const error = new ApiError(422, data, data.requestId);
  render(<ErrorBox error={error} />);
  const alert = screen.getByRole('alert');
  expect(alert).toHaveTextContent('Проверьте заполненные поля.');
  expect(alert).toHaveTextContent(`${label} — ${message}`);
  expect(alert).not.toHaveTextContent(/Outside current|\/amountRub|\/changes|\/termMonths|\/v2\//);
  expect(alert).not.toHaveTextContent(/RequestId|VALIDATION_ERROR|Для диагностики/);
  expect(alert).not.toHaveTextContent(data.requestId);
  expect(errorsForFields(error)).toEqual([{ path: path.replace('/changes', ''), message }]);
});

it('preserves the allowed range supplied by applications', () => {
  const message = 'Сумма кредита должна быть от 100 000 до 5 000 000 ₽.';
  render(<ErrorBox error={new ApiError(422, problem('/amountRub', message))} />);
  expect(screen.getByRole('alert')).toHaveTextContent(`Сумма, ₽ — ${message}`);
});

it('shows local schema errors without JSON paths or validator internals', () => {
  let error: unknown;
  try {
    validate('UpdateDraftRequest', { amountRub: 'invalid' });
  } catch (e) {
    error = e;
  }
  render(<ErrorBox error={error} />);
  expect(screen.getByRole('alert')).toHaveTextContent('Проверьте заполненные поля.');
  expect(screen.getByRole('alert')).not.toHaveTextContent(/amountRub|must be|anyOf/);
});

it('identifies all invalid program fields and explains the actual schema constraints', () => {
  let error: unknown;
  try {
    validate('CreateAdminProductRequest', {
      id: '656',
      name: 'Новая программа',
      description: 'Проверка полей программы',
      purposeCodes: ['WORKING_CAPITAL'],
      minAmountRub: 100000,
      maxAmountRub: 5000000,
      minTermMonths: 1,
      maxTermMonths: 121,
      annualRateBps: 2000,
      minBusinessAgeMonths: 6,
      maxDebtServiceRatioBps: 5000,
      reason: 'Коротко',
    });
  } catch (failure) {
    error = failure;
  }
  expect(error).toBeInstanceOf(Error);
  render(<ErrorBox error={error} />);
  const alert = screen.getByRole('alert');
  expect(alert).toHaveTextContent('Идентификатор программы — От 2 до 64 символов');
  expect(alert).toHaveTextContent('первый символ — буква');
  expect(alert).toHaveTextContent('Максимальный срок, мес. — Укажите значение не больше 120.');
  expect(alert).toHaveTextContent('Обоснование — Введите не меньше 10 символов.');
  expect(alert).not.toHaveTextContent(/Поле формы|must|pattern|\/id|\/reason|maxTermMonths/);
});

it('names missing program fields even when the schema reports the enclosing object', () => {
  let error: unknown;
  try {
    validate('CreateAdminProductRequest', {});
  } catch (failure) {
    error = failure;
  }
  render(<ErrorBox error={error} />);
  const alert = screen.getByRole('alert');
  expect(alert).toHaveTextContent('Идентификатор программы — Заполните поле.');
  expect(alert).toHaveTextContent('Название программы — Заполните поле.');
  expect(alert).toHaveTextContent('Обоснование — Заполните поле.');
  expect(alert).not.toHaveTextContent(/Поле формы|must|required/);
});

it('explains catalog validation responses with program field names and Russian messages', () => {
  const data = problem('/id', 'Invalid product ID');
  data.errors.push(
    { path: '/maxAmountRub', message: 'Number is outside product limits' },
    { path: '/reason', message: 'Invalid text' },
    { path: '/purposeCodes', message: 'Choose at least one purpose' },
  );
  render(<ErrorBox error={new ApiError(422, data)} />);
  const alert = screen.getByRole('alert');
  expect(alert).toHaveTextContent('Идентификатор программы — От 2 до 64 символов');
  expect(alert).toHaveTextContent('Максимальная сумма, ₽ — Введите целое число');
  expect(alert).toHaveTextContent('не меньше минимального значения');
  expect(alert).toHaveTextContent('Обоснование — Введите обоснование от 10 до 2000 символов.');
  expect(alert).toHaveTextContent('Разрешённые цели — Выберите хотя бы одну цель кредита.');
  expect(alert).not.toHaveTextContent(/Поле формы|Invalid|Number is|Choose|\/id|RequestId/);
});
