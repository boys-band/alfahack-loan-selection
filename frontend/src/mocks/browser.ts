import { setupWorker } from 'msw/browser';
import { http, HttpResponse, delay } from 'msw';
import { MockBank } from './bank';
import { MockAdminCatalog } from './admin-catalog';
import { config } from '../core/config';
export const bank = new MockBank(localStorage);
const admin = new MockAdminCatalog(bank);
declare global {
  interface Window {
    __MOCK__?: MockBank;
  }
}
export async function startMocks() {
  window.__MOCK__ = bank;
  const worker = setupWorker(
    http.all(window.location.origin + config.apiBase + '/v2/*', async ({ request }) => {
      const body = request.method === 'GET' ? undefined : (await request.text()) || undefined;
      try {
        const r = request.url.includes('/v2/admin/products')
          ? await admin.handle(request.method, request.url, body, request.headers)
          : await bank.handle(request.method, request.url, body, request.headers);
        if (r.delay) await delay(r.delay);
        if (r.lost) return HttpResponse.error();
        return HttpResponse.json(r.body as any, { status: r.status, headers: r.headers });
      } catch {
        return HttpResponse.json(
          { detail: 'HTTP-стаб обнаружил нарушение схемы' },
          { status: 500 },
        );
      }
    }),
  );
  await worker.start({ onUnhandledRequest: 'bypass', quiet: true });
  setInterval(() => bank.tick(), 100);
}
