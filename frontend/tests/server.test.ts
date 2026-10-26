// @vitest-environment node
import { it, expect } from 'vitest';
import { spawn } from 'node:child_process';
import { createServer } from 'node:net';
import { mkdtemp, writeFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { once } from 'node:events';
it('serves runtime configuration and nested SPA routes; missing assets and API paths return 404', async () => {
  const root = await mkdtemp(join(tmpdir(), 'alfa-frontend-'));
  await writeFile(join(root, 'index.html'), '<html lang="ru">cabinet</html>');
  const probe = createServer();
  probe.listen(0, '127.0.0.1');
  await once(probe, 'listening');
  const port = (probe.address() as { port: number }).port;
  await new Promise<void>((r) => probe.close(() => r()));
  const child = spawn(process.execPath, ['server.mjs'], {
    cwd: new URL('..', import.meta.url),
    env: {
      ...process.env,
      PORT: String(port),
      STATIC_ROOT: root,
      API_BASE_URL: '/gateway',
      MOCK_API: 'true',
    },
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  try {
    await once(child.stdout!, 'data');
    const base = 'http://127.0.0.1:' + port;
    const config = await fetch(base + '/config.js');
    expect(await config.text()).toContain('"API_BASE_URL":"/gateway","MOCK_API":true');
    expect(config.headers.get('cache-control')).toBe('no-store');
    const nested = await fetch(base + '/applications/example/agreement', {
      headers: { Accept: 'text/html' },
    });
    expect(nested.status).toBe(200);
    expect(await nested.text()).toContain('cabinet');
    for (const path of ['/assets/missing.js', '/missing.css', '/gateway/v2/applications']) {
      const response = await fetch(base + path, { headers: { Accept: 'text/html' } });
      expect(response.status).toBe(404);
      expect(await response.text()).not.toContain('cabinet');
    }
  } finally {
    child.kill();
    await once(child, 'exit');
    await rm(root, { recursive: true, force: true });
  }
});
