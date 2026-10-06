import { expect, test } from '@playwright/test';

test('direct Admin URLs reach Spring HTML even after the field worker controls navigation', async ({ page, context }) => {
  await page.goto('/sync');
  await page.evaluate(() => navigator.serviceWorker.ready.then(() => undefined));
  await page.reload();
  await expect.poll(() => page.evaluate(() => Boolean(navigator.serviceWorker.controller))).toBe(true);
  for (const path of ['/admin', '/admin/', '/admin/members', '/admin/sites', '/admin/cats', '/admin/observations', '/admin/visits']) {
    const response = await page.goto(path);
    expect(response?.status()).toBe(200);
    expect(new URL(page.url()).pathname).toBe('/admin/login');
    await expect(page.locator('form[action="/admin/login"]')).toBeVisible();
    await expect(page.locator('form[action="/admin/login"] input[name="_csrf"]').first()).toHaveValue(/.+/);
    await expect(page.locator('#root')).toHaveCount(0);
    await expect(page.getByRole('navigation', { name: 'Bereiche' })).toHaveCount(0);
  }
  const cached = await page.evaluate(async () => {
    const paths: string[] = [];
    for (const key of await caches.keys()) for (const request of await (await caches.open(key)).keys()) paths.push(new URL(request.url).pathname);
    return paths;
  });
  expect(cached.some(path => /^\/admin(?:\/|$)/.test(path))).toBe(false);
  await context.setOffline(true);
  // A denied network navigation must fail, never return the cached React shell.
  await expect(page.goto('/admin/sites')).rejects.toThrow();
});

test('development proxy reserves the complete Admin namespace for Spring', async ({ request }) => {
  for (const path of ['/admin', '/admin/', '/admin/members', '/admin/sites', '/admin/cats', '/admin/observations', '/admin/visits']) {
    const response = await request.get(`http://127.0.0.1:5173${path}`);
    expect(response.status()).toBe(200);
    expect(new URL(response.url()).pathname).toBe('/admin/login');
    const html = await response.text();
    expect(html).toContain('action="/admin/login"');
    expect(html).toContain('name="_csrf"');
    expect(html).not.toContain('id="root"');
  }
});
