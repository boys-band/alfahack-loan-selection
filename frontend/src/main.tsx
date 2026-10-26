import { createRoot } from 'react-dom/client';
import { createBrowserRouter, RouterProvider } from 'react-router-dom';
import { SessionProvider } from './core/session';
import { App } from './app';
import { config } from './core/config';
import './style.css';
async function main() {
  if (config.mock) {
    const { startMocks } = await import('./mocks/browser');
    await startMocks();
  } else if ('serviceWorker' in navigator) {
    const registrations = await navigator.serviceWorker.getRegistrations();
    for (const r of registrations)
      if (r.active?.scriptURL.endsWith('/mockServiceWorker.js')) await r.unregister();
  }
  createRoot(document.getElementById('root')!).render(
    <SessionProvider>
      <RouterProvider router={createBrowserRouter([{ path: '*', element: <App /> }])} />
    </SessionProvider>,
  );
}
void main().catch(() => {
  document.getElementById('root')!.textContent =
    'Не удалось запустить кабинет. Проверьте runtime-конфигурацию и обновите страницу.';
});
