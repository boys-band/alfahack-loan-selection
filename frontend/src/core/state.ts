import type {
  Application,
  ManualReviewCase,
  ProcessingStep,
  TimelineEntry,
} from '../api/generated';
export const statusLabels: Record<Application['status'], string> = {
  DRAFT: 'Черновик',
  SUBMITTED: 'Заявка отправлена',
  VALIDATION: 'Проверяем данные заявки',
  IN_REVIEW: 'Заявка на рассмотрении',
  NEEDS_INFO: 'Нужны дополнительные сведения',
  MANUAL_REVIEW: 'Рассматривает сотрудник',
  OFFER_READY: 'Предложение готово',
  OFFER_ACCEPTED: 'Готовим договор',
  AWAITING_SIGNATURE: 'Договор готов к подписи',
  SIGNED: 'Договор подписан',
  DISBURSING: 'Подтверждаем зачисление',
  DISBURSED: 'Кредит выдан',
  REJECTED: 'Кредит не одобрен',
  CANCELLED: 'Заявка отменена',
  EXPIRED: 'Срок истёк',
};
export const stageLabels = {
  ENRICHMENT: 'Получаем и сверяем сведения',
  SCORING: 'Оцениваем риск и условия',
};
export const manualReviewStatusLabels: Record<ManualReviewCase['status'], string> = {
  OPEN: 'Ожидает решения',
  RESOLVED: 'Решение принято',
  CLOSED: 'Дело закрыто',
};
const processingStepLabels: Record<ProcessingStep, string> = {
  VALIDATION: 'Проверка данных заявки',
  ENRICHMENT: 'Получение и сверка сведений',
  SCORING: 'Оценка риска и условий',
  MANUAL_CASE: 'Создание дела ручного рассмотрения',
  MANUAL_ACTION: 'Обработка решения сотрудника',
  AGREEMENT: 'Подготовка договора',
  SIGNATURE: 'Подписание договора',
  PAYOUT_VALIDATION: 'Проверка реквизитов зачисления',
  DISBURSEMENT: 'Зачисление кредита',
  CLOSURE: 'Завершение заявки',
};
const workflowLabels: Record<string, string | undefined> = {
  ...processingStepLabels,
  ...statusLabels,
  ...stageLabels,
};
export function workflowText(text: string) {
  const code = text
    .trim()
    .replace(/[\s-]+/g, '_')
    .toUpperCase();
  return workflowLabels[code] ?? text;
}
export function timelineReason(entry: Pick<TimelineEntry, 'actor' | 'reason'>) {
  // Only system templates contain technical codes; authored reasons stay verbatim.
  if (entry.actor.type !== 'SYSTEM') return entry.reason;
  const step = /^Заверш[её]н этап ([A-Z_]+)$/.exec(entry.reason)?.[1];
  if (step && Object.hasOwn(processingStepLabels, step))
    return `Завершён этап: ${processingStepLabels[step as ProcessingStep]}`;
  const deadline = /^Ист[её]к срок действия (INFO|OFFER|SIGNATURE)$/.exec(entry.reason)?.[1];
  const deadlines: Record<string, string> = {
    INFO: 'ответа на дозапрос',
    OFFER: 'принятия предложения',
    SIGNATURE: 'подписания договора',
  };
  if (deadline) return `Истёк срок ${deadlines[deadline]}`;
  return workflowText(entry.reason);
}
export const purposeLabels = {
  WORKING_CAPITAL: 'Оборотные средства',
  EQUIPMENT: 'Оборудование',
  REFINANCING: 'Рефинансирование',
};
export const finalStates = ['DISBURSED', 'REJECTED', 'CANCELLED', 'EXPIRED'];
export function pollingDelay(app?: Application, failures = 0, retryAfter = 0) {
  if (app && finalStates.includes(app.status)) return null;
  const active =
    (app?.processing && app.processing.state !== 'PAUSED') ||
    (app &&
      ['SUBMITTED', 'VALIDATION', 'IN_REVIEW', 'OFFER_ACCEPTED', 'DISBURSING'].includes(
        app.status,
      ));
  return Math.max(
    retryAfter,
    failures ? Math.min(15000, 2000 * 2 ** failures) : active ? 2000 : 15000,
  );
}
export function deadline(app: Application) {
  return app.status === 'AWAITING_SIGNATURE'
    ? app.agreement?.signBy || app.actionDeadline
    : app.actionDeadline;
}
export const money = (n: number | null | undefined) =>
  n == null
    ? 'Не указано'
    : new Intl.NumberFormat('ru-RU', {
        style: 'currency',
        currency: 'RUB',
        maximumFractionDigits: 0,
      }).format(n);
export const dateTime = (d: string) =>
  new Intl.DateTimeFormat('ru-RU', {
    dateStyle: 'medium',
    timeStyle: 'short',
    timeZone: 'Europe/Moscow',
  }).format(new Date(d)) + ' МСК';
export function safeReturn(path: unknown) {
  return typeof path === 'string' &&
    /^\/(applications|products|staff|admin)(\/|\?|$)/.test(path) &&
    !path.startsWith('//')
    ? path
    : null;
}
