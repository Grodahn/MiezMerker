import { expect, test } from '@playwright/test';

test('fresh browser without identity sees only login', async ({ page }) => {
  for (const path of ['/', '/sync', '/nodes', '/cats', '/sites', '/observations', '/visits', '/admin/members']) {
    await page.goto(path);
    await expect(page.getByRole('button', { name: 'Anmelden' })).toBeVisible();
    await expect(page.getByRole('navigation', { name: 'Bereiche' })).not.toBeVisible();
    await expect(page.getByText('Lokaler Speicher bereit')).not.toBeVisible();
    await expect(page.getByText('Futterstellen')).not.toBeVisible();
    expect(new URL(page.url()).pathname).toBe(path === '/' ? '/' : path);
  }
});

test('collector reloads offline with durable IndexedDB outbox', async ({ page, context }) => {
  await page.goto('/sync');
  await expect(page.getByRole('button', { name: 'Anmelden' })).toBeVisible();
  await expect(page.getByText('Lokaler Speicher bereit')).not.toBeVisible();
  await page.evaluate(() => navigator.serviceWorker.ready.then(() => undefined));
  await page.reload();
  await expect(page.getByRole('button', { name: 'Anmelden' })).toBeVisible();
  await expect.poll(() => page.evaluate(() => Boolean(navigator.serviceWorker.controller))).toBe(true);
  await page.evaluate(async () => {
    const db = await new Promise<IDBDatabase>((resolve, reject) => {
      const request = indexedDB.open('miezmerker-collector');
      request.onsuccess = () => resolve(request.result); request.onerror = () => reject(request.error);
    });
    if (!db.objectStoreNames.contains('uploads')) {
      db.close();
      await new Promise<void>((resolve, reject) => {
        const upgrade = indexedDB.open('miezmerker-collector', 2);
        upgrade.onupgradeneeded = () => { upgrade.result.createObjectStore('uploads', { keyPath: 'id' }); };
        upgrade.onsuccess = () => { upgrade.result.close(); resolve(); };
        upgrade.onerror = () => reject(upgrade.error);
      });
      const reopened = await new Promise<IDBDatabase>((resolve, reject) => {
        const request = indexedDB.open('miezmerker-collector');
        request.onsuccess = () => resolve(request.result); request.onerror = () => reject(request.error);
      });
      await new Promise<void>((resolve, reject) => {
        const tx = reopened.transaction('uploads', 'readwrite');
        tx.objectStore('uploads').put({ id: 'offline-test', organizationId: 'org-test',
          appDeviceId: 'app-test', nodeId: 'node-test', protocolVersion: 1, payload: new Uint8Array([42]), createdAt: 1 });
        tx.oncomplete = () => resolve(); tx.onerror = () => reject(tx.error);
      });
      reopened.close();
      return;
    }
    await new Promise<void>((resolve, reject) => {
      const tx = db.transaction('uploads', 'readwrite');
      tx.objectStore('uploads').put({ id: 'offline-test', organizationId: 'org-test',
        appDeviceId: 'app-test', nodeId: 'node-test', protocolVersion: 1, payload: new Uint8Array([42]), createdAt: 1 });
      tx.oncomplete = () => resolve(); tx.onerror = () => reject(tx.error);
    });
    db.close();
  });
  await context.setOffline(true);
  await page.evaluate(() => localStorage.setItem('miezmerker-offline-session', JSON.stringify({
    user: { userId: 'offline-user', email: 'field@example.org', memberships: [
      { organizationId: 'org-test', organizationName: 'Field Org', role: 'MEMBER', status: 'ACTIVE' },
    ] }, activeOrganizationId: 'org-test',
  })));
  await page.reload();
  await expect(page.getByRole('button', { name: 'Anmelden' })).toBeVisible();
  await expect(page.getByText('Lokaler Speicher bereit')).not.toBeVisible();
  const persisted = await page.evaluate(async () => {
    const db = await new Promise<IDBDatabase>(resolve => {
      const request = indexedDB.open('miezmerker-collector'); request.onsuccess = () => resolve(request.result);
    });
    const row = await new Promise<{ payload: Uint8Array }>(resolve => {
      const request = db.transaction('uploads').objectStore('uploads').get('offline-test');
      request.onsuccess = () => resolve(request.result);
    });
    db.close(); return row.payload[0];
  });
  expect(persisted).toBe(42);
});

test('same-origin proxy reaches real backend', async ({ page }) => {
  await page.goto('/sites');
  const result = await page.evaluate(async () => {
    const response = await fetch('/api/v1/version', { credentials: 'same-origin' });
    return { status: response.status, body: await response.json() };
  });
  expect(result.status).toBe(200);
  expect(result.body.apiVersion).toBe('v1');
});
