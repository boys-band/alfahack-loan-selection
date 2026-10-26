declare global {
  interface Window {
    __CONFIG__?: { API_BASE_URL?: string; MOCK_API?: boolean };
  }
}
const raw = window.__CONFIG__ || {};
const base = raw.API_BASE_URL || '/api';
if (!base.startsWith('/') || base.startsWith('//') || /[?#]/.test(base))
  throw new Error('API_BASE_URL должен быть same-origin путём gateway, например /api');
export const config = { apiBase: base.replace(/\/$/, ''), mock: raw.MOCK_API === true };
