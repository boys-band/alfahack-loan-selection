import type { Application, DataChanges, UpdateDraftRequest } from '../api/generated';
export const fieldLabels: Record<string, string> = {
  '/amountRub': 'Сумма, ₽',
  '/termMonths': 'Срок, мес.',
  '/purpose': 'Цель кредита',
  '/financialData/monthlyRevenueRub': 'Месячная выручка, ₽',
  '/financialData/monthlyExpensesRub': 'Месячные расходы, ₽',
  '/financialData/existingMonthlyDebtPaymentsRub': 'Текущие платежи по долгам, ₽',
  '/additionalInfo/businessDescription': 'Описание бизнеса',
  '/additionalInfo/revenueExplanation': 'Пояснение выручки и расходов',
  '/additionalInfo/refinancedDebtRub': 'Рефинансируемый долг, ₽',
};
export type Fields = Record<string, string>;
export function fieldsOf(
  a: Pick<Application, 'amountRub' | 'termMonths' | 'purpose' | 'financialData' | 'additionalInfo'>,
): Fields {
  return Object.fromEntries(
    Object.keys(fieldLabels).map((path) => {
      const [, parent, child] = path.split('/');
      const v = child ? (a as any)[parent]?.[child] : (a as any)[parent];
      return [path, v == null ? '' : String(v)];
    }),
  );
}
const numberPaths = [
  '/amountRub',
  '/termMonths',
  '/financialData/monthlyRevenueRub',
  '/financialData/monthlyExpensesRub',
  '/financialData/existingMonthlyDebtPaymentsRub',
  '/additionalInfo/refinancedDebtRub',
];
export function fieldValue(path: string, value: string) {
  if (numberPaths.includes(path)) {
    if (!value.trim()) return null;
    const n = Number(value);
    if (!/^\d+$/.test(value) || !Number.isSafeInteger(n))
      throw new Error(`${fieldLabels[path]}: введите целое неотрицательное число`);
    return n;
  }
  return value.trim() || null;
}
export function draftValues(fields: Fields): UpdateDraftRequest {
  const financialData = Object.fromEntries(
    Object.keys(fields)
      .filter((p) => p.startsWith('/financialData/'))
      .map((p) => [p.split('/')[2], fieldValue(p, fields[p])]),
  );
  const additionalInfo = Object.fromEntries(
    Object.keys(fields)
      .filter((p) => p.startsWith('/additionalInfo/') && fields[p].trim())
      .map((p) => [p.split('/')[2], fieldValue(p, fields[p])]),
  );
  return {
    amountRub: fieldValue('/amountRub', fields['/amountRub']) as number | null,
    termMonths: fieldValue('/termMonths', fields['/termMonths']) as number | null,
    purpose: (fields['/purpose'] || null) as Application['purpose'],
    financialData: financialData as Application['financialData'],
    additionalInfo,
  };
}
export function changesOf(fields: Fields, a: Application, required: string[] = []): DataChanges {
  const values = draftValues(fields);
  const original = fieldsOf(a);
  const changed = Object.keys(fields).filter(
    (p) => fields[p] !== original[p] || required.includes(p),
  );
  const result: Record<string, unknown> = {};
  for (const p of changed) {
    if (!fieldLabels[p]) continue;
    const parent = p.split('/')[1];
    result[parent] = (values as any)[parent];
  }
  for (const [k, v] of Object.entries(result)) {
    if (v === null || (k === 'financialData' && Object.values(v as object).some((x) => x === null)))
      throw new Error('После отправки нельзя очищать обязательные поля. Заполните их.');
  }
  return result as DataChanges;
}
