import { useEffect, useRef, type ReactNode } from 'react';
import { ApiError } from '../api/client';
import { ValidationError } from '../api/schema';
import { dateTime, statusLabels, stageLabels } from '../core/state';
import type { Application } from '../api/generated';
import type { ErrorObject } from 'ajv';
import { fieldLabels } from '../core/forms';
const errorFieldLabels: Record<string, string> = {
  ...fieldLabels,
  '/id': 'Идентификатор программы',
  '/productId': 'Идентификатор программы',
  '/name': 'Название программы',
  '/description': 'Описание программы',
  '/purposeCodes': 'Разрешённые цели',
  '/minAmountRub': 'Минимальная сумма, ₽',
  '/maxAmountRub': 'Максимальная сумма, ₽',
  '/minTermMonths': 'Минимальный срок, мес.',
  '/maxTermMonths': 'Максимальный срок, мес.',
  '/annualRateBps': 'Годовая ставка, б.п.',
  '/minBusinessAgeMonths': 'Минимальный возраст бизнеса, мес.',
  '/maxDebtServiceRatioBps': 'Предельная нагрузка, б.п.',
  '/reason': 'Обоснование',
};
const productIdMessage =
  'От 2 до 64 символов: строчные латинские буквы, цифры и дефис; первый символ — буква.';

function schemaIssue(error: ErrorObject) {
  const path =
    error.keyword === 'required'
      ? error.instancePath +
        '/' +
        String(error.params.missingProperty).replace(/~/g, '~0').replace(/\//g, '~1')
      : error.instancePath;
  let message = 'Проверьте значение поля.';
  switch (error.keyword) {
    case 'required':
      message = 'Заполните поле.';
      break;
    case 'pattern':
      message =
        path === '/id' || path === '/productId' ? productIdMessage : 'Проверьте формат значения.';
      break;
    case 'minimum':
      message = `Укажите значение не меньше ${error.params.limit}.`;
      break;
    case 'maximum':
      message = `Укажите значение не больше ${error.params.limit}.`;
      break;
    case 'minLength':
      message = `Введите не меньше ${error.params.limit} символов.`;
      break;
    case 'maxLength':
      message = `Введите не больше ${error.params.limit} символов.`;
      break;
    case 'type':
      if (error.params.type === 'integer') message = 'Введите целое число.';
      break;
    case 'minItems':
      message = 'Выберите хотя бы одно значение.';
      break;
  }
  return { path, message };
}

export function errorsForFields(error: unknown): { path: string; message: string }[] {
  const items =
    error instanceof ApiError
      ? error.problem?.errors || []
      : error instanceof ValidationError
        ? error.errors.map(schemaIssue)
        : [];
  return items.map((e) => {
    const path = e.path.replace(/^\/changes(?=\/)/, '');
    let message = e.message;
    // Older applications versions return the same English text for both bounds.
    if (message === 'Outside current product limits')
      message =
        path === '/amountRub'
          ? 'Укажите сумму в пределах лимитов выбранного кредита.'
          : path === '/termMonths'
            ? 'Укажите срок в пределах лимитов выбранного кредита.'
            : 'Укажите значение в пределах лимитов выбранного кредита.';
    if (message === 'Invalid product ID') message = productIdMessage;
    if (message === 'Missing field') message = 'Заполните поле.';
    if (message === 'Invalid text')
      message =
        path === '/reason'
          ? 'Введите обоснование от 10 до 2000 символов.'
          : 'Введите непустой текст.';
    if (message === 'Number is outside product limits')
      message =
        path === '/maxAmountRub' || path === '/maxTermMonths'
          ? 'Введите целое число в допустимом диапазоне, не меньше минимального значения.'
          : 'Введите целое число в допустимом диапазоне.';
    if (message === 'Choose at least one purpose') message = 'Выберите хотя бы одну цель кредита.';
    if (message === 'Invalid or repeated purpose')
      message = 'Выберите допустимые цели без повторений.';
    return { path, message };
  });
}
export function ErrorBox({ error, retry }: { error: unknown; retry?: () => void }) {
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (error) ref.current?.focus();
  }, [error]);
  if (!error) return null;
  const fields = errorsForFields(error);
  return (
    <div className="alert error" role="alert" tabIndex={-1} ref={ref}>
      <strong>Не удалось выполнить действие</strong>
      <p>
        {error instanceof ValidationError
          ? 'Проверьте заполненные поля.'
          : error instanceof Error
            ? error.message
            : 'Произошла ошибка. Повторите запрос.'}
      </p>
      {fields?.map((e, i) => (
        <p key={i}>
          {errorFieldLabels[e.path] || 'Поле формы'} — {e.message}
        </p>
      ))}
      {retry && (
        <button className="secondary" onClick={retry}>
          Повторить запрос
        </button>
      )}
    </div>
  );
}
export function Loading() {
  return (
    <div className="loading" role="status">
      <span className="spinner" /> Загружаем данные…
    </div>
  );
}
export function Empty({ children }: { children: ReactNode }) {
  return (
    <div className="empty">
      <span aria-hidden>◇</span>
      <p>{children}</p>
    </div>
  );
}
export function Status({ app }: { app: Pick<Application, 'status' | 'reviewStage'> }) {
  return (
    <span className={'status status-' + app.status.toLowerCase()}>
      <span aria-hidden>●</span>{' '}
      {app.reviewStage ? stageLabels[app.reviewStage] : statusLabels[app.status]}
    </span>
  );
}
export function Modal({
  title,
  children,
  close,
}: {
  title: string;
  children: ReactNode;
  close: () => void;
}) {
  const ref = useRef<HTMLDialogElement>(null);
  useEffect(() => {
    const dialog = ref.current!;
    const trigger = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    dialog.showModal();
    return () => {
      dialog.close();
      // React may remove the dialog before native close can restore focus.
      if (trigger?.isConnected) trigger.focus();
    };
  }, []);
  return (
    <dialog
      ref={ref}
      onCancel={(e) => {
        e.preventDefault();
        close();
      }}
      aria-label={title}
    >
      <div className="dialog-heading">
        <h2>{title}</h2>
        <button aria-label="Закрыть диалог" className="icon-button" onClick={close}>
          ×
        </button>
      </div>
      {children}
    </dialog>
  );
}
export function Deadline({
  at,
  now,
  approximate,
}: {
  at: string;
  now: number;
  approximate: boolean;
}) {
  const ms = Math.max(0, Date.parse(at) - now);
  const hours = Math.floor(ms / 3600000),
    minutes = Math.floor(ms / 60000) % 60,
    seconds = Math.floor(ms / 1000) % 60;
  return (
    <div className={'deadline ' + (!ms ? 'expired' : '')}>
      <span className="eyebrow">Срок действия</span>
      <strong>{dateTime(at)}</strong>
      <small>
        {ms
          ? `Осталось ${hours} ч ${minutes} мин ${seconds} с`
          : 'Срок достигнут. Уточняем состояние у сервера.'}
        {approximate ? ' · ориентировочно' : ''}
      </small>
    </div>
  );
}
