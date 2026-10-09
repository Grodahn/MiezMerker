import { expect, test, type Page } from '@playwright/test';

async function backend(page: Page, authenticated = true) {
  const state = { authenticated, homeDataRequests: [] as string[] };
  await page.route('**/api/v1/**', async route => {
    const path = new URL(route.request().url()).pathname;
    const send = (data: unknown, status = 200) => route.fulfill({ status, contentType: 'application/json',
      headers: { 'Cache-Control': 'no-store' }, body: JSON.stringify(data) });
    if (path === '/api/v1/auth/login') state.authenticated = true;
    if (path === '/api/v1/auth/session' || path === '/api/v1/auth/login') return send(state.authenticated ? {
      userId: 'home-user', email: 'field@example.org', memberships: [
        { membershipId: 'member', organizationId: 'home-org', organizationName: 'Feldteam', role: 'MEMBER', status: 'ACTIVE' },
      ],
    } : {}, state.authenticated ? 200 : 401);
    if (path === '/api/v1/auth/csrf') return send({ token: 'csrf' });
    // Existing app-wide offline identity renewal can register a device on login.
    // It is independent of Home; this fixture does not issue offline credentials.
    if (path === '/api/v1/devices') return send({}, 403);
    if (path.includes('offline-credential')) return send({}, 403);
    state.homeDataRequests.push(path);
    return send([]);
  });
  return state;
}

for (const [width, height] of [[320, 568], [375, 667], [390, 844], [430, 932], [768, 1024], [1280, 800]]) {
  test(`Home actions fit and remain reachable at ${width}×${height}`, async ({ page }) => {
    await page.setViewportSize({ width, height });
    const state = await backend(page);
    await page.goto('/');
    const home = page.getByRole('region', { name: 'Hallo!' });
    const cta = home.getByRole('link', { name: 'Futterstelle auslesen' });
    await expect(home).toBeVisible();
    await expect(cta).toBeInViewport();
    await expect(page).toHaveTitle('Home · MiezMerker');
    await expect(home.getByRole('link')).toHaveText(['Futterstelle auslesen', 'Futterstellen', 'Katzen']);
    await expect(page.getByRole('img', { name: 'MiezMerker' })).toBeVisible();
    await expect(home.locator('.home-illustration')).toHaveAttribute('alt', '');
    await expect.poll(() => home.locator('.home-illustration').evaluate(image => (image as HTMLImageElement).naturalWidth)).toBeGreaterThan(0);
    const nav = page.getByRole('navigation', { name: 'Bereiche' });
    await expect(nav.getByRole('link')).toHaveText(['Home', 'Sync', 'Futterstellen', 'Katzen']);
    await expect(nav.locator('[aria-current="page"]')).toHaveText('Home');
    const navBox = (await nav.boundingBox())!;
    const ctaBox = (await cta.boundingBox())!;
    expect(ctaBox.height).toBeGreaterThanOrEqual(64);
    expect(ctaBox.y + ctaBox.height).toBeLessThanOrEqual(navBox.y);
    expect(ctaBox.y).toBeGreaterThan(height / 3);
    for (const link of await home.getByRole('link').all()) {
      await expect(link).toBeInViewport({ ratio: 1 });
      await link.scrollIntoViewIfNeeded();
      await expect(link).toBeInViewport();
      const box = (await link.boundingBox())!;
      expect(box.width).toBeGreaterThanOrEqual(44);
      expect(box.height).toBeGreaterThanOrEqual(44);
      expect(box.x).toBeGreaterThanOrEqual(0);
      expect(box.x + box.width).toBeLessThanOrEqual(width);
      expect(box.y + box.height).toBeLessThanOrEqual(navBox.y);
    }
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    expect(state.homeDataRequests).toEqual([]);
    await page.getByRole('main').evaluate(element => { element.scrollTop = 0; });
    await page.screenshot({ path: test.info().outputPath(`home-${width}.png`) });
  });
}

test('online login lands on Home and secondary entries preserve history, reloads and deep links', async ({ page }) => {
  await backend(page, false);
  await page.goto('/');
  await page.getByLabel('E-Mail').fill('field@example.org');
  await page.getByLabel('Passwort').fill('password');
  await page.getByRole('button', { name: 'Anmelden' }).click();
  const home = page.getByRole('region', { name: 'Hallo!' });
  await expect(home).toBeVisible();
  await expect(page).toHaveURL(/\/$/);
  for (const [label, path] of [['Futterstellen', '/feeding-sites'], ['Katzen', '/cats']]) {
    await home.getByRole('link', { name: label, exact: true }).click();
    await expect(page).toHaveURL(new RegExp(`${path}$`));
    await expect(page.getByRole('heading', { name: label, exact: true })).toBeVisible();
    await page.reload();
    await expect(page.getByRole('heading', { name: label, exact: true })).toBeVisible();
    await page.goBack();
    await expect(home).toBeVisible();
    await page.goForward();
    await expect(page.getByRole('heading', { name: label, exact: true })).toBeVisible();
    await page.getByRole('navigation').getByRole('link', { name: 'Home', exact: true }).click();
    await expect(home).toBeVisible();
  }
  await page.reload();
  await expect(home).toBeVisible();
});

test('keyboard activation enters Sync without opening the chooser; only the collector action requests a device', async ({ page }) => {
  await backend(page);
  await page.addInitScript(() => {
    Object.defineProperty(navigator, 'bluetooth', { configurable: true, value: {
      getDevices: async () => [],
      requestDevice: () => {
        sessionStorage.setItem('chooser-calls', String(Number(sessionStorage.getItem('chooser-calls') ?? 0) + 1));
        sessionStorage.setItem('chooser-user-active', String(navigator.userActivation.isActive));
        return Promise.reject(new DOMException('Cancelled', 'NotFoundError'));
      },
    } });
  });
  await page.goto('/');
  await expect(page.getByRole('heading', { name: 'Hallo!' })).toBeVisible();
  await page.keyboard.press('Tab');
  await expect(page.getByRole('link', { name: 'Zum Inhalt' })).toBeFocused();
  await page.keyboard.press('Enter');
  await expect(page.getByRole('main')).toBeFocused();
  await page.keyboard.press('Tab');
  const cta = page.getByRole('link', { name: 'Futterstelle auslesen' });
  await expect(cta).toBeFocused();
  expect(await cta.evaluate(element => getComputedStyle(element).outlineStyle)).not.toBe('none');
  await page.keyboard.press('Tab');
  await expect(page.getByRole('main').getByRole('link', { name: 'Futterstellen', exact: true })).toBeFocused();
  await page.keyboard.press('Tab');
  await expect(page.getByRole('main').getByRole('link', { name: 'Katzen', exact: true })).toBeFocused();
  await page.keyboard.press('Shift+Tab');
  await page.keyboard.press('Shift+Tab');
  await page.keyboard.press('Enter');
  await expect(page).toHaveURL(/\/sync$/);
  await expect(page.getByText('Lokaler Speicher bereit')).toBeVisible();
  expect(await page.evaluate(() => sessionStorage.getItem('chooser-calls'))).toBeNull();
  await page.reload();
  const batch = page.getByRole('button', { name: 'Futterstelle auslesen' });
  await expect(batch).toBeEnabled();
  expect(await page.evaluate(() => sessionStorage.getItem('chooser-calls'))).toBeNull();
  // Batch sync of previously authorized bowls never opens the chooser.
  await batch.click();
  expect(await page.evaluate(() => sessionStorage.getItem('chooser-calls'))).toBeNull();
  const release = page.getByRole('button', { name: 'Weiteren Napf freigeben' });
  await expect(release).toBeEnabled();
  await release.click();
  expect(await page.evaluate(() => sessionStorage.getItem('chooser-calls'))).toBe('1');
  expect(await page.evaluate(() => sessionStorage.getItem('chooser-user-active'))).toBe('true');
  await page.getByRole('navigation').getByRole('link', { name: 'Home', exact: true }).click();
  await expect(page.getByRole('region', { name: 'Hallo!' })).toBeVisible();
  await expect(page.getByRole('main').getByRole('link')).toHaveCount(3);
  expect(await page.evaluate(() => sessionStorage.getItem('chooser-calls'))).toBe('1');
});

test('200% text and unavailable illustration retain labelled, keyboard-reachable Home actions', async ({ page }) => {
  await backend(page);
  await page.setViewportSize({ width: 320, height: 568 });
  await page.route('**/assets/home-*.svg', route => route.abort());
  await page.goto('/');
  await expect(page.getByRole('heading', { name: 'Hallo!' })).toBeVisible();
  await page.evaluate(() => { document.documentElement.style.fontSize = '200%'; });
  const home = page.getByRole('region', { name: 'Hallo!' });
  for (const link of await home.getByRole('link').all()) {
    await link.focus();
    await expect(link).toBeFocused();
    await expect(link).toBeInViewport();
    expect(await link.evaluate(element => element.scrollWidth <= element.clientWidth)).toBe(true);
  }
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await expect(page.getByRole('navigation')).toBeInViewport();
});
