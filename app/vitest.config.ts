import { defineConfig } from 'vitest/config';
// Preserve the full signed-time compatibility matrix; alpha flows explicitly
// select alpha and the default/build contract has separate regression coverage.
export default defineConfig({ test: { environment: 'jsdom', env: { VITE_MM_SECURITY_CLOCK_POLICY: 'SIGNED_BACKEND_TIME' }, include: ['src/**/*.test.{ts,tsx}'] } });
