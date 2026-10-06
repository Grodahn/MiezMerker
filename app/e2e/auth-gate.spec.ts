import { expect, test, type Page } from '@playwright/test';

const user = { userId: 'gate-user', email: 'gate@example.org', memberships: [
  { membershipId: 'gate-membership', organizationId: 'gate-org', organizationName: 'Field Org',
    role: 'MEMBER', status: 'ACTIVE' },
] };

async function backend(page: Page) {
  const state = { expired: false, sessionHold: Promise.resolve(), logoutHold: Promise.resolve() };
  await page.route('**/api/v1/**', async route => {
    const path = new URL(route.request().url()).pathname;
    const send = (data: unknown, status = 200) => route.fulfill({ status,
      contentType: 'application/json', headers: { 'Cache-Control': 'no-store' }, body: JSON.stringify(data) });
    if (path === '/api/v1/auth/session') {
      await state.sessionHold;
      return send(state.expired ? {} : user, state.expired ? 401 : 200);
    }
    if (path === '/api/v1/auth/csrf') return send({ token: 'csrf' });
    if (path === '/api/v1/auth/logout') {
      await state.logoutHold; state.expired = true;
      return route.fulfill({ status: 204 });
    }
    if (path.endsWith('/cats')) return send([{ id: 'cat', chipId: 'CHIP', organizationId: 'gate-org', name: 'Private site' }]);
    if (path.endsWith('/feeding-sites') || path.endsWith('/chip-activity')) return send([]);
    // Offline issuance is intentionally denied: the offline test seeds an
    // already-issued, account/device-bound credential and real Web Crypto keys.
    return send({}, 403);
  });
  return state;
}

test('reconnect verifies the session before showing management', async ({ page, context }) => {
  const state = await backend(page);
  await page.goto('/cats');
  await expect(page.getByRole('cell', { name: 'Private site' })).toBeVisible();
  await context.setOffline(true);
  await expect(page.getByRole('navigation')).not.toBeVisible();
  state.expired = true;
  let release!: () => void;
  state.sessionHold = new Promise(resolve => { release = resolve; });
  await context.setOffline(false);
  await expect(page.getByText('Anmeldung wird geprüft …')).toBeVisible();
  await expect(page.getByRole('navigation')).not.toBeVisible();
  await expect(page.getByRole('cell', { name: 'Private site' })).not.toBeVisible();
  release();
  await expect(page.getByRole('button', { name: 'Anmelden' })).toBeVisible();
});

test('logout clears the shell while its server request is pending', async ({ page }) => {
  const state = await backend(page);
  await page.goto('/cats');
  await expect(page.getByRole('cell', { name: 'Private site' })).toBeVisible();
  let release!: () => void;
  state.logoutHold = new Promise(resolve => { release = resolve; });
  await page.getByRole('button', { name: 'Abmelden' }).click();
  await expect(page.getByRole('button', { name: 'Anmelden' })).toBeVisible();
  await expect(page.getByRole('navigation')).not.toBeVisible();
  await expect(page.getByRole('cell', { name: 'Private site' })).not.toBeVisible();
  release();
});

test('collector account and content are cleared before history freezes the document', async ({ page }) => {
  await backend(page);
  await page.goto('/sync');
  await expect(page.getByText('Lokaler Speicher bereit')).toBeVisible();
  await page.evaluate(() => window.dispatchEvent(new PageTransitionEvent('pagehide', { persisted: true })));
  await expect(page.getByRole('navigation')).not.toBeVisible();
  await expect(page.getByText('Lokaler Speicher bereit')).not.toBeVisible();
  await expect(page.getByText(/Angemeldet als/)).not.toBeVisible();
});

test('cold offline reload allows only sync until the bound credential expires', async ({ page, context }) => {
  await backend(page);
  await page.goto('/sync');
  await expect(page.getByText('Lokaler Speicher bereit')).toBeVisible();
  await page.evaluate(() => navigator.serviceWorker.ready.then(() => undefined));
  await page.reload();
  await expect.poll(() => page.evaluate(() => Boolean(navigator.serviceWorker.controller))).toBe(true);
  await page.clock.install();
  await page.evaluate(async () => {
    const db = await new Promise<IDBDatabase>((resolve, reject) => {
      const request = indexedDB.open('miezmerker-offline-identity');
      request.onsuccess = () => resolve(request.result); request.onerror = () => reject(request.error);
    });
    const keys = await crypto.subtle.generateKey({ name: 'ECDSA', namedCurve: 'P-256' }, false, ['sign', 'verify']);
    await new Promise<void>((resolve, reject) => {
      const tx = db.transaction(['identities', 'credentials'], 'readwrite');
      tx.objectStore('identities').put({ userId: 'gate-user', deviceId: 'gate-device', keys });
      tx.objectStore('credentials').put({ userId: 'gate-user', organizationId: 'gate-org', deviceId: 'gate-device',
        credential: 'previously-issued-credential', expiresAt: Date.now() + 60_000 });
      tx.oncomplete = () => resolve(); tx.onerror = () => reject(tx.error);
    });
    db.close();
  });
  await context.setOffline(true);
  await page.reload();
  await expect(page.getByText('Lokaler Speicher bereit')).toBeVisible();
  await expect(page.getByRole('link', { name: 'Futterstellen' })).not.toBeVisible();
  await expect(page.getByRole('button', { name: 'Anmelden' })).not.toBeVisible();
  await page.clock.fastForward(61_000);
  await expect(page.getByRole('button', { name: 'Anmelden' })).toBeVisible();
  await expect(page.getByText('Lokaler Speicher bereit')).not.toBeVisible();
});
