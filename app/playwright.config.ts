import { defineConfig } from '@playwright/test';
const previewPort = process.env.MM_E2E_PREVIEW_PORT ?? '4173';
const devPort = process.env.MM_E2E_DEV_PORT ?? '5173';
export default defineConfig({
  testDir: './e2e', workers: 1,
  use: { baseURL: `http://127.0.0.1:${previewPort}`, serviceWorkers: 'allow' },
  webServer: [
    { command: `node node_modules/vite/bin/vite.js preview --host 127.0.0.1 --port ${previewPort} --outDir ${process.env.MM_E2E_DIST ?? 'dist'}`,
      url: `http://127.0.0.1:${previewPort}`, reuseExistingServer: false },
    { command: `node node_modules/vite/bin/vite.js --host 127.0.0.1 --port ${devPort}`,
      url: `http://127.0.0.1:${devPort}`, reuseExistingServer: false },
  ],
});
