import { it, expect } from 'vitest';
import { validate } from '../src/api/schema';
import { describe as operation } from '../src/api/client';
import ops from '../contract/operations.json';
import examples from '../contract/examples.json';
import type { Operation } from '../src/api/client';
it('validates every public example request/response against the shipped current schemas', () => {
  let count = 0;
  for (const exchanges of Object.values(examples))
    for (const exchange of exchanges) {
      if (!(exchange.operationId in ops)) continue;
      const op = ops[exchange.operationId as Operation];
      if ('body' in exchange.request && op.body) validate(op.body, exchange.request.body);
      if (exchange.response.status < 400) validate(op.response, exchange.response.body);
      else validate('Problem', exchange.response.body);
      count++;
    }
  expect(count).toBeGreaterThan(40);
});
it('covers all 29 public operations and encodes opaque cursor', () => {
  expect(Object.keys(ops)).toHaveLength(29);
  expect(operation('listApplications', { cursor: 'a/b +', limit: 20 }).path).toBe(
    '/v2/applications?limit=20&cursor=a%2Fb+%2B',
  );
});
it('distinguishes null/omission and enforces exact staff DTO', () => {
  validate('UpdateDraftRequest', { amountRub: null });
  expect(() => validate('DataChanges', { amountRub: null })).toThrow();
  expect(() => validate('DataChanges', { productId: 'working-capital' })).toThrow();
  expect(() =>
    validate('StaffReviewActionRequest', {
      action: 'REJECT',
      reason: 'Обоснованный отказ',
      requirements: [],
    }),
  ).toThrow();
  validate('StaffReviewActionRequest', {
    action: 'REJECT',
    reason: 'Обоснованный отказ',
    requirements: [],
    termsId: null,
    changes: null,
    factsResolution: null,
  });
});
