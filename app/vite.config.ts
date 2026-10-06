import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import { VitePWA } from 'vite-plugin-pwa';

const backend = { target: process.env.MIEZMERKER_BACKEND_URL ?? 'http://127.0.0.1:8080', changeOrigin: false };
// Proxy and Workbox match URLs including query strings, including bare /admin?… .
// Server-rendered Admin owns this namespace in development and preview too.
const proxy = { '/api': backend, '^/admin(?:/|\\?|$)': backend };
export default defineConfig({
  plugins: [react(), VitePWA({
    registerType: 'prompt',
    injectRegister: false,
    manifest: {
      id: '/', name: 'MiezMerker', short_name: 'MiezMerker', lang: 'de',
      start_url: '/sync', scope: '/', display: 'standalone',
      theme_color: '#24584b', background_color: '#f4f7f5',
      icons: [{ src: '/icon-192.png', sizes: '192x192', type: 'image/png' },
              { src: '/icon-512.png', sizes: '512x512', type: 'image/png', purpose: 'any maskable' }],
    },
    workbox: {
      globPatterns: ['**/*.{js,css,html,png,ico}'],
      navigateFallback: 'index.html',
      navigateFallbackDenylist: [/^\/api(?:\/|\?|$)/, /^\/admin(?:\/|\?|$)/],
      // HTTP business data is never cached by this foundation.
      runtimeCaching: [],
    },
  })],
  server: { port: 5173, strictPort: true, proxy },
  preview: { port: 4173, strictPort: true, proxy },
});
