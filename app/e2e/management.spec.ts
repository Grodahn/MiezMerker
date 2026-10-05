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
  const sites: components['schemas']['FeedingSiteView'][] = [];
  const cats: components['schemas']['CatView'][] = [];
  const deployments: components['schemas']['DeploymentView'][] = [];
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
      { membershipId: 'membership-a', organizationId: orgA, organizationName: 'Tierschutz A', role: 'ADMIN', status: 'ACTIVE' },
      { membershipId: 'membership-b', organizationId: orgB, organizationName: 'Tierschutz B', role: 'MEMBER', status: 'ACTIVE' },
    ] });
    if (method !== 'GET' && request.headers()['x-xsrf-token'] !== 'csrf') return send({}, 403);
    const foreign = path.includes(orgB) || url.searchParams.get('organizationId') === orgB;
    if (foreign) return send([]);
    if (path.endsWith('/feeding-sites')) {
      if (method === 'POST') {
        const created = { ...body, id: sites.length ? '00000000-0000-4000-8000-000000000021' : siteId, organizationId: orgA };
        sites.push(created); return send(created);
      }
      return send(sites);
    }
    if (path.endsWith('/nodes')) return send([{ nodeId, organizationId: orgA, state: 'CLAIMED',
      firmwareVersion: '1.0', protocolVersion: '1', lastContactAt: end }]);
    if (path.endsWith('/deployments/move')) {
      const open = deployments.find(d => !d.validUntil);
      if (open) open.validUntil = body.validFrom;
      const created = { ...body, id: `deployment-${deployments.length}`, organizationId: orgA };
      deployments.push(created); return send(created);
    }
    if (path.endsWith('/deployments')) return send(deployments);
    if (path.endsWith('/cats')) {
      if (method === 'POST') { const created = { ...body, id: 'cat', organizationId: orgA }; cats.push(created); return send(created); }
      return send(cats);
    }
    if (path.endsWith('/chip-activity')) return send(observations.length ? [
      { chipId: 'CHIP-NEW', lastSeenAtMillis: String(Date.parse(end)), lastReceivedAt: end,
        observationCount: observations.length, uncertainClockCount: 1, feedingSiteIds: [siteId] },
    ] : []);
    if (path === '/api/v1/observations') {
      const chip = url.searchParams.get('chipId'); const site = url.searchParams.get('feedingSiteId');
      return send(observations.filter(row => (!chip || row.chipId === chip) && (!site || row.feedingSiteId === site)));
    }
    if (path.endsWith('/visits/recompute')) {
      visits.splice(0, visits.length, { id: 'visit', organizationId: orgA, feedingSiteId: siteId, chipId: 'CHIP-NEW',
        startAt: start, endAt: end, startAtMillis: String(Date.parse(start)), endAtMillis: String(Date.parse(end)),
        observationCount: 2, algorithmVersion: 'visit-gap-v1', gapSeconds: 60,
        firstObservationId: readId, lastObservationId: readId });
      return send({ visits, visitCount: 1, algorithmVersion: 'visit-gap-v1', excludedUnknownClock: 1 });
    }
    if (path.endsWith('/visits')) return send(visits);
    if (path.endsWith('/members')) return send([{ membershipId: 'member', email: 'staff@example.org', role: 'ADMIN', status: 'ACTIVE' }]);
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
  test(`management workflow and organization isolation at ${viewport.width}px`, async ({ page }) => {
    await page.setViewportSize(viewport);
    const state = await backend(page);
    await page.goto('/sites');
    await expect(page.getByText(/Noch keine Futterstellen vorhanden/)).toBeVisible();
    await page.getByRole('button', { name: 'Futterstelle anlegen' }).click();
    await page.getByLabel('Name / Bezeichnung').fill('Garten');
    await page.getByLabel('Ort / Wegbeschreibung').fill('Hinter dem Haus');
    await page.getByRole('button', { name: 'Speichern', exact: true }).click();
    await expect(page.getByRole('cell', { name: 'Garten', exact: true })).toBeVisible();
    await page.getByRole('button', { name: 'Futterstelle anlegen' }).click();
    await page.getByLabel('Name / Bezeichnung').fill('Scheune');
    await page.getByRole('button', { name: 'Speichern', exact: true }).click();
    await expect(page.getByRole('cell', { name: 'Scheune', exact: true })).toBeVisible();

    await page.getByRole('link', { name: 'Nodes', exact: true }).click();
    await page.getByRole('button', { name: 'Details / zuordnen' }).click();
    await page.getByLabel('Neue Futterstelle').selectOption(siteId);
    await page.getByLabel('Gültig ab (lokale Uhrzeit)').fill('2024-12-31T00:00');
    await page.getByRole('button', { name: 'Zuordnung speichern' }).click();
    await expect(page.getByRole('cell', { name: 'Garten', exact: true })).toBeVisible();
    state.observations.push(
      { id: readId, organizationId: orgA, nodeId, chipId: 'CHIP-NEW', sequence: '9007199254740993',
        observedAtMillis: String(Date.parse(end)), clockStatus: 'SYNCED', receivedAt: end, feedingSiteId: siteId },
      { id: 'read-unknown', organizationId: orgA, nodeId, chipId: 'CHIP-NEW', sequence: '9007199254740994',
        clockStatus: 'UNKNOWN', receivedAt: end });

    await page.getByRole('link', { name: 'Rohbeobachtungen', exact: true }).click();
    await expect(page.getByText('Uhrzeit unbekannt')).toBeVisible();
    await page.getByRole('button', { name: 'Katze dazu anlegen' }).first().click();
    const editor = page.getByRole('region', { name: 'Katze pflegen' });
    await expect(editor.getByLabel('Chip-ID')).toHaveValue('CHIP-NEW');
    await editor.getByLabel('Name (optional)').fill('Miez');
    await editor.getByRole('button', { name: 'Speichern' }).click();
    await expect(editor).not.toBeVisible();
    await page.getByLabel('Chip-ID', { exact: true }).fill('CHIP-NEW');
    await page.getByRole('combobox', { name: 'Futterstelle', exact: true }).selectOption(siteId);
    await page.getByRole('button', { name: 'Filter anwenden' }).click();
    await expect(page.getByText('Uhrzeit unbekannt')).not.toBeVisible();
    expect(page.url()).not.toContain('CHIP-NEW');

    await page.getByRole('link', { name: 'Besuche', exact: true }).click();
    await page.getByRole('button', { name: 'Besuche neu berechnen' }).click();
    await expect(page.getByRole('cell', { name: /visit-gap-v1/ })).toBeVisible();
    await expect(page.getByText('2 Reads')).toBeVisible();

    await page.getByRole('link', { name: 'Nodes', exact: true }).click();
    await page.getByRole('button', { name: 'Details / zuordnen' }).click();
    await page.getByLabel('Neue Futterstelle').selectOption(state.sites[1].id!);
    await page.getByLabel('Gültig ab (lokale Uhrzeit)').fill('2025-01-02T00:00');
    await page.getByRole('button', { name: 'Zuordnung speichern' }).click();
    await page.getByRole('button', { name: 'Details / zuordnen' }).click();
    await expect(page.getByRole('region', { name: 'Deployment-Historie' }).getByRole('cell', { name: 'Garten', exact: true })).toBeVisible();
    await expect(page.getByRole('region', { name: 'Deployment-Historie' }).getByRole('cell', { name: 'Scheune', exact: true })).toBeVisible();
    await page.screenshot({ path: `../.tools/issue11-nodes-${viewport.width}.png`, fullPage: true });
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);

    await page.getByRole('link', { name: 'Besuche', exact: true }).click();
    await expect(page.getByRole('cell', { name: 'Garten', exact: true })).toBeVisible();
    await page.getByRole('link', { name: 'Katzen', exact: true }).click();
    await expect(page.getByRole('cell', { name: /Miez/ })).toBeVisible();
    await expect(page.getByText('1 Read(s) ohne verlässliche Uhrzeit')).toBeVisible();
    await page.getByLabel('Aktive Organisation').selectOption(orgB);
    await expect(page.getByRole('cell', { name: /Miez/ })).not.toBeVisible();
    await expect(page.getByText(/Noch keine Katzen oder Chips/)).toBeVisible();
    await expect(page.getByRole('link', { name: 'Mitglieder', exact: true })).not.toBeVisible();
    await page.goto('/admin/members');
    await expect(page.getByText('Dieser Bereich ist ADMIN vorbehalten.')).toBeVisible();
    expect(state.requests.some(r => r.path === `/api/v1/organizations/${orgB}/members`)).toBe(false);
    await page.goto('/sync');
    await expect(page.getByText('Lokaler Speicher bereit')).toBeVisible();
    const cached = await page.evaluate(async () => {
      const keys = await caches.keys(); const urls: string[] = [];
      for (const key of keys) { for (const request of await (await caches.open(key)).keys()) urls.push(request.url); }
      return urls;
    });
    expect(cached.some(url => new URL(url).pathname.startsWith('/api/'))).toBe(false);
    expect(state.requests.filter(r => r.method === 'POST' && r.path.endsWith('/deployments/move'))).toHaveLength(2);
  });
}
