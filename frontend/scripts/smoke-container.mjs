import { execFileSync } from 'node:child_process';
import { existsSync } from 'node:fs';
import { chromium } from '@playwright/test';

const containers = [];
const browser = await chromium.launch({
  executablePath:
    process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE ||
    (existsSync('/usr/bin/google-chrome') ? '/usr/bin/google-chrome' : undefined),
  args: ['--no-sandbox'],
});
try {
  for (const mock of [false, true]) {
    const id = execFileSync(
      'docker',
      [
        'run',
        '--rm',
        '-d',
        '-p',
        '127.0.0.1::3000',
        '-e',
        'API_BASE_URL=/api',
        '-e',
        `MOCK_API=${mock}`,
        process.env.FRONTEND_IMAGE || 'alfa-frontend:local',
      ],
      { encoding: 'utf8' },
    ).trim();
    containers.push(id);
    const base =
      'http://' + execFileSync('docker', ['port', id, '3000/tcp'], { encoding: 'utf8' }).trim();
    for (let n = 0; n < 30; n++) {
      try {
        await fetch(base + '/config.js');
        break;
      } catch {
        await new Promise((r) => setTimeout(r, 100));
      }
    }
    const config = await (await fetch(base + '/config.js')).text();
    if (!config.includes(`"MOCK_API":${mock}`)) throw new Error('Runtime config mismatch');
    for (const path of ['/assets/missing.js', '/api/v2/missing']) {
      if ((await fetch(base + path, { headers: { Accept: 'text/html' } })).status !== 404)
        throw new Error('Expected 404: ' + path);
    }
    const nested = await fetch(base + '/applications/example/history', {
      headers: { Accept: 'text/html' },
    });
    if (nested.status !== 200 || !(await nested.text()).includes('<div id="root">'))
      throw new Error('SPA fallback');
    const context = await browser.newContext({ viewport: { width: 1440, height: 900 } });
    const page = await context.newPage();
    const errors = [],
      requested = [];
    page.on('pageerror', (e) => errors.push(String(e)));
    page.on('request', (r) => requested.push(r.url()));
    await page.goto(base + '/demo-access');
    await page.getByRole('heading', { name: 'Войти в кабинет' }).waitFor();
    if (mock) {
      await page.getByLabel('Демотокен', { exact: true }).fill('demo-customer');
      await page.getByRole('button', { name: 'Войти →', exact: true }).click();
      await page.getByRole('heading', { name: 'Мои заявки', exact: true }).waitFor();
      await page.reload();
      await page.getByRole('heading', { name: 'Мои заявки', exact: true }).waitFor();
    } else {
      if (await page.evaluate(() => !!window.__MOCK__))
        throw new Error('Mocks active in real mode');
      if (requested.some((x) => /\/assets\/browser-/.test(x)))
        throw new Error('Mock chunk loaded in real mode');
    }
    if (errors.length) throw new Error(errors.join('\n'));
    console.log(
      JSON.stringify({
        mode: mock ? 'mock' : 'real',
        runtimeConfig: true,
        spaReload: true,
        missingAsset404: true,
        browserErrors: 0,
      }),
    );
    await context.close();
  }
} finally {
  await browser.close();
  for (const id of containers) execFileSync('docker', ['rm', '-f', id], { stdio: 'ignore' });
}
