import { expect, test } from '@playwright/test';

test('sync event outbox survives offline reload and lost receipt without adopting another owner', async ({ page, context }) => {
  const user = { userId: 'event-user', email: 'events@example.org', memberships: [
    { organizationId: 'event-org', organizationName: 'Events', role: 'MEMBER', status: 'ACTIVE' },
  ] };
  const uploads: Array<Record<string, unknown>> = [];
  await page.route('**/api/v1/**', async route => {
    const path = new URL(route.request().url()).pathname;
    if (path === '/api/v1/auth/session') return route.fulfill({ json: user });
    if (path === '/api/v1/auth/csrf') return route.fulfill({ json: { token: 'token' } });
    if (path === '/api/v1/collector-sync-events') {
      const body = route.request().postDataJSON(); uploads.push(body);
      if (uploads.length === 1) return route.fulfill({ status: 503, json: {} });
      return route.fulfill({ json: { eventId: body.eventId, status: 'DUPLICATE_IDENTICAL', resolution: 'UNRESOLVED' } });
    }
    return route.fulfill({ json: [] });
  });
  await page.goto('/sync');
  await expect(page.getByRole('heading', { name: 'Vor-Ort-Sync', exact: true })).toBeVisible();
  await page.evaluate(() => navigator.serviceWorker.ready.then(() => undefined));
  await page.reload();
  await expect.poll(() => page.evaluate(() => Boolean(navigator.serviceWorker.controller))).toBe(true);
  await page.evaluate(async () => {
    const db = await new Promise<IDBDatabase>((resolve, reject) => {
      const request = indexedDB.open('miezmerker-collector'); request.onsuccess = () => resolve(request.result); request.onerror = () => reject(request.error);
    });
    await new Promise<void>((resolve, reject) => {
      const tx = db.transaction('syncEvents', 'readwrite');
      for (const [eventId, ownerUserId, expiresAt] of [['own-event', 'event-user', Date.now() + 86400_000],
        ['foreign-event', 'someone-else', Date.now() + 86400_000], ['expired-event', 'event-user', Date.now() - 1]] as const) {
        tx.objectStore('syncEvents').add({ eventId, ownerUserId, expiresAt, organizationId: 'event-org',
          nodeId: '44444444-4444-4444-8444-444444444444', incarnation: '55555555-5555-4555-9555-555555555555',
          occurredAt: Date.now(), timeQuality: 'UNKNOWN' });
      }
      tx.oncomplete = () => resolve(); tx.onerror = () => reject(tx.error);
    }); db.close();
  });
  const pending = () => page.evaluate(async () => {
    const db = await new Promise<IDBDatabase>(resolve => { const r = indexedDB.open('miezmerker-collector'); r.onsuccess = () => resolve(r.result); });
    const rows = await new Promise<string[]>(resolve => {
      const r = db.transaction('syncEvents').objectStore('syncEvents').getAllKeys(); r.onsuccess = () => resolve(r.result as string[]);
    }); db.close(); return rows.sort();
  });
  await context.setOffline(true); await page.reload();
  await expect.poll(pending).toEqual(['foreign-event', 'own-event']);
  expect(uploads).toHaveLength(0);
  await context.setOffline(false);
  await expect.poll(() => uploads.length).toBe(1);
  await expect.poll(pending).toEqual(['foreign-event', 'own-event']);
  await page.reload();
  await expect.poll(() => uploads.length).toBe(2);
  await expect.poll(pending).toEqual(['foreign-event']);
  expect(uploads[0]).toEqual(uploads[1]); expect(uploads[0].ownerUserId).toBe('event-user');
  expect(uploads[0]).not.toHaveProperty('expiresAt');
});
