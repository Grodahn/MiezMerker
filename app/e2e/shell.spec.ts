import { expect, test, type Page } from '@playwright/test';

async function session(page: Page, organizationName = 'Tierschutz A') {
  const state = { signedOut: false };
  await page.route('**/api/v1/**', async route => {
    const path = new URL(route.request().url()).pathname;
    const send = (data: unknown, status = 200) => route.fulfill({ status, contentType: 'application/json',
      headers: { 'Cache-Control': 'no-store' }, body: JSON.stringify(data) });
    if (path === '/api/v1/auth/session') return send(state.signedOut ? {} : {
      userId: 'shell-user', email: 'staff@example.org', displayName: 'Feldteam', memberships: [
        { membershipId: 'a', organizationId: 'org-a', organizationName, role: 'MEMBER', status: 'ACTIVE' },
        { membershipId: 'b', organizationId: 'org-b', organizationName: 'Tierschutz B', role: 'MEMBER', status: 'ACTIVE' },
      ],
    }, state.signedOut ? 401 : 200);
    if (path === '/api/v1/auth/csrf') return send({ token: 'csrf' });
    if (path === '/api/v1/auth/logout') { state.signedOut = true; return route.fulfill({ status: 204 }); }
    if (path.includes('offline-credential')) return send({}, 403);
    return send([]);
  });
}

for (const width of [320, 375, 390, 430, 768, 1280]) {
  test(`shell navigation and content fit at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 844 });
    await session(page);
    for (const [path, active] of [['/', 'Home'], ['/sync', 'Sync'], ['/feeding-sites', 'Futterstellen'], ['/cats', 'Katzen'], ['/nodes', 'Futterstellen']]) {
      await page.goto(path);
      const nav = page.getByRole('navigation', { name: 'Bereiche' });
      await expect(nav.getByRole('link')).toHaveText(['Home', 'Sync', 'Futterstellen', 'Katzen']);
      await expect(nav.locator('[aria-current="page"]')).toHaveText(active);
      await expect(nav.locator('[aria-current="page"]')).toHaveCount(1);
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
      for (const link of await nav.getByRole('link').all()) {
        const box = (await link.boundingBox())!;
        expect(box.width).toBeGreaterThanOrEqual(44);
        expect(box.height).toBeGreaterThanOrEqual(44);
      }
      const main = (await page.getByRole('main').boundingBox())!;
      const navigation = (await nav.boundingBox())!;
      expect(main.y + main.height).toBeLessThanOrEqual(navigation.y + 1);
      await page.getByRole('main').evaluate(element => { element.scrollTop = element.scrollHeight; });
      expect(((await nav.boundingBox())!).y).toBe(navigation.y);
      if (path === '/' && [320, 390, 1280].includes(width)) await page.screenshot({ path: test.info().outputPath(`home-${width}.png`) });
    }
    // Short dynamic viewport (including an on-screen keyboard) keeps all four targets available.
    await page.setViewportSize({ width, height: 400 });
    const nav = page.getByRole('navigation');
    await expect(nav).toBeInViewport();
    expect(((await nav.boundingBox())!).y + ((await nav.boundingBox())!).height).toBeLessThanOrEqual(400);
  });
}

test('native links preserve deep links, refresh, back/forward and legacy Nodes', async ({ page }) => {
  await session(page);
  await page.goto('/');
  await page.getByRole('link', { name: 'Futterstellen', exact: true }).click();
  await expect(page).toHaveURL(/\/feeding-sites$/);
  await page.getByRole('link', { name: 'Näpfe und Zuordnungen öffnen' }).click();
  await expect(page.getByRole('heading', { name: 'Nodes', exact: true })).toBeVisible();
  await expect(page.getByRole('link', { name: 'Futterstellen', exact: true })).toHaveAttribute('aria-current', 'page');
  await page.reload();
  await expect(page.getByRole('heading', { name: 'Nodes', exact: true })).toBeVisible();
  await page.goBack();
  await expect(page.getByRole('heading', { name: 'Futterstellen', exact: true })).toBeVisible();
  await page.goBack();
  await expect(page.getByRole('heading', { name: 'Home', exact: true })).toBeVisible();
  await page.goForward();
  await expect(page.getByRole('heading', { name: 'Futterstellen', exact: true })).toBeVisible();
  await page.getByRole('link', { name: 'Katzen', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Katzen', exact: true })).toBeVisible();
  await page.reload();
  await expect(page).toHaveTitle('Katzen · MiezMerker');
  await page.goto('/does-not-exist');
  await expect(page.getByRole('heading', { name: 'Seite nicht gefunden' })).toBeVisible();
  await expect(page.locator('nav [aria-current]')).toHaveCount(0);
});

test('keyboard skip link, account disclosure, organization and logout remain usable', async ({ page }) => {
  await session(page); await page.goto('/');
  await expect(page.getByRole('heading', { name: 'Home', exact: true })).toBeVisible();
  await page.keyboard.press('Tab');
  await expect(page.getByRole('link', { name: 'Zum Inhalt' })).toBeFocused();
  await page.keyboard.press('Enter');
  await expect(page.getByRole('main')).toBeFocused();
  const summary = page.locator('.auth-panel > summary');
  await summary.focus(); await page.keyboard.press('Enter');
  await expect(page.getByLabel('Aktive Organisation')).toBeVisible();
  await page.getByLabel('Aktive Organisation').selectOption('org-b');
  await expect(summary).toContainText('Tierschutz B');
  await page.getByRole('button', { name: 'Abmelden' }).click();
  await expect(page.getByRole('button', { name: 'Anmelden' })).toBeVisible();
  await expect(page.getByRole('navigation')).toHaveCount(0);
  await expect(page.getByRole('heading', { name: 'Home' })).toHaveCount(0);
});

test('200% text, reduced motion and long account labels do not overflow', async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 844 });
  await page.emulateMedia({ reducedMotion: 'reduce' });
  await session(page, 'Tierschutzverein mit einem sehr langen Organisationsnamen'); await page.goto('/');
  await page.locator('.auth-panel > summary').click();
  await page.getByLabel('Aktive Organisation').selectOption('org-a');
  await page.locator('.auth-panel > summary').click();
  await page.evaluate(() => { document.documentElement.style.fontSize = '200%'; });
  await expect(page.getByRole('link', { name: 'Futterstellen', exact: true })).toBeInViewport();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await page.locator('.auth-panel > summary').click();
  await page.getByLabel('Aktive Organisation').focus();
  const outline = await page.getByLabel('Aktive Organisation').evaluate(el => getComputedStyle(el).outlineStyle);
  expect(outline).not.toBe('none');
  await expect(page.getByRole('button', { name: 'Abmelden' })).toBeInViewport();
  expect(await page.evaluate(() => document.documentElement.scrollHeight <= innerHeight)).toBe(true);
  await expect(page.getByRole('navigation')).toBeInViewport();
  await page.screenshot({ path: test.info().outputPath('text-200-percent.png') });
  await page.getByRole('button', { name: 'Abmelden' }).click();
  await expect(page.getByRole('button', { name: 'Anmelden' })).toBeVisible();
});

test('all static illustrations and fallbacks are cached and fetchable offline', async ({ page, context }) => {
  await session(page); await page.goto('/');
  await expect(page.getByRole('heading', { name: 'Home', exact: true })).toBeVisible();
  await page.evaluate(() => navigator.serviceWorker.ready.then(() => undefined));
  await page.reload();
  await expect.poll(() => page.evaluate(() => Boolean(navigator.serviceWorker.controller))).toBe(true);
  const urls = await page.evaluate(async () => {
    const keys = (await Promise.all((await caches.keys()).map(async name => (await caches.open(name)).keys()))).flat();
    return keys.map(key => key.url).filter(url => /\/assets\/.+\.(svg|png)/.test(url));
  });
  for (const name of ['logo', 'home', 'background', 'cat', 'feeding-site', 'bowl', 'bluetooth', 'success', 'error', 'offline']) {
    expect(urls.some(url => url.includes(`/assets/${name}-`))).toBe(true);
  }
  await context.setOffline(true);
  expect(await page.evaluate(async urls => (await Promise.all(urls.map(async url => (await fetch(url)).ok))).every(Boolean), urls)).toBe(true);
});
