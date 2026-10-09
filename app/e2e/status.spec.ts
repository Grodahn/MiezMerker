import { expect, test, type Page } from '@playwright/test';

async function backend(page: Page) {
  const state = { fail: false, unknownClock: false, hold: Promise.resolve() };
  await page.route('**/api/v1/**', async route => {
    const path = new URL(route.request().url()).pathname;
    const send = (data: unknown, status = 200) => route.fulfill({ status, contentType: 'application/json',
      headers: { 'Cache-Control': 'no-store' }, body: JSON.stringify(data) });
    if (path.endsWith('/auth/session')) return send({ userId: 'status-user', email: 'field@example.org', memberships: [
      { membershipId: 'status-member', organizationId: 'status-org', organizationName: 'Feldteam', role: 'MEMBER', status: 'ACTIVE' },
    ] });
    if (path.endsWith('/auth/csrf')) return send({ token: 'csrf' });
    if (path.includes('offline-credential')) return send({}, 403);
    await state.hold;
    if (state.fail) return route.abort('failed');
    if (path.endsWith('/chip-activity') && state.unknownClock) return send([
      { chipId: 'UNKNOWN-CHIP', lastSeenAtMillis: null, lastReceivedAt: '2026-10-08T12:00:00Z',
        uncertainClockCount: 1, observationCount: 1, feedingSiteIds: [] },
    ]);
    return send([]);
  });
  return state;
}

test('loading, unavailable data, keyboard retry and confirmed empty list share accessible states', async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 844 });
  const state = await backend(page);
  let release!: () => void;
  state.hold = new Promise(resolve => { release = resolve; });
  await page.goto('/cats');
  await expect(page.getByRole('progressbar', { name: 'Daten werden geladen …' })).toBeVisible();
  state.fail = true; release();
  await expect(page.getByRole('alert')).toContainText('Keine Verbindung zum Server');
  await expect(page.getByText('Noch keine Katzen oder Chips vorhanden.')).toHaveCount(0);
  state.fail = false;
  const retry = page.getByRole('button', { name: 'Erneut versuchen', exact: true });
  await retry.focus();
  expect(await retry.evaluate(el => getComputedStyle(el).outlineStyle)).not.toBe('none');
  await page.keyboard.press('Enter');
  await expect(page.getByRole('status').filter({ hasText: 'Noch keine Katzen oder Chips vorhanden.' })).toBeVisible();
  await expect(page.getByRole('alert')).toHaveCount(0);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await page.screenshot({ path: test.info().outputPath('empty-mobile.png') });
});

test('unknown clock explains receipt time instead of claiming a precise sighting', async ({ page }) => {
  const state = await backend(page); state.unknownClock = true;
  await page.goto('/cats');
  const clockStatus = page.getByRole('status').filter({ hasText: 'Keine verlässliche Sichtungszeit' });
  await expect(clockStatus).toContainText('UNKNOWN');
  await expect(clockStatus).toContainText('Serverempfang ist keine genaue Sichtungszeit');
  await expect(page.getByText(/Serverempfang:/)).toBeVisible();
});

for (const [name, pattern, message = 'selection'] of [
  ['NotFoundError', /abgebrochen oder kein passendes Gerät/], ['NotAllowedError', /Browser-Einstellungen/],
  ['AbortError', /abgebrochen/], ['NetworkError', /Bluetooth nicht verfügbar/, 'Bluetooth adapter not available'],
] as const) {
  test(`Bluetooth ${name} has its own recovery and does not claim local or server success`, async ({ page }) => {
    await backend(page);
    await page.addInitScript(({ errorName, message }) => Object.defineProperty(navigator, 'bluetooth', { configurable: true,
      value: { getDevices: async () => [], requestDevice: async () => { throw new DOMException(message, errorName); } },
    }), { errorName: name, message });
    await page.goto('/sync');
    // Batch sync itself never opens the chooser; only the explicit release does.
    await page.getByRole('button', { name: 'Futterstelle auslesen' }).click();
    await expect(page.getByText('Noch kein Napf freigegeben')).toBeVisible();
    await page.getByRole('button', { name: 'Weiteren Napf freigeben' }).click();
    await expect(page.getByText('Browser-Freigabe')).toBeVisible();
    await expect(page.getByText(pattern)).toBeVisible();
    await expect(page.getByText('Napf ausgelesen', { exact: true })).toHaveCount(0);
    await expect(page.getByText('Daten an Server übertragen', { exact: true })).toHaveCount(0);
    await expect(page.getByRole('button', { name: 'Weiteren Napf freigeben' })).toBeEnabled();
  });
}

test('long Bluetooth waits explain recovery and respect reduced motion without blocking navigation', async ({ page }) => {
  await backend(page);
  await page.setViewportSize({ width: 390, height: 844 });
  await page.emulateMedia({ reducedMotion: 'reduce' });
  await page.addInitScript(() => Object.defineProperty(navigator, 'bluetooth', { configurable: true,
    value: { getDevices: async () => [], requestDevice: () => new Promise(() => {}) },
  }));
  await page.goto('/sync');
  // With no authorized bowls the batch reports an honest fallback instead of
  // an endless spinner; navigation stays available throughout.
  await page.getByRole('button', { name: 'Futterstelle auslesen' }).click();
  await expect(page.getByText('Noch kein Napf freigegeben')).toBeVisible();
  await expect(page.getByRole('progressbar')).toHaveCount(0);
  await page.screenshot({ path: test.info().outputPath('loading-mobile.png') });
  await page.getByRole('link', { name: 'Katzen', exact: true }).click();
  await expect(page).toHaveURL(/\/cats$/);
});

test('offline login displays an honest connection state and no server success', async ({ page, context }) => {
  await backend(page); await page.goto('/');
  await expect(page.getByRole('heading', { name: 'Hallo!', exact: true })).toBeVisible();
  await context.setOffline(true);
  await expect(page.getByRole('status').filter({ hasText: 'Offline' })).toContainText('Serververbindung');
  await expect(page.getByRole('navigation')).toHaveCount(0);
  await expect(page.getByText('Daten an Server übertragen', { exact: true })).toHaveCount(0);
});
