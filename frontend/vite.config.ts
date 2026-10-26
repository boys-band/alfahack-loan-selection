import { defineConfig, loadEnv } from 'vite';
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '');
  return {
    server: {
      host: '0.0.0.0',
      port: 3000,
      strictPort: true,
      proxy: {
        '/api': { target: env.API_PROXY_TARGET || 'http://localhost:8080', changeOrigin: true },
      },
    },
    plugins: [
      {
        name: 'runtime-config',
        configureServer(server) {
          server.middlewares.use('/config.js', (_req, res) => {
            res.setHeader('Content-Type', 'application/javascript');
            res.setHeader('Cache-Control', 'no-store');
            res.end(
              `window.__CONFIG__=${JSON.stringify({ API_BASE_URL: env.API_BASE_URL || '/api', MOCK_API: env.MOCK_API === 'true' })};`,
            );
          });
        },
      },
    ],
  };
});
