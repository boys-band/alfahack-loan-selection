import { defineConfig } from '@playwright/test';
import { existsSync } from 'node:fs';

// This suite intentionally uses the already running real Docker stack, never Vite/MSW.
export default defineConfig({
  testDir: './e2e-real',
  fullyParallel: false,
  workers: 1,
  retries: 0,
  timeout: 60000,
  expect: { timeout: 20000 },
  outputDir: 'test-results/real',
  reporter: [['list'], ['html', { outputFolder: 'playwright-report/real', open: 'never' }]],
  use: {
    baseURL: process.env.E2E_BASE_URL || 'http://localhost:8080',
    viewport: { width: 1440, height: 900 },
    headless: true,
    serviceWorkers: 'block',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    launchOptions: {
      executablePath:
        process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE ||
        (existsSync('/usr/bin/google-chrome') ? '/usr/bin/google-chrome' : undefined),
      args: ['--no-sandbox'],
    },
  },
});
