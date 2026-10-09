import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';

const { get, csrf, auth, listeners } = vi.hoisted(() => ({
  get: vi.fn(), csrf: vi.fn(),
  auth: { user: { userId: 'user', email: 'staff@example.org', memberships: [
    { organizationId: 'org-a', status: 'ACTIVE', role: 'MEMBER' },
  ] }, activeOrganizationId: 'org-a' as string | null, sessionVerified: true }, listeners: new Set<() => void>(),
}));
vi.mock('../api/client', () => ({ api: { GET: get } }));
vi.mock('../platform/auth', () => ({ getAuthState: () => auth,
  subscribeAuth: (listener: () => void) => { listeners.add(listener); return () => listeners.delete(listener); },
  fetchCsrfToken: csrf,
}));
import { FeedingSiteDetail, FeedingSitesOverview, FeedingSitesPage, currentlyAssignedDeployments,
  isLegacyNodesPath, parseFeedingSitePath } from './FeedingSites';
import { assets } from '../assets';

function ok(data: unknown) { return { data, response: { ok: true, status: 200 } }; }
function changed() { for (const listener of listeners) listener(); }
const siteA = { id: 'site-a', organizationId: 'org-a', name: 'Am Friedhof', locationLabel: 'Hinter der Kapelle', description: 'Neben dem Tor' };
const siteB = { id: 'site-b', organizationId: 'org-a', name: 'Scheune' };
const nodeGreen = { nodeId: 'node-green', organizationId: 'org-a', state: 'CLAIMED', displayName: 'Der Grüne' };
const nodeSilver = { nodeId: 'node-silver', organizationId: 'org-a', state: 'CLAIMED', displayName: 'Der Silberne' };
const nodeUnnamed = { nodeId: 'node-plain', organizationId: 'org-a', state: 'CLAIMED' };

beforeEach(() => {
  get.mockReset(); csrf.mockReset(); csrf.mockResolvedValue('csrf');
  (auth as { user: unknown }).user = { userId: 'user', email: 'staff@example.org', memberships: [
    { organizationId: 'org-a', status: 'ACTIVE', role: 'MEMBER' },
  ] };
  auth.activeOrganizationId = 'org-a';
  auth.sessionVerified = true;
  changed();
  get.mockImplementation(async () => ok([]));
  Object.defineProperty(navigator, 'onLine', { configurable: true, value: true });
});
afterEach(cleanup);

test('routing helpers keep detail sections active and legacy nodes intentional', async () => {
  expect(parseFeedingSitePath('/feeding-sites')).toEqual({ kind: 'overview' });
  expect(parseFeedingSitePath('/feeding-sites/')).toEqual({ kind: 'overview' });
  expect(parseFeedingSitePath('/feeding-sites/site-a')).toEqual({ kind: 'detail', siteId: 'site-a' });
  expect(parseFeedingSitePath('/feeding-sites/a%20b')).toEqual({ kind: 'detail', siteId: 'a b' });
  expect(parseFeedingSitePath('/feeding-sites/a/b')).toEqual({ kind: 'unknown' });
  expect(parseFeedingSitePath('/feeding-sites/')).toEqual({ kind: 'overview' });
  expect(parseFeedingSitePath('/sites')).toEqual({ kind: 'unknown' });
  expect(isLegacyNodesPath('/nodes')).toBe(true);
  expect(isLegacyNodesPath('/nodes/anything')).toBe(true);
  expect(isLegacyNodesPath('/feeding-sites')).toBe(false);
  const { sectionPath } = await import('../ui/navigation');
  expect(sectionPath('/feeding-sites/site-a')).toBe('/feeding-sites');
  expect(sectionPath('/nodes')).toBe('/feeding-sites');
  expect(sectionPath('/nodes/old-id')).toBe('/feeding-sites');
});

test('currently assigned bowls respect validity windows for moved bowls', () => {
  const history = [
    { id: 'd1', nodeId: 'n', feedingSiteId: 'site-a', validFrom: '2025-01-01T00:00:00Z', validUntil: '2025-06-01T00:00:00Z' },
    { id: 'd2', nodeId: 'n', feedingSiteId: 'site-b', validFrom: '2025-06-01T00:00:00Z' },
  ];
  expect(currentlyAssignedDeployments(history, 'site-a')).toHaveLength(0);
  expect(currentlyAssignedDeployments(history, 'site-b')).toHaveLength(1);
  expect(currentlyAssignedDeployments([], 'site-a')).toHaveLength(0);
  expect(currentlyAssignedDeployments(history, undefined)).toHaveLength(0);
  // Future and invalid windows are never current.
  expect(currentlyAssignedDeployments([
    { id: 'd3', nodeId: 'n', feedingSiteId: 'site-b', validFrom: new Date(Date.now() + 86_400_000).toISOString() },
    { id: 'd4', nodeId: 'n', feedingSiteId: 'site-b', validFrom: 'not-a-date' },
  ], 'site-b')).toHaveLength(0);
});

test('overview lists feeding-site names primarily with bowl counts and no admin controls', async () => {
  get.mockImplementation(async (path: string) => ok(path.endsWith('/feeding-sites') ? [siteA, siteB]
    : path.endsWith('/deployments') ? [
      { id: 'd1', nodeId: nodeGreen.nodeId, feedingSiteId: 'site-a', validFrom: '2025-01-01T00:00:00Z' },
      { id: 'd2', nodeId: nodeSilver.nodeId, feedingSiteId: 'site-a', validFrom: '2025-01-01T00:00:00Z' },
    ] : path.endsWith('/nodes') ? [nodeGreen, nodeSilver] : []));
  render(<FeedingSitesOverview organizationId="org-a"/>);
  expect(await screen.findByRole('heading', { name: 'Futterstellen' })).toBeTruthy();
  const cards = await screen.findAllByRole('heading', { level: 2 });
  expect(cards.map(h => h.textContent)).toEqual(['Am Friedhof', 'Scheune']);
  expect(screen.getByText('Hinter der Kapelle')).toBeTruthy();
  expect(screen.getByText('2 Näpfe zugeordnet.')).toBeTruthy();
  expect(screen.getByText('Aktuell keine Näpfe zugeordnet.')).toBeTruthy();
  expect(screen.getByRole('link', { name: 'Am Friedhof' }).getAttribute('href')).toBe('/feeding-sites/site-a');
  expect(screen.queryByRole('button', { name: /anlegen|bearbeiten|löschen|umhängen|speichern/i })).toBeNull();
  expect(screen.queryByText(/node-green|node-silver/)).toBeNull();
  // Review finding: exactly one link per card (no duplicate title + action links to the same detail).
  const cardLinks = screen.getAllByRole('link').filter(link => link.getAttribute('href')?.startsWith('/feeding-sites/'));
  expect(cardLinks).toHaveLength(2);
  for (const link of cardLinks) expect(link.classList.contains('feeding-site-name-link')).toBe(true);
});

test('overview renders a site without id without a broken deep link', async () => {
  get.mockImplementation(async (path: string) => ok(path.endsWith('/feeding-sites') ? [{ organizationId: 'org-a', name: 'Namenlos' }] : []));
  render(<FeedingSitesOverview organizationId="org-a"/>);
  expect(await screen.findByRole('heading', { name: 'Namenlos' })).toBeTruthy();
  expect(screen.queryByRole('link', { name: 'Namenlos' })).toBeNull();
});

test('overview empty, loading and error states use shared status patterns', async () => {
  get.mockImplementation(async () => ok([]));
  render(<FeedingSitesOverview organizationId="org-a"/>);
  expect(await screen.findByText('Keine Futterstellen vorhanden.')).toBeTruthy();
  expect(screen.queryByRole('button', { name: /anlegen/i })).toBeNull();
  cleanup();
  get.mockResolvedValue({ error: {}, response: { ok: false, status: 500 } });
  render(<FeedingSitesOverview organizationId="org-a"/>);
  expect(await screen.findByText('Server nicht erreichbar. Bitte erneut versuchen.')).toBeTruthy();
  get.mockResolvedValue(ok([]));
  fireEvent.click(screen.getByRole('button', { name: 'Erneut versuchen' }));
  expect(await screen.findByText('Keine Futterstellen vorhanden.')).toBeTruthy();
});

test('detail shows location, currently assigned bowls and historical cat activity', async () => {
  get.mockImplementation(async (path: string) => {
    if (path.endsWith('/cat-activity')) return ok([
      { chipId: 'LUNA', catId: 'cat-luna', catName: 'Luna', lastReliableSightingAt: '2026-05-01T12:00:30Z', lastReceivedAt: '2026-05-01T12:01:00Z', visitCount: 2 },
      { chipId: 'UNKNOWN-CHIP', catId: null, catName: null, lastReliableSightingAt: null, lastReceivedAt: '2026-05-01T12:02:00Z', visitCount: 0 },
    ]);
    if (path.includes('/feeding-sites/{siteId}')) return ok(siteA);
    if (path.endsWith('/deployments')) return ok([
      { id: 'd1', nodeId: nodeGreen.nodeId, feedingSiteId: 'site-a', validFrom: '2025-01-01T00:00:00Z' },
      { id: 'd-old', nodeId: nodeSilver.nodeId, feedingSiteId: 'site-a', validFrom: '2024-01-01T00:00:00Z', validUntil: '2024-06-01T00:00:00Z' },
    ]);
    if (path.endsWith('/nodes')) return ok([nodeGreen, nodeSilver]);
    return ok([]);
  });
  render(<FeedingSiteDetail organizationId="org-a" siteId="site-a"/>);
  expect(await screen.findByRole('heading', { name: 'Am Friedhof' })).toBeTruthy();
  expect(screen.getByText('Hinter der Kapelle')).toBeTruthy();
  expect(screen.getByText('Neben dem Tor')).toBeTruthy();
  expect(screen.getByText('Zugeordnete Näpfe (1)')).toBeTruthy();
  expect(screen.getByText('Der Grüne')).toBeTruthy();
  expect(screen.getByText(nodeGreen.nodeId)).toBeTruthy();
  expect(screen.queryByText('Der Silberne')).toBeNull();
  expect(screen.getByText('Luna')).toBeTruthy();
  expect(screen.getByText('Unbekannter Chip')).toBeTruthy();
  expect(screen.getByText(/Zuletzt verlässlich gesehen/)).toBeTruthy();
  expect(screen.getByText(/Keine verlässliche Sichtungszeit/)).toBeTruthy();
  const receipts = screen.getAllByText(/Serverempfang \(keine Sichtungszeit\)/);
  expect(receipts).toHaveLength(2);
  expect(screen.getByText('2 verlässliche Besuche')).toBeTruthy();
  expect(screen.getByText(/Historische Futterstellenzuordnung/)).toBeTruthy();
  expect(screen.getByRole('link', { name: '← Zurück zu Futterstellen' }).getAttribute('href')).toBe('/feeding-sites');
  expect(screen.getByRole('link', { name: 'Alle Katzen und Chips ansehen' }).getAttribute('href')).toBe('/cats');
  expect(screen.queryByRole('button', { name: /anlegen|bearbeiten|löschen|umhängen|speichern/i })).toBeNull();
});

test('detail distinguishes unnamed cats, shows honest location fallback and empty states', async () => {
  get.mockImplementation(async (path: string) => {
    if (path.endsWith('/cat-activity')) return ok([]);
    if (path.includes('/feeding-sites/{siteId}')) return ok({ id: 'site-b', organizationId: 'org-a', name: 'Scheune' });
    return ok([]);
  });
  render(<FeedingSiteDetail organizationId="org-a" siteId="site-b"/>);
  expect(await screen.findByRole('heading', { name: 'Scheune' })).toBeTruthy();
  expect(screen.getByText('Keine Ortsangaben vorhanden.')).toBeTruthy();
  expect(screen.getByText('Zugeordnete Näpfe (0)')).toBeTruthy();
  expect(screen.getByText('Keine Näpfe zugeordnet.')).toBeTruthy();
  expect(await screen.findByText('Noch keine Sichtungen an dieser Futterstelle.')).toBeTruthy();
});

test('detail shows unnamed bowls and registered cats without names honestly', async () => {
  get.mockImplementation(async (path: string) => {
    if (path.endsWith('/cat-activity')) return ok([
      { chipId: 'CHIP', catId: 'cat-id', catName: null, lastReliableSightingAt: null, lastReceivedAt: null, visitCount: 0 },
    ]);
    if (path.includes('/feeding-sites/{siteId}')) return ok(siteA);
    if (path.endsWith('/deployments')) return ok([
      { id: 'd1', nodeId: nodeUnnamed.nodeId, feedingSiteId: 'site-a', validFrom: '2025-01-01T00:00:00Z' },
      { id: 'd2', nodeId: nodeGreen.nodeId, feedingSiteId: 'site-a', validFrom: '2025-01-01T00:00:00Z' },
    ]);
    if (path.endsWith('/nodes')) return ok([nodeUnnamed, nodeGreen]);
    return ok([]);
  });
  render(<FeedingSiteDetail organizationId="org-a" siteId="site-a"/>);
  // Named bowls sort before unnamed ones; the unnamed cat keeps its own honest label.
  await screen.findByText('Der Grüne');
  const bowlNames = [...document.querySelectorAll('.bowl-list .bowl-name')].map(el => el.textContent);
  expect(bowlNames).toHaveLength(2);
  expect(bowlNames[0]).toContain('Der Grüne');
  expect(bowlNames[1]).toContain('Ohne Namen');
  expect(screen.getByText(nodeUnnamed.nodeId)).toBeTruthy();
  expect(screen.getByText(nodeGreen.nodeId)).toBeTruthy();
  expect(screen.getByText('Zugeordnete Näpfe (2)')).toBeTruthy();
});

test('detail foreign site does not leak data and offers retry', async () => {
  get.mockImplementation(async (path: string) => {
    if (path.includes('/feeding-sites/')) return { error: {}, response: { ok: false, status: 404 } };
    return ok([]);
  });
  render(<FeedingSiteDetail organizationId="org-a" siteId="foreign"/>);
  expect(await screen.findByText('Eintrag in dieser Organisation nicht verfügbar.')).toBeTruthy();
  get.mockImplementation(async (path: string) => {
    if (path.endsWith('/cat-activity')) return ok([]);
    if (path.includes('/feeding-sites/{siteId}')) return ok(siteA);
    return ok([]);
  });
  fireEvent.click(screen.getByRole('button', { name: 'Erneut versuchen' }));
  expect(await screen.findByRole('heading', { name: 'Am Friedhof' })).toBeTruthy();
});

test('page preserves tenant, offline and auth gates without admin controls', async () => {
  get.mockImplementation(async (path: string) => ok(path.endsWith('/feeding-sites') ? [siteA] : []));
  const view = render(<FeedingSitesPage path="/feeding-sites"/>);
  expect(await screen.findByText('Am Friedhof')).toBeTruthy();
  expect(get.mock.calls[0][1].params.path.organizationId).toBe('org-a');
  view.unmount(); cleanup();
  get.mockClear();
  auth.activeOrganizationId = null; changed();
  render(<FeedingSitesPage path="/feeding-sites"/>);
  expect(screen.getByText('Bitte eine aktive Organisation auswählen.')).toBeTruthy();
  expect(get).not.toHaveBeenCalled();
  cleanup();
  get.mockClear();
  auth.activeOrganizationId = 'org-a'; changed();
  Object.defineProperty(navigator, 'onLine', { configurable: true, value: false });
  render(<FeedingSitesPage path="/feeding-sites"/>);
  expect(screen.getByText(/Verwaltung benötigt eine Serververbindung/)).toBeTruthy();
  expect(get).not.toHaveBeenCalled();
  Object.defineProperty(navigator, 'onLine', { configurable: true, value: true });
  cleanup();
  render(<FeedingSitesPage path="/feeding-sites/site-a"/>);
  expect(screen.queryByText('Am Friedhof')).toBeNull();
  cleanup();
  render(<FeedingSitesPage path="/sites"/>);
  expect(screen.getByRole('heading', { name: 'Seite nicht gefunden' })).toBeTruthy();
});

test('all feeding-site graphics resolve through the central registry', async () => {
  const original = { site: assets.placeholders.feedingSite, bowl: assets.placeholders.bowl, cat: assets.placeholders.cat };
  try {
    assets.placeholders.feedingSite = '/replacement-site.svg';
    assets.placeholders.bowl = '/replacement-bowl.svg';
    assets.placeholders.cat = '/replacement-cat.svg';
    get.mockImplementation(async (path: string) => {
      if (path.endsWith('/cat-activity')) return ok([
        { chipId: 'CHIP', catId: null, catName: null, lastReliableSightingAt: null, lastReceivedAt: null, visitCount: 0 },
      ]);
      if (path.includes('/feeding-sites/{siteId}')) return ok(siteA);
      if (path.endsWith('/deployments')) return ok([{ id: 'd1', nodeId: nodeGreen.nodeId, feedingSiteId: 'site-a', validFrom: '2025-01-01T00:00:00Z' }]);
      if (path.endsWith('/nodes')) return ok([nodeGreen]);
      if (path.endsWith('/feeding-sites')) return ok([siteA]);
      return ok([]);
    });
    const overview = render(<FeedingSitesOverview organizationId="org-a"/>);
    await screen.findByText('Am Friedhof');
    expect(overview.container.querySelector('.feeding-site-illustration')?.getAttribute('src')).toBe('/replacement-site.svg');
    overview.unmount(); cleanup();
    const detail = render(<FeedingSiteDetail organizationId="org-a" siteId="site-a"/>);
    await detail.findByText('Der Grüne');
    const images = [...detail.container.querySelectorAll('img')].map(img => img.getAttribute('src'));
    expect(images).toContain('/replacement-site.svg');
    expect(images).toContain('/replacement-bowl.svg');
    expect(images).toContain('/replacement-cat.svg');
    for (const image of detail.container.querySelectorAll('img')) {
      expect(image.getAttribute('alt')).toBe('');
      expect(image.getAttribute('aria-hidden')).toBe('true');
    }
  } finally {
    assets.placeholders.feedingSite = original.site;
    assets.placeholders.bowl = original.bowl;
    assets.placeholders.cat = original.cat;
  }
});

test('overview and detail expose keyboard-reachable native navigation', async () => {
  get.mockImplementation(async (path: string) => {
    if (path.endsWith('/cat-activity')) return ok([]);
    if (path.includes('/feeding-sites/{siteId}')) return ok(siteA);
    if (path.endsWith('/feeding-sites')) return ok([siteA]);
    return ok([]);
  });
  render(<FeedingSitesOverview organizationId="org-a"/>);
  const link = await screen.findByRole('link', { name: 'Am Friedhof' });
  link.focus();
  expect(document.activeElement).toBe(link);
  cleanup();
  render(<FeedingSiteDetail organizationId="org-a" siteId="site-a"/>);
  await screen.findByRole('heading', { name: 'Am Friedhof' });
  const back = screen.getByRole('link', { name: '← Zurück zu Futterstellen' });
  back.focus();
  expect(document.activeElement).toBe(back);
});
