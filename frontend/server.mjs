import { createServer } from 'node:http';
import { readFile, stat } from 'node:fs/promises';
import { resolve, extname, sep } from 'node:path';
const root = resolve(process.env.STATIC_ROOT || 'dist');
const base = process.env.API_BASE_URL || '/api';
if (!base.startsWith('/') || base.startsWith('//') || /[?#\r\n]/.test(base))
  throw new Error('API_BASE_URL must be a same-origin gateway path');
const config = { API_BASE_URL: base, MOCK_API: process.env.MOCK_API === 'true' };
const types = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'application/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.ico': 'image/x-icon',
  '.woff2': 'font/woff2',
};
createServer(async (req, res) => {
  res.setHeader('X-Content-Type-Options', 'nosniff');
  if (!['GET', 'HEAD'].includes(req.method)) {
    res.writeHead(405, { Allow: 'GET, HEAD' });
    res.end();
    return;
  }
  try {
    const path = decodeURIComponent(new URL(req.url, 'http://localhost').pathname);
    if (path === '/config.js') {
      res.writeHead(200, { 'Content-Type': types['.js'], 'Cache-Control': 'no-store' });
      res.end(
        req.method === 'HEAD' ? undefined : 'window.__CONFIG__=' + JSON.stringify(config) + ';',
      );
      return;
    }
    let file = resolve(root, '.' + path);
    if (!file.startsWith(root + sep) && file !== root) {
      res.writeHead(404);
      res.end();
      return;
    }
    try {
      if (!(await stat(file)).isFile()) throw new Error();
    } catch {
      // Only UI navigation gets SPA fallback. Missing assets/API never return index.html.
      if (
        extname(path) ||
        path.startsWith('/assets/') ||
        path.startsWith(base + '/') ||
        !(req.headers.accept || '').includes('text/html')
      ) {
        res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
        res.end('Not found');
        return;
      }
      file = resolve(root, 'index.html');
    }
    const bytes = await readFile(file);
    res.writeHead(200, {
      'Content-Type': types[extname(file)] || 'application/octet-stream',
      'Cache-Control': path.startsWith('/assets/')
        ? 'public, max-age=31536000, immutable'
        : 'no-store',
      'Content-Length': bytes.length,
    });
    res.end(req.method === 'HEAD' ? undefined : bytes);
  } catch {
    res.writeHead(400);
    res.end('Bad request');
  }
}).listen(Number(process.env.PORT || 3000), '0.0.0.0', () =>
  console.log('Frontend listening on 0.0.0.0:' + Number(process.env.PORT || 3000)),
);
