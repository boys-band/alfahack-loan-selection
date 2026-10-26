import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { App } from '../src/app';
import { SessionProvider } from '../src/core/session';
import { Api } from '../src/api/client';
import { SavedDataDetails } from '../src/components/application-data';
import type { Application, SubmittedData } from '../src/api/generated';
import examples from '../contract/examples.json';

const approved = examples.approved.find(
  (e) =>
    e.operationId === 'getApplication' && (e.response.body as Application).status === 'OFFER_READY',
)!.response.body as Application;
const privateReason = 'Внутренняя причина кредитного решения';
const privateFactor = 'Внутренний фактор скоринга';
const privateFinding = 'Внутреннее замечание проверки';
const privateAudit = 'Внутреннее обоснование сотрудника';

beforeEach(() => sessionStorage.clear());
afterEach(cleanup);

for (const role of ['customer', 'staff'] as const)
  for (const status of ['REJECTED', 'OFFER_READY'] as const)
    it(`${role} sees the appropriate decision detail for ${status}`, async () => {
      const app = structuredClone(approved);
      app.status = status;
      app.nextActionHint = status === 'REJECTED' ? privateReason : 'Предложение готово к принятию';
      app.decision!.outcome = status === 'REJECTED' ? 'REJECTED' : 'APPROVED';
      app.decision!.kind = 'MANUAL';
      if (status === 'REJECTED') app.decision!.offer = null;
      app.decision!.reasons[0].message = privateReason;
      app.decision!.risk!.factors[0].description = privateFactor;
      app.verification!.findings = [
        { source: 'BKI', code: 'INTERNAL', severity: 'INFO', message: privateFinding },
      ];
      app.decision!.staffAudit = {
        actionId: crypto.randomUUID(),
        staffId: app.customerId,
        reason: privateAudit,
        decidedAt: app.updatedAt,
      };
      sessionStorage.setItem(
        'alfa.session',
        JSON.stringify({ role, token: 'synthetic', identity: role }),
      );
      vi.spyOn(Api.prototype, 'call').mockResolvedValue({
        data: app,
        status: 200,
        etag: '"tag"',
        requestId: null,
        date: null,
      });
      render(
        <MemoryRouter
          initialEntries={[
            role === 'staff' ? `/staff/applications/${app.id}` : `/applications/${app.id}`,
          ]}
        >
          <SessionProvider>
            <App />
          </SessionProvider>
        </MemoryRouter>,
      );
      await screen.findByRole('heading', { name: app.company.legalName });
      if (role === 'customer') {
        for (const text of [privateReason, privateFactor, privateFinding, privateAudit])
          expect(screen.queryByText(text)).toBeNull();
        expect(screen.queryByRole('heading', { name: 'Факторы оценки' })).toBeNull();
        if (status === 'REJECTED')
          expect(screen.getByText('По результатам рассмотрения кредит не одобрен.')).toBeTruthy();
        else expect(screen.getByRole('heading', { name: 'Условия финансирования' })).toBeTruthy();
      } else {
        expect(screen.getByText(privateFactor)).toBeTruthy();
        expect(screen.getByText(privateFinding)).toBeTruthy();
        expect(screen.getByText(privateAudit, { exact: false })).toBeTruthy();
      }
    });

for (const staff of [false, true])
  it(`saved data ${staff ? 'retains' : 'hides'} staff verification and rationale`, () => {
    const data = structuredClone(
      (
        examples.approved.find((e) => e.operationId === 'createVerification')!.request.body as {
          data: SubmittedData;
        }
      ).data,
    ) as SubmittedData;
    data.staffAttestation = {
      caseActionId: approved.id,
      staffId: approved.customerId,
      reason: privateAudit,
      recordedAt: approved.updatedAt,
      confirmedFacts: approved.verification!.facts!,
    };
    render(<SavedDataDetails data={data} {...(staff ? { staff: true } : {})} />);
    expect(screen.getByText('Дано согласие на обработку персональных данных.')).toBeTruthy();
    if (staff) {
      expect(screen.getByText(privateAudit)).toBeTruthy();
      expect(screen.getByText('Балл кредитной истории')).toBeTruthy();
    } else {
      expect(screen.queryByText(privateAudit)).toBeNull();
      expect(screen.queryByText('Балл кредитной истории')).toBeNull();
    }
  });
