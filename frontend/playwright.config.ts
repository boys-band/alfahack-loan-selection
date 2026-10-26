import { defineConfig } from '@playwright/test';
import { existsSync } from 'node:fs';
export default defineConfig({
  testDir: './e2e',
  outputDir: 'test-results/mock',
  reporter: [['list'], ['html', { outputFolder: 'playwright-report/mock', open: 'never' }]],
  fullyParallel: false,
  workers: 1,
  timeout: 45000,
  expect: { timeout: 15000 },
  use: {
    baseURL: 'http://localhost:3000',
    viewport: { width: 1440, height: 900 },
    headless: true,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    launchOptions: {
      executablePath:
        process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE ||
        (existsSync('/usr/bin/google-chrome') ? '/usr/bin/google-chrome' : undefined),
      args: ['--no-sandbox'],
    },
  },
  webServer: {
    command: 'npm run dev',
    env: { MOCK_API: 'true' },
    url: 'http://localhost:3000',
    reuseExistingServer: false,
    timeout: 30000,
  },
});
