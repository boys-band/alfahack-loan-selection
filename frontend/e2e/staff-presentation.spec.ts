import { expect, test } from '@playwright/test';

for (const width of [1280, 1440])
  test(`staff compares facts, candidates and factor chart at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    await page.goto('/demo-access');
    await page.getByLabel('Сотрудник', { exact: true }).check();
    await page.getByLabel('Демотокен', { exact: true }).fill('demo-staff');
    await page.getByRole('button', { name: 'Войти →', exact: true }).click();
    await expect(page).toHaveURL(/staff\/manual-reviews$/);
    const sample = await page.evaluate(() => {
      const bank = window.__MOCK__!;
      const id = bank.seed('manual', 'MANUAL_REVIEW');
      const app = bank.state.apps[id];
      app.verification!.facts!.taxDebtRub = app.verification!.rawFacts!.taxDebtRub + 15000;
      const decision = app.decision!;
      decision.selectedTermsId = decision.evaluatedTerms[0].id;
      bank.checkpoint();
      return { id, factors: decision.risk!.factors, candidates: decision.evaluatedTerms.length };
    });
    await page.goto('/staff/applications/' + sample.id);
    await expect(page.getByRole('heading', { name: 'ООО «Демо Мастерская»' })).toBeVisible();
    const chart = page.getByRole('figure', { name: 'Вклад факторов в оценку риска' });
    await expect(chart).toBeVisible();
    for (const factor of sample.factors)
      await expect(chart.getByRole('meter', { name: factor.description })).toHaveAttribute(
        'value',
        String(factor.points),
      );
    const candidates = page.getByRole('table', { name: 'Сравнение оценённых условий' });
    await expect(candidates).toBeVisible();
    await expect(candidates.getByRole('row')).toHaveCount(sample.candidates + 1);
    await expect(candidates.getByText('Выбран', { exact: true })).toBeVisible();
    const facts = page.getByRole('table', { name: 'Сведения источников и подтверждённые факты' });
    await expect(facts).toBeVisible();
    await expect(facts.getByRole('row', { name: /Налоговый долг/ })).toContainText('Уточнено');
    await expect(page.locator('pre')).toHaveCount(0);
    await expect(page.locator('main')).not.toContainText(
      /rawFacts|assessmentId|evaluatedTerms|MANUAL_REVIEW/,
    );
    await expect(
      page.getByText('Автоматическое решение · Требуется рассмотрение сотрудником'),
    ).toBeVisible();
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(
      true,
    );
    await page.screenshot({ path: `test-results/staff-presentation-${width}.png`, fullPage: true });
    await page.getByLabel('Действие сотрудника').selectOption('APPROVE');
    await expect(page.getByLabel('Оценённые допустимые условия')).toBeVisible();
  });
