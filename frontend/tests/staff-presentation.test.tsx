import { afterEach, expect, it } from 'vitest';
import { cleanup, render, screen, within } from '@testing-library/react';
import {
  EvaluatedConditions,
  VerificationDetails,
  RiskOverview,
} from '../src/components/application-data';
import type { DecisionResult, VerificationResult } from '../src/api/generated';
import { money } from '../src/core/state';
import examples from '../contract/examples.json';

afterEach(cleanup);
const verification = examples.approved.find((e) => e.operationId === 'createVerification')!.response
  .body as VerificationResult;
const decision = examples.approved.find((e) => e.operationId === 'createDecision')!.response
  .body as DecisionResult;

it('plots the server factor points, including zero, without inventing a new score', () => {
  const risk = structuredClone(decision.risk!);
  risk.factors[risk.factors.length - 1].points = 0;
  render(<RiskOverview risk={risk} />);
  const chart = screen.getByRole('figure', { name: 'Вклад факторов в оценку риска' });
  for (const factor of risk.factors)
    expect(within(chart).getByRole('meter', { name: factor.description })).toHaveAttribute(
      'value',
      String(factor.points),
    );
  expect(screen.getByText(new RegExp(`Оценка риска: ${risk.score} баллов`))).toBeInTheDocument();
});

it('compares source and confirmed facts in a labelled table and identifies changes', () => {
  const data = structuredClone(verification);
  data.facts!.taxDebtRub = data.rawFacts!.taxDebtRub + 15000;
  render(<VerificationDetails verification={data} staff />);
  const table = screen.getByRole('table', { name: 'Сведения источников и подтверждённые факты' });
  const row = within(table).getByRole('row', { name: /Налоговый долг/ });
  const cells = within(row).getAllByRole('cell');
  expect(cells[0]).toHaveTextContent(money(data.rawFacts!.taxDebtRub).replace(/\s/g, ' '));
  expect(cells[1]).toHaveTextContent(money(data.facts!.taxDebtRub).replace(/\s/g, ' '));
  expect(within(row).getByText('Уточнено')).toBeInTheDocument();
  expect(table).not.toHaveTextContent(/taxDebtRub|rawFacts|registryStatus/);
});

it('shows missing source facts explicitly instead of inventing zero values', () => {
  render(
    <VerificationDetails
      verification={{ ...verification, rawFacts: null, facts: null, confirmed: false }}
      staff
    />,
  );
  const table = screen.getByRole('table', { name: 'Сведения источников и подтверждённые факты' });
  expect(within(table).getAllByText('Нет сведений')).toHaveLength(12);
  expect(screen.getByText('Сведения требуют уточнения.')).toBeInTheDocument();
});

it('compares assessed candidates without losing rates, eligibility or rejection reasons', () => {
  const data = structuredClone(decision);
  const first = data.evaluatedTerms[0];
  data.selectedTermsId = first.id;
  data.evaluatedTerms.push({
    ...first,
    id: crypto.randomUUID(),
    amountRub: first.amountRub - 100000,
    eligible: false,
    risk: { ...first.risk, debtServiceRatioBps: null },
    reasons: [
      {
        ...first.reasons[0],
        code: 'DEBT_BURDEN_LIMIT',
        message: 'Нагрузка превышает лимит продукта.',
      },
    ],
  });
  render(<EvaluatedConditions decision={data} />);
  const table = screen.getByRole('table', { name: 'Сравнение оценённых условий' });
  expect(within(table).getAllByRole('row')).toHaveLength(3);
  expect(within(table).getByText('Выбран')).toBeInTheDocument();
  expect(within(table).getByText('Не подходит')).toBeInTheDocument();
  expect(within(table).getByText('Не рассчитана')).toBeInTheDocument();
  expect(within(table).getByText('Нагрузка превышает лимит продукта.')).toBeInTheDocument();
  expect(within(table).getAllByText(`${first.annualRateBps / 100}% годовых`)).toHaveLength(2);
});
