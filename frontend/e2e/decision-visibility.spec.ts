import { expect, test, type Page } from '@playwright/test';

async function login(page: Page, staff = false) {
  await page.goto('/demo-access');
  if (staff) await page.getByLabel('Сотрудник', { exact: true }).check();
  await page.getByLabel('Демотокен', { exact: true }).fill(staff ? 'demo-staff' : 'demo-customer');
  await page.getByRole('button', { name: 'Войти →', exact: true }).click();
  await expect(page).toHaveURL(staff ? /staff\/manual-reviews$/ : /applications$/);
}

for (const status of ['REJECTED', 'OFFER_READY'] as const)
  test(`customer sees only the public ${status} result while staff keeps assessment and audit`, async ({
    page,
  }) => {
    await login(page);
    const id = await page.evaluate((status) => {
      const bank = window.__MOCK__!;
      const id = bank.seed('approved', 'OFFER_READY');
      const app = bank.state.apps[id];
      app.status = status;
      app.nextActionHint =
        status === 'REJECTED' ? 'Закрытая причина скоринга' : 'Предложение готово';
      app.decision!.outcome = status === 'REJECTED' ? 'REJECTED' : 'APPROVED';
      app.decision!.kind = 'MANUAL';
      app.decision!.reasons[0].message = 'Закрытая причина скоринга';
      app.decision!.risk!.factors[0].description = 'Закрытый фактор скоринга';
      app.verification!.findings = [
        {
          source: 'BKI',
          code: 'INTERNAL',
          severity: 'INFO',
          message: 'Закрытое замечание проверки',
        },
      ];
      app.decision!.staffAudit = {
        actionId: crypto.randomUUID(),
        staffId: app.customerId,
        reason: 'Закрытое обоснование сотрудника',
        decidedAt: app.updatedAt,
      };
      if (status === 'REJECTED') {
        app.decision!.offer = null;
        app.closureReason = 'CREDIT_REJECTED';
        app.availableActions = [];
        app.actionDeadline = null;
        app.deadlineKind = null;
      }
      const event = bank.state.timelines[id][0];
      event.status = status;
      event.dataVersion = 1;
      event.eventType = 'STATE_CHANGED';
      event.reason = 'Закрытая история решения';
      bank.state.versions[id][0].data.staffAttestation = {
        caseActionId: crypto.randomUUID(),
        staffId: app.customerId,
        reason: 'Закрытое уточнение сотрудника',
        recordedAt: app.updatedAt,
        confirmedFacts: app.verification!.facts!,
      };
      bank.checkpoint();
      return id;
    }, status);

    const customerPage = page.locator('main');
    const privateText = /Закрыт|Факторы оценки|Оценка риска|Балл кредитной истории|Налоговый долг/;
    await page.goto('/applications/' + id);
    await expect(page.getByRole('heading', { name: 'ООО «Демо Мастерская»' })).toBeVisible();
    await expect(customerPage).not.toContainText(privateText);
    if (status === 'REJECTED') {
      await expect(page.getByText('По результатам рассмотрения кредит не одобрен.')).toBeVisible();
      await page.screenshot({ path: 'test-results/customer-decision.png', fullPage: true });
    } else {
      await expect(page.getByRole('heading', { name: 'Условия финансирования' })).toBeVisible();
      await expect(page.getByText('Платёж в месяц', { exact: true })).toBeVisible();
    }
    await page.reload();
    await expect(page.getByRole('heading', { name: 'ООО «Демо Мастерская»' })).toBeVisible();
    await expect(customerPage).not.toContainText(privateText);
    await page.getByRole('link', { name: 'История и версии ↗' }).click();
    await expect(page.getByRole('link', { name: 'Снимок данных 1 →' }).first()).toBeVisible();
    await expect(customerPage).not.toContainText(privateText);
    await page.getByRole('link', { name: 'Снимок данных 1 →' }).first().click();
    await expect(page.getByText('Дано согласие на обработку персональных данных.')).toBeVisible();
    await expect(customerPage).not.toContainText(privateText);
    await page.reload();
    await expect(page.getByText('Дано согласие на обработку персональных данных.')).toBeVisible();
    await expect(customerPage).not.toContainText(privateText);

    await page.getByRole('button', { name: 'Выйти из кабинета ↗' }).click();
    await login(page, true);
    await page.goto('/staff/applications/' + id);
    await expect(page.getByText('Закрытый фактор скоринга')).toBeVisible();
    await expect(page.getByText('Закрытое замечание проверки')).toBeVisible();
    await expect(page.getByText('Закрытое обоснование сотрудника', { exact: false })).toBeVisible();
    await page.getByRole('link', { name: 'История и версии ↗' }).click();
    await expect(page.getByText('Закрытая история решения')).toBeVisible();
    await page.getByRole('link', { name: 'Снимок данных 1 →' }).first().click();
    await expect(page.getByText('Закрытое уточнение сотрудника')).toBeVisible();
    await expect(page.getByText('Балл кредитной истории')).toBeVisible();
  });
