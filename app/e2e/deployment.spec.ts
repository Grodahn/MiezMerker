import { expect, test } from '@playwright/test';

test('deployment receipt identifies the compiled variant and commit', async ({ request }) => {
  const response = await request.get('/build.json');
  expect(response.status()).toBe(200);
  expect(await response.json()).toEqual({ sha: process.env.GITHUB_SHA ?? 'local',
    policy: process.env.MM_E2E_CLOCK_POLICY ?? 'FIELD_ALPHA_PHONE_CLOCK' });
});

test('offline navigation fallback is limited to PWA routes', async ({ page, context }) => {
  await page.goto('/sync');
  await page.evaluate(() => navigator.serviceWorker.ready.then(() => undefined));
  await page.reload();
  await expect.poll(() => page.evaluate(() => Boolean(navigator.serviceWorker.controller))).toBe(true);
  await context.setOffline(true);
  await page.goto('/sync');
  await expect(page.locator('#root')).toHaveCount(1);
  for (const path of ['/unknown-private-route', '/cat-photos/private.jpg', '/backend.jar', '/admin', '/api/v1/health']) {
    await expect(page.goto(path)).rejects.toThrow();
  }
});
