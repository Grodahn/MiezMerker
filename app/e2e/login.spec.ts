import { expect, test, type Page } from '@playwright/test';

async function backend(page: Page) {
  const state = { signedIn: false, status: 200, hold: Promise.resolve(), requests: 0 };
  const user = { userId: 'login-user', email: 'volunteer@example.org', memberships: [
    { membershipId: 'membership', organizationId: 'org', organizationName: 'Tierschutz', role: 'MEMBER', status: 'ACTIVE' },
  ] };
  await page.route('**/api/v1/**', async route => {
    const path = new URL(route.request().url()).pathname;
    const send = (data: unknown, status = 200) => route.fulfill({ status, contentType: 'application/json',
      headers: { 'Cache-Control': 'no-store' }, body: JSON.stringify(data) });
    if (path.endsWith('/auth/csrf')) return send({ token: 'test-csrf' });
    if (path.endsWith('/auth/session')) return send(state.signedIn ? user : {}, state.signedIn ? 200 : 401);
    if (path.endsWith('/auth/login')) {
      state.requests++;
      expect(route.request().headers()['x-xsrf-token']).toBe('test-csrf');
      expect(route.request().postDataJSON()).toEqual({ email: user.email, password: 'test-password' });
      await state.hold;
      state.signedIn = state.status === 200;
      return send(state.signedIn ? user : { detail: 'sensitive server detail' }, state.status);
    }
    if (path.endsWith('/auth/logout')) { state.signedIn = false; return route.fulfill({ status: 204 }); }
    if (path.includes('offline-credential')) return send({}, 403);
    return send([]);
  });
  return state;
}

async function fill(page: Page) {
  await page.getByLabel('E-Mail', { exact: true }).fill('volunteer@example.org');
  await page.getByLabel('Passwort', { exact: true }).fill('test-password');
}

for (const [width, height] of [[320, 740], [390, 844], [430, 932], [768, 1024], [1280, 900], [844, 390], [390, 360]]) {
  test(`login fits ${width}x${height} with accessible controls`, async ({ page }, testInfo) => {
    await page.setViewportSize({ width, height });
    await backend(page);
    await page.goto('/cats');
    await expect(page.getByRole('heading', { name: 'Anmelden', exact: true })).toBeVisible();
    await expect(page.getByRole('navigation')).toHaveCount(0);
    await expect(page.getByRole('heading', { name: 'Katzen', exact: true })).toHaveCount(0);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    expect(await page.getByRole('main').evaluate(element => element.scrollWidth <= element.clientWidth)).toBe(true);
    for (const control of [page.getByLabel('E-Mail', { exact: true }), page.getByLabel('Passwort'), page.getByRole('button', { name: 'Anmelden', exact: true })]) {
      await control.scrollIntoViewIfNeeded();
      const box = (await control.boundingBox())!;
      expect(box.width).toBeGreaterThanOrEqual(44);
      expect(box.height).toBeGreaterThanOrEqual(44);
      expect(box.x).toBeGreaterThanOrEqual(0);
      expect(box.x + box.width).toBeLessThanOrEqual(width);
      await expect(control).toBeInViewport();
    }
    const email = page.getByLabel('E-Mail', { exact: true });
    expect(await email.evaluate(element => parseFloat(getComputedStyle(element).fontSize))).toBeGreaterThanOrEqual(16);
    await email.focus();
    expect(await email.evaluate(element => getComputedStyle(element).outlineStyle)).not.toBe('none');
    if ((width === 390 && height === 844) || width === 1280) {
      await page.getByRole('main').evaluate(element => { element.scrollTop = 0; });
      const path = testInfo.outputPath(`login-${width}.png`);
      await page.screenshot({ path });
      await testInfo.attach(`login-${width}.png`, { path, contentType: 'image/png' });
    }
  });
}

test('keyboard login announces loading and opens protected navigation only on success; logout returns to login', async ({ page }) => {
  const state = await backend(page);
  let release!: () => void;
  state.hold = new Promise(resolve => { release = resolve; });
  await page.goto('/');
  await expect(page.getByRole('button', { name: 'Anmelden', exact: true })).toBeVisible();
  await page.keyboard.press('Tab'); // skip link
  await page.keyboard.press('Tab'); // email
  await expect(page.getByLabel('E-Mail', { exact: true })).toBeFocused();
  await page.keyboard.type('volunteer@example.org');
  await page.keyboard.press('Tab');
  await expect(page.getByLabel('Passwort')).toBeFocused();
  await page.keyboard.type('test-password');
  await page.keyboard.press('Enter');
  await expect(page.getByRole('button', { name: 'Anmeldung läuft …' })).toBeDisabled();
  await expect(page.getByRole('status')).toContainText('Anmeldung wird geprüft');
  await expect(page.getByRole('navigation')).toHaveCount(0);
  release();
  await expect(page.getByRole('navigation', { name: 'Bereiche' })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Hallo!' })).toBeVisible();
  expect(state.requests).toBe(1);
  await page.locator('.auth-panel > summary').click();
  await page.getByRole('button', { name: 'Abmelden' }).click();
  await expect(page.getByRole('button', { name: 'Anmelden', exact: true })).toBeVisible();
  await expect(page.getByRole('navigation')).toHaveCount(0);
});

for (const status of [401, 500]) {
  test(`login ${status} offers safe retry feedback`, async ({ page }) => {
    await page.setViewportSize({ width: 320, height: 740 });
    const state = await backend(page);
    state.status = status;
    await page.goto('/');
    await fill(page);
    await page.getByLabel('Passwort').press('Enter');
    await expect(page.getByRole('alert')).toContainText('Bitte E-Mail und Passwort prüfen');
    await expect(page.getByRole('alert')).not.toContainText('sensitive');
    await expect(page.getByRole('navigation')).toHaveCount(0);
    await expect(page.getByRole('button', { name: 'Anmelden', exact: true })).toBeEnabled();
    expect(await page.getByRole('main').evaluate(element => element.scrollWidth <= element.clientWidth)).toBe(true);
  });
}

test('network failure offers safe connection guidance', async ({ page }) => {
  await backend(page);
  await page.route('**/api/v1/auth/login', route => route.abort('internetdisconnected'));
  await page.goto('/');
  await fill(page);
  await page.getByLabel('Passwort').press('Enter');
  await expect(page.getByRole('alert')).toContainText('Internetverbindung prüfen');
  await expect(page.getByRole('navigation')).toHaveCount(0);
});

test('replacement artwork retains reserved logo dimensions and background cropping', async ({ page }) => {
  await backend(page);
  let release!: () => void;
  const hold = new Promise<void>(resolve => { release = resolve; });
  await page.route('**/assets/logo-*.png', async route => {
    await hold;
    // Deliberately different intrinsic proportions from the original logo.
    await route.fulfill({ contentType: 'image/svg+xml',
      body: '<svg xmlns="http://www.w3.org/2000/svg" width="400" height="40"><rect width="400" height="40" fill="#24584b"/></svg>' });
  });
  await page.goto('/', { waitUntil: 'domcontentloaded' });
  const logo = page.locator('.login-logo');
  await expect(logo).toBeVisible();
  const before = (await logo.boundingBox())!;
  expect(before.width).toBe(128);
  expect(before.height).toBe(128);
  expect(await page.locator('.login-background').evaluate(element => getComputedStyle(element).objectFit)).toBe('cover');
  release();
  await expect.poll(() => logo.evaluate(element => (element as HTMLImageElement).naturalWidth)).toBe(400);
  const after = (await logo.boundingBox())!;
  expect(after.width).toBe(before.width);
  expect(after.height).toBe(before.height);
  expect(await logo.evaluate(element => getComputedStyle(element).objectFit)).toBe('contain');
  expect(await page.getByRole('main').evaluate(element => element.scrollWidth <= element.clientWidth)).toBe(true);
});
