import { expect, test } from '@playwright/test';

test('collector reloads offline with durable IndexedDB outbox', async ({ page, context }) => {
  await page.goto('/sync');
  await expect(page.getByText('Lokaler Speicher bereit')).toBeVisible();
  await page.evaluate(() => navigator.serviceWorker.ready.then(() => undefined));
  // A prompt-based first worker controls the next navigation, not the already-open page.
  await page.reload();
  await expect(page.getByText('Lokaler Speicher bereit')).toBeVisible();
  await expect.poll(() => page.evaluate(() => Boolean(navigator.serviceWorker.controller))).toBe(true);
  await page.evaluate(async () => {
    const db = await new Promise<IDBDatabase>((resolve, reject) => {
      const request = indexedDB.open('miezmerker-collector');
      request.onsuccess = () => resolve(request.result); request.onerror = () => reject(request.error);
    });
    await new Promise<void>((resolve, reject) => {
      const tx = db.transaction('uploads', 'readwrite');
      tx.objectStore('uploads').put({ id: 'offline-test', organizationId: 'org-test',
        appDeviceId: 'app-test', nodeId: 'node-test', protocolVersion: 1, payload: new Uint8Array([42]), createdAt: 1 });
      tx.oncomplete = () => resolve(); tx.onerror = () => reject(tx.error);
    });
    db.close();
  });
  await context.setOffline(true);
  await page.reload();
  await expect(page.getByText('Lokaler Speicher bereit')).toBeVisible();
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
