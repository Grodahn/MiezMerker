import { expect, test, type Page } from '@playwright/test';
import { readFile } from 'node:fs/promises';

const orgA = '00000000-0000-4000-8000-000000000001';
const orgB = '00000000-0000-4000-8000-000000000002';
const orgC = '00000000-0000-4000-8000-000000000003';
const proof = '00000000-0000-4000-8000-000000000010';
const source = { organizationId: orgB, displayName: 'Organisation B' };
const profile = { chipId: 'CHIP', source, catDisplayName: 'Foreign-B-unique' };
const visit = { source, startAt: '2026-10-01T10:00:00Z', endAt: '2026-10-01T10:01:00Z' };

async function backend(page: Page) {
  const state = { revoked: false, careOnly: false, siteLabel: false, known: true, hasProof: true, expired: false,
    multiple: false, pageSize: 5, delay: null as Promise<void> | null, started: false };
  const requests: { path: string; body: any; headers: Record<string, string> }[] = [];
  await page.route('**/api/v1/**', async route => {
    const request = route.request(); const url = new URL(request.url()); const path = url.pathname;
    const body = request.postDataJSON(); requests.push({ path, body, headers: request.headers() });
    const send = (data: unknown, status = 200) => route.fulfill({ status, contentType: 'application/json',
      headers: { 'Cache-Control': 'no-store' }, body: JSON.stringify(data) });
    if (path.endsWith('/auth/logout')) { state.expired = true; return send({}); }
    if (state.expired && !path.endsWith('/auth/csrf')) return send({}, 401);
    if (path.endsWith('/auth/csrf')) return send({ token: 'csrf' });
    if (path.endsWith('/auth/session')) return send({ userId: 'user', email: 'staff@example.org', memberships: [orgA, orgC].map((organizationId, i) => ({
      membershipId: `membership-${i}`, organizationId, organizationName: i ? 'Organisation C' : 'Organisation A', role: 'ADMIN', status: 'ACTIVE',
    })) });
    if (request.method() === 'PATCH' && path.endsWith('/cats/local')) return send({}, 409);
    if (path.includes('/shared-care/')) {
      expect(request.method()).toBe('POST'); expect(request.headers()['x-xsrf-token']).toBe('csrf');
      expect(url.search).toBe('');
      if (state.delay) { state.started = true; await state.delay; }
      if (path.endsWith('/resolve')) {
        expect(body.ownObservationRefs).toEqual([proof]);
        return send({ items: state.revoked ? [] : [profile, ...(state.multiple ? [{ ...profile, source: { organizationId: orgC, displayName: 'Organisation C' }, catDisplayName: 'Foreign-C-unique' }] : [])], nextOffset: null });
      }
      // Foreign cat photos (#95): observation proof plus source organization,
      // no chip/cat lookup, no paging. Denied exactly like a missing grant.
      if (path.endsWith('/photo')) {
        expect(body.ownObservationRef).toBe(proof);
        expect([orgB, orgC]).toContain(body.sourceOrganizationId);
        if (state.revoked || state.careOnly) return send({}, 404);
        return route.fulfill({ status: 200, contentType: 'image/jpeg',
          headers: { 'Cache-Control': 'no-store' }, body: Buffer.from([0xff, 0xd8, 0xff, 0xd9]) });
      }
      expect(body.ownObservationRef).toBe(proof); expect(body.limit).toBe(state.pageSize);
      return send({ items: state.revoked || state.careOnly || body.offset > 0 ? [] : [{ ...visit, ...(state.siteLabel ? { siteDisplayName: 'Authorized-B-site' } : {}) }],
        nextOffset: state.revoked || state.careOnly || body.offset > 0 ? null : body.limit });
    }
    if (path === '/api/v1/observations') return send(state.hasProof ? [{ id: proof, chipId: 'CHIP', organizationId: url.searchParams.get('organizationId'), clockStatus: 'UNKNOWN' }] : []);
    if (path.endsWith('/cats')) return send(state.known ? [{ id: 'local', chipId: 'CHIP', name: 'Own-A-unique' }] : []);
    if (path.endsWith('/chip-activity')) return send([{ chipId: 'CHIP', uncertainClockCount: 1, observationCount: 1 }]);
    return send([]);
  });
  await page.addInitScript(org => sessionStorage.setItem('miezmerker-active-organization', JSON.stringify({ userId: 'user', organizationId: org })), orgA);
  return { state, requests };
}
async function open(page: Page, known = true) {
  await page.goto('/cats');
  await page.getByRole('button', { name: known ? 'Details / bearbeiten' : 'Katze dazu anlegen' }).click();
}
const shared = (page: Page) => page.getByRole('region', { name: 'Geteilte Betreuung', exact: true });

for (const width of [320, 390, 430]) {
  test(`authorized A/B/C care in existing Cat details at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 844 });
    const { state } = await backend(page); state.multiple = true; state.siteLabel = true;
    await open(page);
    await expect(shared(page)).toContainText('Foreign-B-unique'); await expect(shared(page)).toContainText('Foreign-C-unique');
    await expect(page.getByLabel('Name (optional)')).toHaveValue('Own-A-unique');
    await expect(shared(page)).toContainText('Authorized-B-site');
    // Authorized foreign photos render read-only inside their provenance card;
    // the local profile is never substituted.
    await expect(shared(page).getByAltText('Freigegebenes Katzenfoto von Organisation B')).toBeVisible();
    await expect(page.getByLabel('Name (optional)')).toHaveValue('Own-A-unique');
    await expect(shared(page).getByRole('textbox')).toHaveCount(0);
    await expect(shared(page).getByRole('link')).toHaveCount(0);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    const sourceCard = shared(page).getByRole('region', { name: 'Geteilte Betreuung von Organisation B' });
    expect((await sourceCard.locator('caption').boundingBox())!.height).toBeLessThan(100);
    if (width === 320) {
      await sourceCard.evaluate(element => element.scrollIntoView({ block: 'start' }));
      await page.screenshot({ path: '../test-results/shared-care-320.png' });
    }
    const button = shared(page).getByRole('button', { name: 'Freigaben neu prüfen' });
    await button.focus(); await expect(button).toBeFocused(); await page.keyboard.press('Enter');
    await expect(shared(page)).toContainText('Foreign-B-unique');
  });
}

test('unknown chip stays unknown; CARE only and no observation proof reveal no visits', async ({ page }) => {
  const { state, requests } = await backend(page); state.known = false; state.careOnly = true;
  await open(page, false); await expect(shared(page)).toContainText('Foreign-B-unique');
  await expect(page.getByRole('heading', { name: 'Unbekannter Chip', exact: true })).toBeVisible();
  await expect(shared(page).locator('time')).toHaveCount(0);
  await expect(shared(page)).toContainText('Keine freigegebenen Besuche');
  // CARE without PHOTO shows no foreign image, only the neutral placeholder.
  await expect(shared(page)).toContainText('Kein freigegebenes Foto verfügbar');
  await expect(shared(page).locator('img')).toHaveCount(0);
  state.hasProof = false; await shared(page).getByRole('button', { name: 'Freigaben neu prüfen' }).click();
  await expect(shared(page)).toContainText('Keine eigene gespeicherte Beobachtung');
  expect(requests.filter(r => r.path.endsWith('/resolve'))).toHaveLength(1);
});

test('pagination reauthorizes scopes and removes revoked foreign profile', async ({ page }) => {
  const { state, requests } = await backend(page); await open(page);
  await expect(shared(page)).toContainText('Foreign-B-unique');
  await expect(shared(page)).toContainText('Futterstellenname nicht freigegeben');
  await shared(page).getByRole('button', { name: 'Ältere Besuche' }).click();
  await expect(shared(page)).toContainText('Seite 2');
  await expect(shared(page)).toContainText('Keine freigegebenen Besuche');
  expect(requests.filter(r => r.path.endsWith('/resolve'))).toHaveLength(2);
  state.revoked = true;
  await shared(page).getByRole('button', { name: 'Neuere Besuche' }).click();
  await expect(shared(page)).toContainText('Keine freigegebene Betreuung');
  await expect(shared(page)).not.toContainText('Foreign-B-unique');
  await expect(shared(page).getByRole('button', { name: 'Freigaben neu prüfen' })).toBeFocused();
});

test('a rejected local Cat save preserves the shared panel and its refresh control', async ({ page }) => {
  await backend(page); await open(page); await expect(shared(page)).toContainText('Foreign-B-unique');
  await page.getByLabel('Name (optional)').fill('Changed own name');
  await page.getByRole('button', { name: 'Speichern', exact: true }).click();
  await expect(page.getByText(/Konflikt: Chip bereits vorhanden/)).toBeVisible();
  await expect(shared(page)).toContainText('Foreign-B-unique');
  await shared(page).getByRole('button', { name: 'Freigaben neu prüfen' }).click();
  await expect(shared(page)).toContainText('Foreign-B-unique');
});

for (const transition of ['organization', 'logout', 'expired session', 'navigation', 'offline', 'pagehide']) {
  test(`late shared response cannot restore data after ${transition}`, async ({ page, context }) => {
    const { state } = await backend(page); await open(page); await expect(shared(page)).toContainText('Foreign-B-unique');
    let finish!: () => void; state.delay = new Promise(resolve => { finish = resolve; });
    await shared(page).getByRole('button', { name: 'Freigaben neu prüfen' }).click();
    await expect.poll(() => state.started).toBe(true);
    if (['organization', 'logout', 'expired session'].includes(transition)) await page.locator('.auth-panel > summary').click();
    if (transition === 'organization') await page.getByLabel('Aktive Organisation').selectOption(orgC);
    if (transition === 'logout') await page.getByRole('button', { name: 'Abmelden' }).click();
    if (transition === 'expired session') {
      state.expired = true;
      // An existing auth session request detects expiry and closes the gate.
      await page.getByLabel('Aktive Organisation').selectOption(orgC);
    }
    if (transition === 'navigation') await page.getByRole('button', { name: 'Zurück zur Liste' }).click();
    if (transition === 'offline') await context.setOffline(true);
    if (transition === 'pagehide') await page.evaluate(() => window.dispatchEvent(new PageTransitionEvent('pagehide')));
    finish();
    await expect(page.getByText('Name bei Organisation B: Foreign-B-unique')).toHaveCount(0);
    await page.waitForTimeout(150); // Let the deliberately delayed response finish after cancellation.
    await expect(page.getByText('Name bei Organisation B: Foreign-B-unique')).toHaveCount(0);
  });
}

test('foreign data is absent from browser storage and service-worker caches; offline clears DOM', async ({ page, context }) => {
  await backend(page); await open(page); await expect(shared(page)).toContainText('Foreign-B-unique');
  const storage = await page.evaluate(async () => {
    const values = [JSON.stringify(localStorage), JSON.stringify(sessionStorage)];
    for (const name of await caches.keys()) {
      const cache = await caches.open(name);
      for (const request of await cache.keys()) {
        if (request.url.includes('/shared-care/')) throw new Error('Shared response was cached');
        values.push(await (await cache.match(request))!.text());
      }
    }
    for (const info of await indexedDB.databases()) {
      if (!info.name) continue;
      const db = await new Promise<IDBDatabase>((resolve, reject) => { const request = indexedDB.open(info.name!); request.onsuccess = () => resolve(request.result); request.onerror = () => reject(request.error); });
      for (const name of Array.from(db.objectStoreNames)) {
        values.push(JSON.stringify(await new Promise((resolve, reject) => { const request = db.transaction(name).objectStore(name).getAll(); request.onsuccess = () => resolve(request.result); request.onerror = () => reject(request.error); })));
      }
      db.close();
    }
    return values.join('\n');
  });
  expect(storage).not.toContain('Foreign-B-unique'); expect(storage).not.toContain('Authorized-B-site');
  await context.setOffline(true); await expect(page.getByText('Name bei Organisation B: Foreign-B-unique')).toHaveCount(0);
  await context.setOffline(false); await open(page); await expect(shared(page)).toContainText('Foreign-B-unique');
});

test('Admin adapter uses twenty-item pages, clears on submit and reloads server context on return', async ({ page }) => {
  const { state, requests } = await backend(page); state.pageSize = 20;
  // Browser harness for the real static Admin adapter. AdminCatsTest separately
  // checks the actual authenticated Thymeleaf detail and resource delivery.
  for (const file of ['admin-shared-care.js', 'shared-care.js', 'shared-care.css', 'error-codes.generated.js']) {
    const content = await readFile(new URL(`../../backend/src/main/resources/static/${file}`, import.meta.url), 'utf8');
    await page.route(`**/${file}`, route => route.fulfill({ contentType: file.endsWith('.css') ? 'text/css' : 'text/javascript', body: content }));
  }
  let navigations = 0;
  await page.route('**/admin/cats/local', route => {
    navigations++;
    return route.fulfill({ contentType: 'text/html', headers: { 'Cache-Control': 'no-store' }, body: `<!doctype html><html lang="de"><head><meta name="viewport" content="width=device-width"><link rel="stylesheet" href="/shared-care.css"></head><body>
      <h1>Own-A-unique</h1><section class="shared-care" aria-label="Geteilte Betreuung"><h2>Geteilte Betreuung</h2><div data-shared-care data-organization-id="${orgA}" data-chip-id="CHIP" aria-live="polite"></div></section>
      <form onsubmit="event.preventDefault()"><button>Abmelden</button></form><script type="module" src="/admin-shared-care.js"></script></body></html>` });
  });
  await page.goto('/admin/cats/local'); await expect(shared(page)).toContainText('Foreign-B-unique');
  expect(requests.find(r => r.path.endsWith('/visits'))?.body.limit).toBe(20);
  await shared(page).getByRole('button', { name: 'Ältere Besuche' }).click();
  await expect(shared(page)).toContainText('Seite 2');
  expect(requests.filter(r => r.path.endsWith('/resolve'))).toHaveLength(2);
  state.revoked = true;
  await page.evaluate(() => { window.dispatchEvent(new Event('blur')); window.dispatchEvent(new Event('focus')); });
  await expect.poll(() => navigations).toBe(2);
  await expect(shared(page)).toContainText('Keine freigegebene Betreuung');
  state.revoked = false; await shared(page).getByRole('button', { name: 'Freigaben neu prüfen' }).click();
  await expect(shared(page)).toContainText('Foreign-B-unique');
  await page.getByRole('button', { name: 'Abmelden', exact: true }).click();
  await expect(shared(page)).not.toContainText('Foreign-B-unique');
  expect(page.url()).toMatch(/\/admin\/cats\/local$/);
});
