import { it, expect } from 'vitest';
import { fieldsOf, draftValues, changesOf } from '../src/core/forms';
import { statusLabels, safeReturn } from '../src/core/state';
import examples from '../contract/examples.json';
import type { Application } from '../src/api/generated';
const draft = examples.approved.find((e) => e.operationId === 'createApplication')!.response
  .body as Application;
it('keeps empty numeric values null, supports zero, and preserves neighbors in replacement objects', () => {
  const a = {
    ...draft,
    additionalInfo: {
      businessDescription: 'Мастерская',
      revenueExplanation: 'Исходное объяснение',
    },
  };
  const fields = fieldsOf(a);
  fields['/financialData/monthlyExpensesRub'] = '0';
  fields['/additionalInfo/businessDescription'] = 'Новое описание';
  const changes = changesOf(fields, a);
  expect(changes.additionalInfo).toEqual({
    businessDescription: 'Новое описание',
    revenueExplanation: 'Исходное объяснение',
  });
  expect(changes.financialData?.monthlyExpensesRub).toBe(0);
  expect(changes.financialData?.monthlyRevenueRub).toBe(a.financialData!.monthlyRevenueRub);
  fields['/amountRub'] = '';
  expect(draftValues(fields).amountRub).toBeNull();
  expect(() => changesOf(fields, a)).toThrow();
});
it('all 15 statuses have text labels; unsafe return URLs are rejected', () => {
  expect(Object.keys(statusLabels)).toHaveLength(15);
  expect(safeReturn('//attacker.test')).toBeNull();
  expect(safeReturn('https://attacker.test')).toBeNull();
  expect(safeReturn('/applications/123/history')).toBe('/applications/123/history');
});
