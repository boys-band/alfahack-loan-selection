import { useState } from 'react';
import { afterEach, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { Input, Textarea, ValidatedForm } from '../src/components/validated-form';
import { DataFields } from '../src/components/fields';
import type { Fields } from '../src/core/forms';

afterEach(cleanup);

it('requires correcting a saved purpose that the selected product no longer allows', () => {
  const submit = vi.fn();
  function Sample() {
    const [fields, setFields] = useState<Fields>({ '/purpose': 'WORKING_CAPITAL' });
    return (
      <ValidatedForm
        onSubmit={(event) => {
          event.preventDefault();
          submit(fields);
        }}
      >
        <DataFields
          fields={fields}
          setFields={setFields}
          paths={['/purpose']}
          purposeCodes={['EQUIPMENT']}
        />
        <button>Сохранить</button>
      </ValidatedForm>
    );
  }
  render(<Sample />);
  expect(screen.getByRole('option', { name: /Оборотные средства — недоступна/ })).toBeDisabled();
  fireEvent.click(screen.getByRole('button', { name: 'Сохранить' }));
  expect(submit).not.toHaveBeenCalled();
  expect(screen.getByLabelText('Цель кредита')).toHaveFocus();
  expect(
    screen.getByText('Выберите цель кредита, доступную для выбранного продукта.'),
  ).toBeVisible();
  fireEvent.change(screen.getByLabelText('Цель кредита'), { target: { value: 'EQUIPMENT' } });
  fireEvent.click(screen.getByRole('button', { name: 'Сохранить' }));
  expect(submit).toHaveBeenCalledWith({ '/purpose': 'EQUIPMENT' });
});

function TwoFields({ submit }: { submit: () => void }) {
  const [first, setFirst] = useState('');
  const [second, setSecond] = useState('');
  return (
    <ValidatedForm
      aria-label="Условия"
      onSubmit={(event) => {
        event.preventDefault();
        submit();
      }}
    >
      <label htmlFor="first">Сумма</label>
      <Input
        id="first"
        type="number"
        min={1}
        max={100}
        step={1}
        required
        value={first}
        onChange={(event) => setFirst(event.target.value)}
      />
      <label htmlFor="second">Срок</label>
      <Input
        id="second"
        type="number"
        min={1}
        max={12}
        step={1}
        required
        value={second}
        onChange={(event) => setSecond(event.target.value)}
      />
      <button>Сохранить</button>
    </ValidatedForm>
  );
}

it('shows both errors during input and clears only the field that was corrected', () => {
  const submit = vi.fn();
  render(<TwoFields submit={submit} />);
  expect(screen.queryByText(/Укажите значение/)).toBeNull();
  const first = screen.getByLabelText('Сумма');
  const second = screen.getByLabelText('Срок');
  fireEvent.change(first, { target: { value: '-1' } });
  fireEvent.change(second, { target: { value: '13' } });
  expect(first).toHaveAccessibleDescription('Укажите значение от 1 до 100.');
  expect(second).toHaveAccessibleDescription('Укажите значение от 1 до 12.');
  fireEvent.change(first, { target: { value: '50' } });
  expect(first).not.toHaveAttribute('aria-invalid', 'true');
  expect(second).toHaveAttribute('aria-invalid', 'true');
  fireEvent.click(screen.getByRole('button'));
  expect(submit).not.toHaveBeenCalled();
  expect(second).toHaveFocus();
  fireEvent.change(second, { target: { value: '12' } });
  fireEvent.click(screen.getByRole('button'));
  expect(submit).toHaveBeenCalledTimes(1);
});

it('reveals every missing required field on the first submit', () => {
  render(<TwoFields submit={vi.fn()} />);
  fireEvent.click(screen.getByRole('button'));
  expect(screen.getAllByText('Заполните поле.')).toHaveLength(2);
  expect(screen.getByLabelText('Сумма')).toHaveFocus();
});

it('keeps draft fields optional and ignores disabled controls', () => {
  const submit = vi.fn();
  render(
    <ValidatedForm
      onSubmit={(event) => {
        event.preventDefault();
        submit();
      }}
    >
      <Input id="optional" type="number" min={1} />
      <Input id="disabled" required disabled />
      <button>Сохранить</button>
    </ValidatedForm>,
  );
  fireEvent.click(screen.getByRole('button'));
  expect(submit).toHaveBeenCalledTimes(1);
});

it('uses code points for soft text limits and rejects whitespace required text', () => {
  const submit = vi.fn();
  render(
    <ValidatedForm
      onSubmit={(event) => {
        event.preventDefault();
        submit();
      }}
    >
      <label htmlFor="reason">Обоснование</label>
      <Textarea id="reason" required minLength={10} data-max-length="2000" />
      <button>Сохранить</button>
    </ValidatedForm>,
  );
  const reason = screen.getByLabelText('Обоснование');
  fireEvent.change(reason, { target: { value: '🙂'.repeat(5) } });
  expect(reason).toHaveAttribute('aria-invalid', 'true');
  fireEvent.change(reason, { target: { value: '🙂'.repeat(1001) } });
  expect(reason).not.toHaveAttribute('aria-invalid', 'true');
  fireEvent.click(screen.getByRole('button'));
  expect(submit).toHaveBeenCalledTimes(1);
  fireEvent.change(reason, { target: { value: ' '.repeat(10) } });
  expect(reason).toHaveAccessibleDescription('Заполните поле.');
});

it('clears only the edited server error and shows the same rejection from a later response', () => {
  const errors = [
    { path: '/amountRub', message: 'Ошибка суммы' },
    { path: '/termMonths', message: 'Ошибка срока' },
  ];
  function Sample({ response }: { response: object }) {
    const [fields, setFields] = useState<Fields>({ '/amountRub': '10', '/termMonths': '5' });
    return (
      <ValidatedForm>
        <DataFields
          fields={fields}
          setFields={setFields}
          paths={['/amountRub', '/termMonths']}
          errors={errors}
          errorKey={response}
        />
      </ValidatedForm>
    );
  }
  const { rerender } = render(<Sample response={{}} />);
  fireEvent.change(screen.getByLabelText('Сумма, ₽'), { target: { value: '20' } });
  expect(screen.queryByText('Ошибка суммы')).toBeNull();
  expect(screen.getByText('Ошибка срока')).toBeInTheDocument();
  rerender(<Sample response={{}} />);
  expect(screen.getByText('Ошибка суммы')).toBeInTheDocument();
});

it('shows every cleared mandatory application field while keeping draft fields optional', () => {
  function Sample() {
    const [fields, setFields] = useState<Fields>({ '/amountRub': '100000', '/termMonths': '12' });
    return (
      <ValidatedForm>
        <DataFields
          fields={fields}
          setFields={setFields}
          paths={['/amountRub', '/termMonths']}
          requiredPaths={['/amountRub', '/termMonths']}
        />
      </ValidatedForm>
    );
  }
  render(<Sample />);
  fireEvent.change(screen.getByLabelText('Сумма, ₽'), { target: { value: '' } });
  fireEvent.change(screen.getByLabelText('Срок, мес.'), { target: { value: '' } });
  expect(screen.getAllByText('Заполните поле.')).toHaveLength(2);
  expect(screen.getByLabelText('Сумма, ₽')).toHaveAttribute('aria-invalid', 'true');
  expect(screen.getByLabelText('Срок, мес.')).toHaveAttribute('aria-invalid', 'true');
});
