import { existsSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { pwaBrandAssets } from './src/assets/pwa.ts';
import { defineConfig, loadEnv } from 'vite';
import { buildClockPolicy } from './scripts/security-clock-policy.mjs';
import react from '@vitejs/plugin-react';
import { VitePWA } from 'vite-plugin-pwa';

const backend = { target: process.env.MIEZMERKER_BACKEND_URL ?? 'http://127.0.0.1:8080', changeOrigin: false };
// Proxy and Workbox match URLs including query strings, including bare /admin?… .
// Server-rendered Admin owns this namespace in development and preview too.
const proxy = { '/api': backend, '^/admin(?:/|\\?|$)': backend,
  '^/(?:admin-(?:shared-care|temporal-times)\\.js|shared-care\\.(?:js|css)|error-codes\\.generated\\.js)$': backend };
export default defineConfig(({ mode, isPreview }) => {
  // Preview serves a previously built bundle. Its shell environment neither
  // selects nor validates the policy embedded in that artifact.
  const policy = isPreview ? undefined
    : buildClockPolicy(mode, loadEnv(mode, process.cwd(), 'VITE_').VITE_MM_SECURITY_CLOCK_POLICY);
  console.info(policy ? `PWA security_clock_policy=${policy} mode=${mode} (compiled into bundle)`
    : 'PWA preview serves an existing bundle; see Zeit-Policy / Diagnose for its compiled policy and build ID.');
  // HTTPS is opt-in for physical Android/Web Bluetooth testing. Never require
  // local certificates for standard dev, production builds or CI.
  let https: { key: Buffer; cert: Buffer } | undefined;
  if (mode === 'local-https') {
    const keyPath = resolve(process.cwd(), '.cert/dev-key.pem');
    const certPath = resolve(process.cwd(), '.cert/dev-cert.pem');
    if (!existsSync(keyPath) || !existsSync(certPath)) {
      throw new Error('Missing local HTTPS certificate in app/.cert; see docs/dev-demo.md');
    }
    https = { key: readFileSync(keyPath), cert: readFileSync(certPath) };
  }

  return {
    define: policy ? {
      'import.meta.env.VITE_MM_SECURITY_CLOCK_POLICY': JSON.stringify(policy),
      'import.meta.env.VITE_MM_BUILD_ID': JSON.stringify(new Date().toISOString()),
    } : {},
    build: { assetsInlineLimit: 0 }, // Replaceable artwork stays external and precached, including small SVGs.
    plugins: [react(), VitePWA({
      // Enable the manifest and dev service worker for Android HTTPS installation tests.
      // Keep standard HTTP development free of service-worker caching.
      devOptions: { enabled: mode === 'local-https' },
      registerType: 'prompt',
      injectRegister: false,
      manifest: {
        id: '/', name: 'MiezMerker', short_name: 'MiezMerker', lang: 'de',
        start_url: '/', scope: '/', display: 'standalone',
        theme_color: '#24584b', background_color: '#fafaf3',
        icons: [{ src: pwaBrandAssets.icon192, sizes: '192x192', type: 'image/png', purpose: 'any' },
                { src: pwaBrandAssets.icon512, sizes: '512x512', type: 'image/png', purpose: 'any' },
                { src: pwaBrandAssets.iconMaskable512, sizes: '512x512', type: 'image/png', purpose: 'maskable' }],
      },
      workbox: {
        globPatterns: ['**/*.{js,css,html,png,ico,svg,webp}'],
        navigateFallback: 'index.html',
        navigateFallbackDenylist: [/^\/api(?:\/|\?|$)/, /^\/admin(?:\/|\?|$)/],
        // HTTP business data is never cached by this foundation.
        runtimeCaching: [],
      },
    })],
    server: { port: 5173, strictPort: true, proxy, https },
    preview: { port: 4173, strictPort: true, proxy },
  };
});
