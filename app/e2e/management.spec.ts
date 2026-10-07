import { expect, test, type Page } from '@playwright/test';
import type { components } from '../src/api/generated';

const orgA = '00000000-0000-4000-8000-000000000001';
const orgB = '00000000-0000-4000-8000-000000000002';
const nodeId = '00000000-0000-4000-8000-000000000010';
const siteId = '00000000-0000-4000-8000-000000000020';
const readId = '00000000-0000-4000-8000-000000000030';
const start = '2025-01-01T00:00:00Z';
const end = '2025-01-01T00:00:30Z';

async function backend(page: Page) {
  const sites: components['schemas']['FeedingSiteView'][] = [{ id: siteId, organizationId: orgA, name: 'Garten' }, { id: 'site-b', organizationId: orgA, name: 'Scheune' }];
  const cats: components['schemas']['CatView'][] = [];
  const deployments: components['schemas']['DeploymentView'][] = [{ id: 'deployment-0', organizationId: orgA, nodeId, feedingSiteId: siteId, validFrom: start, validUntil: null, createdAt: start }];
  const observations: components['schemas']['RawObservationView'][] = [];
  const visits: components['schemas']['VisitView'][] = [];
  const requests: { path: string; method: string; query: string; body: unknown }[] = [];
  await page.route('**/api/v1/**', async route => {
    const request = route.request(); const url = new URL(request.url()); const path = url.pathname;
    const method = request.method(); const body = request.postDataJSON();
    requests.push({ path, method, query: url.search, body });
    const send = (data: unknown, status = 200) => route.fulfill({ status, contentType: 'application/json',
      headers: { 'Cache-Control': 'no-store' }, body: JSON.stringify(data) });
    if (path === '/api/v1/auth/csrf') return send({ token: 'csrf' });
    if (path === '/api/v1/auth/session') return send({ userId: 'user', email: 'staff@example.org', memberships: [
      { membershipId: 'membership-a', organizationId: orgA, organizationName: 'Tierschutz A', role: 'MEMBER', status: 'ACTIVE' },
      { membershipId: 'membership-b', organizationId: orgB, organizationName: 'Tierschutz B', role: 'MEMBER', status: 'ACTIVE' },
    ] });
    if (method !== 'GET' && request.headers()['x-xsrf-token'] !== 'csrf') return send({}, 403);
    const foreign = path.includes(orgB) || url.searchParams.get('organizationId') === orgB;
    if (foreign) return send([]);
    if (path.endsWith('/feeding-sites')) {
      return send(sites);
    }
    if (path.endsWith('/nodes')) return send([{ nodeId, organizationId: orgA, state: 'CLAIMED',
      firmwareVersion: '1.0', protocolVersion: '1', lastContactAt: end }]);
    if (path.endsWith('/deployments/move')) return send({}, 403);
    if (path.endsWith('/deployments')) return send(deployments);
    if (path.endsWith('/cats')) {
      if (method === 'POST') { const created = { ...body, id: 'cat', organizationId: orgA }; cats.push(created); return send(created); }
      return send(cats);
    }
    if (path.endsWith('/chip-activity')) return send(observations.length ? [
      { chipId: 'CHIP-NEW', lastSeenAtMillis: String(Date.parse(end)), lastReceivedAt: end,
        observationCount: observations.length, uncertainClockCount: 1, feedingSiteIds: [siteId] },
    ] : []);
    if (path.endsWith('/visits')) return send(visits);
    return send({}, 403);
  });
  await page.addInitScript(org => {
    if (!sessionStorage.getItem('miezmerker-active-organization')) {
      sessionStorage.setItem('miezmerker-active-organization', JSON.stringify({ userId: 'user', organizationId: org }));
    }
  }, orgA);
  return { sites, cats, deployments, observations, visits, requests };
}

for (const viewport of [{ width: 1280, height: 900 }, { width: 390, height: 844 }]) {
  test(`field workflow and organization isolation at ${viewport.width}px`, async ({ page }) => {
    await page.setViewportSize(viewport);
    const state = await backend(page);
    await page.goto('/nodes');
    const nav = page.getByRole('navigation', { name: 'Bereiche' });
    await expect(nav.getByRole('link')).toHaveText(['Vor-Ort-Sync', 'Nodes', 'Katzen']);
    for (const label of ['Mitglieder', 'Futterstellen', 'Rohbeobachtungen', 'Besuche']) {
      await expect(nav.getByRole('link', { name: label, exact: true })).toHaveCount(0);
    }
    await expect(page.getByRole('cell', { name: 'Garten', exact: true })).toBeVisible();
    await page.getByRole('button', { name: 'Details' }).click();
    await expect(page.getByText('Aktuelle Futterstelle: Garten')).toBeVisible();
    await expect(page.getByText(/Die Futterstellenzuordnung wird im Admin-Backend verwaltet/)).toBeVisible();
    await expect(page.getByLabel('Neue Futterstelle')).toHaveCount(0);
    await expect(page.getByRole('button', { name: 'Zuordnung speichern' })).toHaveCount(0);
    const history = page.getByRole('region', { name: 'Deployment-Historie' });
    await expect(history.getByRole('cell', { name: 'Garten', exact: true })).toBeVisible();
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    state.observations.push({ id: readId, chipId: 'CHIP-NEW' });
    state.visits.push({ id: 'visit', chipId: 'CHIP-NEW', feedingSiteId: siteId,
      startAtMillis: String(Date.parse(start)), endAtMillis: String(Date.parse(end)), observationCount: 2, algorithmVersion: 'visit-gap-v1' });
    await page.getByRole('link', { name: 'Katzen', exact: true }).click();
    await page.getByRole('button', { name: 'Katze dazu anlegen' }).click();
    await expect(page.getByRole('cell', { name: 'visit-gap-v1', exact: false })).toBeVisible();
    const editor = page.getByRole('region', { name: 'Katze pflegen' });
    await expect(editor.getByLabel('Chip-ID')).toHaveValue('CHIP-NEW');
    await editor.getByLabel('Name (optional)').fill('Miez');
    await editor.getByRole('button', { name: 'Speichern' }).click();
    await expect(page.getByRole('cell', { name: /Miez/ })).toBeVisible();
    await page.getByLabel('Aktive Organisation').selectOption(orgB);
    await expect(page.getByRole('cell', { name: /Miez/ })).not.toBeVisible();
    await expect(page.getByText(/Noch keine Katzen oder Chips/)).toBeVisible();
    for (const route of ['/sites', '/observations', '/visits']) {
      await page.goto(route);
      await expect(page.getByRole('heading', { name: 'Seite nicht gefunden' })).toBeVisible();
      await expect(page.getByRole('button', { name: 'Futterstelle anlegen' })).toHaveCount(0);
    }
    await page.goto('/sync');
    await expect(page.getByText('Lokaler Speicher bereit')).toBeVisible();
    expect(state.requests.filter(r => r.path.endsWith('/deployments/move'))).toHaveLength(0);
    expect(state.requests.some(r => r.path.endsWith('/members') || r.path.endsWith('/visits/recompute') || r.path === '/api/v1/observations')).toBe(false);
    const cached = await page.evaluate(async () => {
      const urls: string[] = [];
      for (const key of await caches.keys()) for (const request of await (await caches.open(key)).keys()) urls.push(request.url);
      return urls;
    });
    expect(cached.some(url => /^\/(api|admin)(\/|$)/.test(new URL(url).pathname))).toBe(false);
  });
}

test('history lifecycle discards cat data and drafts and restores the latest organization', async ({ page }) => {
  const state = await backend(page);
  state.cats.push({ id: 'cat', chipId: 'CHIP', name: 'Private A cat' });
  await page.goto('/cats');
  await expect(page.getByRole('cell', { name: /Private A cat/ })).toBeVisible();
  await page.getByRole('button', { name: 'Katze anlegen' }).click();
  await page.getByLabel('Name (optional)').fill('Private A draft');
  await page.evaluate(() => window.dispatchEvent(new PageTransitionEvent('pagehide', { persisted: true })));
  await expect(page.getByRole('cell', { name: /Private A cat/ })).not.toBeVisible();
  await expect(page.getByLabel('Name (optional)')).not.toBeVisible();
  await page.evaluate(org => {
    const offline = JSON.parse(localStorage.getItem('miezmerker-offline-session')!);
    offline.activeOrganizationId = org;
    localStorage.setItem('miezmerker-offline-session', JSON.stringify(offline));
    sessionStorage.setItem('miezmerker-active-organization', JSON.stringify({ userId: offline.user.userId, organizationId: org }));
  }, orgB);
  await Promise.all([page.waitForEvent('load'), page.evaluate(() => window.dispatchEvent(new PageTransitionEvent('pageshow', { persisted: true })))]);
  await expect(page.getByLabel('Aktive Organisation')).toHaveValue(orgB);
  await expect(page.getByText(/Noch keine Katzen oder Chips/)).toBeVisible();
  await expect(page.getByRole('cell', { name: /Private A cat/ })).not.toBeVisible();
  await expect(page.getByLabel('Name (optional)')).not.toBeVisible();
});
