import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';

const { get, post, patch, csrf, auth, listeners } = vi.hoisted(() => ({
  get: vi.fn(), post: vi.fn(), patch: vi.fn(), csrf: vi.fn(),
  auth: { user: { userId: 'user', email: 'staff@example.org', memberships: [
    { organizationId: 'org-a', status: 'ACTIVE', role: 'MEMBER' },
    { organizationId: 'org-b', status: 'ACTIVE', role: 'MEMBER' },
  ] }, activeOrganizationId: 'org-a' as string | null, sessionVerified: true }, listeners: new Set<() => void>(),
}));
vi.mock('../api/client', () => ({ api: { GET: get, POST: post, PATCH: patch } }));
vi.mock('../platform/auth', () => ({ getAuthState: () => auth,
  subscribeAuth: (listener: () => void) => { listeners.add(listener); return () => listeners.delete(listener); },
  fetchCsrfToken: csrf,
}));
import { Management } from './Management';
import { millis } from './common';

function ok(data: unknown) { return { data, response: { ok: true, status: 200 } }; }
function changed() { for (const listener of listeners) listener(); }
const site = { id: 'site-a', organizationId: 'org-a', name: 'Garten', description: 'Hinter dem Haus' };
const node = { nodeId: 'node-a', organizationId: 'org-a', state: 'CLAIMED', lastContactAt: '2026-10-01T10:00:00Z' };
beforeEach(() => {
  get.mockReset(); post.mockReset(); patch.mockReset(); csrf.mockReset(); csrf.mockResolvedValue('csrf');
  (auth as { user: unknown }).user = { userId: 'user', email: 'staff@example.org', memberships: [
    { organizationId: 'org-a', status: 'ACTIVE', role: 'MEMBER' },
    { organizationId: 'org-b', status: 'ACTIVE', role: 'MEMBER' },
  ] };
  auth.activeOrganizationId = 'org-a';
  auth.sessionVerified = true;
  changed();
  get.mockImplementation(async () => ok([])); post.mockResolvedValue(ok({})); patch.mockResolvedValue(ok({}));
});
afterEach(cleanup);

test('verification loss aborts pending work even when the offline identity is preserved', async () => {
  const { contextSignal } = await import('./context');
  const controller = contextSignal();
  auth.sessionVerified = false;
  changed();
  expect(controller.signal.aborted).toBe(true);
  expect(auth.user.userId).toBe('user');
  expect(auth.activeOrganizationId).toBe('org-a');
});

test('a batched verification loss and recovery discards management drafts', async () => {
  get.mockImplementation(async (path: string) => ok(path.endsWith('/cats') ? [{ id: 'cat', chipId: 'CHIP', name: 'Garten' }] : []));
  render(<Management path="/cats"/>);
  await screen.findByText('Garten');
  fireEvent.click(screen.getByRole('button', { name: 'Katze anlegen' }));
  fireEvent.change(screen.getByLabelText('Name (optional)'), { target: { value: 'Private draft' } });
  act(() => {
    auth.sessionVerified = false; changed();
    auth.sessionVerified = true; changed();
  });
  expect(screen.queryByLabelText('Name (optional)')).toBeNull();
  await screen.findByText('Garten');
});

test.each([
  ['/nodes', 'Noch keine Nodes vorhanden.'],
  ['/cats', 'Noch keine Katzen oder Chips vorhanden.'],
])('empty installation is usable on %s', async (path, text) => {
  render(<Management path={path}/>);
  expect(await screen.findByText(new RegExp(text))).toBeTruthy();
});

test('node detail shows deployment history without any move control', async () => {
  get.mockImplementation(async (path: string) => ok(path.endsWith('/feeding-sites') ? [site, { ...site, id: 'site-b', name: 'Scheune' }]
    : path.endsWith('/nodes') ? [node] : path.endsWith('/deployments') ? [
      { id: 'deployment-a', nodeId: node.nodeId, feedingSiteId: site.id, validFrom: '2025-01-01T00:00:00Z' },
    ] : []));
  render(<Management path="/nodes"/>);
  await screen.findByText('Garten');
  fireEvent.click(screen.getByRole('button', { name: 'Details' }));
  // The list table stays mounted behind the detail, so scope read
  // assertions to the detail region instead of matching ambiguous text.
  const detail = await screen.findByRole('region', { name: 'Node-Details' });
  expect(within(detail).getByRole('heading', { name: 'Deployment-Historie' })).toBeTruthy();
  expect(within(detail).getByText('Offen')).toBeTruthy();
  expect(within(detail).getByText('Garten')).toBeTruthy();
  expect(screen.queryByLabelText('Neue Futterstelle')).toBeNull();
  expect(screen.queryByRole('button', { name: 'Zuordnung speichern' })).toBeNull();
  expect(post).not.toHaveBeenCalled();
});

test('cats include observed unknown chips and separate last sighting from late server receipt', async () => {
  get.mockImplementation(async (path: string) => ok(path.endsWith('/feeding-sites') ? [site] : path.endsWith('/chip-activity') ? [
    { chipId: 'CHIP-NEW', lastSeenAtMillis: '1735689600000', lastReceivedAt: '2026-10-01T00:00:00Z', uncertainClockCount: 2, feedingSiteIds: ['site-a'] },
  ] : []));
  render(<Management path="/cats"/>);
  expect(await screen.findByText('CHIP-NEW')).toBeTruthy();
  expect(screen.getByText('Unbekannter Chip')).toBeTruthy();
  expect(screen.getByText(/Garten/)).toBeTruthy();
  expect(screen.getByText('2 Read(s) ohne verlässliche Uhrzeit')).toBeTruthy();
  expect(screen.getByText(millis('1735689600000'))).toBeTruthy();
});

test('organization changes clear cats and abort stale A responses, including A -> B -> A', async () => {
  let resolveA: (value: unknown) => void = () => {};
  get.mockImplementation((path: string, options: { params: { path: { organizationId: string } } }) => {
    if (!path.endsWith('/cats')) return Promise.resolve(ok([]));
    if (options.params.path.organizationId === 'org-a') return new Promise(resolve => { resolveA = resolve; });
    return Promise.resolve(ok([{ id: 'cat-b', chipId: 'B', name: 'Org B cat' }]));
  });
  render(<Management path="/cats"/>);
  await vi.waitFor(() => expect(get).toHaveBeenCalledTimes(3));
  const oldSignal = get.mock.calls[0][1].signal as AbortSignal;
  await act(async () => { auth.activeOrganizationId = 'org-b'; changed(); });
  expect(await screen.findByText('Org B cat')).toBeTruthy();
  expect(oldSignal.aborted).toBe(true);
  const firstResolve = resolveA;
  await act(async () => { auth.activeOrganizationId = 'org-a'; changed(); });
  expect(screen.queryByText('Org B cat')).toBeNull();
  await act(async () => firstResolve(ok([{ id: 'cat-a', chipId: 'A', name: 'Stale A cat' }])));
  expect(screen.queryByText('Stale A cat')).toBeNull();
  await act(async () => resolveA(ok([{ id: 'cat-a', chipId: 'A', name: 'Garten' }])));
  expect(await screen.findByText('Garten')).toBeTruthy();
});

test('switching organization while CSRF resolves prevents a mutation in the old context', async () => {
  let resolveCsrf: (value: string) => void = () => {};
  csrf.mockImplementation(() => new Promise(resolve => { resolveCsrf = resolve; }));
  render(<Management path="/cats"/>);
  await screen.findByText(/Noch keine Katzen oder Chips/);
  fireEvent.click(screen.getByRole('button', { name: 'Katze anlegen' }));
  fireEvent.change(screen.getByLabelText('Name (optional)'), { target: { value: 'Old A cat' } });
  fireEvent.change(screen.getByLabelText('Chip-ID'), { target: { value: 'CHIP' } });
  fireEvent.click(screen.getByRole('button', { name: 'Speichern' }));
  await act(async () => { auth.activeOrganizationId = 'org-b'; changed(); resolveCsrf('csrf'); });
  expect(post).not.toHaveBeenCalled(); expect(screen.queryByLabelText('Name (optional)')).toBeNull();
});

test('leaving a management document clears records and drafts before browser history retains it', async () => {
  get.mockImplementation(async (path: string) => ok(path.endsWith('/cats') ? [{ id: 'cat', chipId: 'CHIP', name: 'Garten' }] : []));
  let resolveCsrf: (value: string) => void = () => {};
  csrf.mockImplementation(() => new Promise(resolve => { resolveCsrf = resolve; }));
  render(<Management path="/cats"/>);
  await screen.findByText('Garten');
  const signal = get.mock.calls[0][1].signal as AbortSignal;
  fireEvent.click(screen.getByRole('button', { name: 'Katze anlegen' }));
  fireEvent.change(screen.getByLabelText('Name (optional)'), { target: { value: 'Private draft' } });
  fireEvent.change(screen.getByLabelText('Chip-ID'), { target: { value: 'CHIP' } });
  fireEvent.click(screen.getByRole('button', { name: 'Speichern' }));
  fireEvent(window, new PageTransitionEvent('pagehide', { persisted: true }));
  expect(screen.queryByText('Garten')).toBeNull();
  expect(screen.queryByLabelText('Name (optional)')).toBeNull();
  expect(signal.aborted).toBe(true);
  await act(async () => resolveCsrf('csrf'));
  expect(post).not.toHaveBeenCalled();
});

test('inactive membership, no selected organization and offline management cannot fetch business data', async () => {
  auth.activeOrganizationId = null; changed();
  const view = render(<Management path="/cats"/>);
  expect(screen.getByText('Bitte eine aktive Organisation auswählen.')).toBeTruthy(); expect(get).not.toHaveBeenCalled();
  await act(async () => { auth.activeOrganizationId = 'org-a'; auth.user.memberships[0].status = 'DISABLED'; changed(); });
  expect(get).not.toHaveBeenCalled();
  view.unmount();
  Object.defineProperty(navigator, 'onLine', { configurable: true, value: false });
  auth.user.memberships[0].status = 'ACTIVE'; changed();
  render(<Management path="/cats"/>);
  expect(screen.getByText(/Verwaltung benötigt eine Serververbindung/)).toBeTruthy(); expect(get).not.toHaveBeenCalled();
  Object.defineProperty(navigator, 'onLine', { configurable: true, value: true });
});

test('server failures are actionable without leaking response details', async () => {
  get.mockResolvedValue({ error: { detail: 'PRIVATE-CHIP' }, response: { ok: false, status: 500 } });
  render(<Management path="/cats"/>);
  expect(await screen.findByText('Server nicht erreichbar. Bitte erneut versuchen.')).toBeTruthy();
  expect(screen.queryByText('PRIVATE-CHIP')).toBeNull();
  get.mockResolvedValue(ok([])); fireEvent.click(screen.getByRole('button', { name: 'Erneut versuchen' }));
  expect(await screen.findByText(/Noch keine Katzen oder Chips/)).toBeTruthy();
});

test.each(['/sites', '/observations', '/visits', '/admin/members', '/admin/sites', '/admin/cats'])('removed/backend route %s never loads a PWA management page', (path) => {
  render(<Management path={path}/>);
  expect(screen.getByRole('heading', { name: 'Seite nicht gefunden' })).toBeTruthy();
  expect(get).not.toHaveBeenCalled();
});

test('MEMBER registers an observed chip and retains cat visit history', async () => {
  get.mockImplementation(async (path: string) => ok(path.endsWith('/chip-activity') ? [{ chipId: 'CHIP', feedingSiteIds: ['site-a'] }]
    : path.endsWith('/feeding-sites') ? [site] : path.endsWith('/visits') ? [{ id: 'visit', chipId: 'CHIP', feedingSiteId: 'site-a',
      startAtMillis: '1735689600000', endAtMillis: '1735689601500', observationCount: 3, algorithmVersion: 'visit-gap-v1' }] : []));
  render(<Management path="/cats"/>);
  fireEvent.click(await screen.findByRole('button', { name: 'Katze dazu anlegen' }));
  expect(await screen.findByText('visit-gap-v1')).toBeTruthy();
  expect(screen.getByText('1,500 s')).toBeTruthy();
  fireEvent.change(screen.getByLabelText('Name (optional)'), { target: { value: 'Miez' } });
  fireEvent.click(screen.getByRole('button', { name: 'Speichern' }));
  await vi.waitFor(() => expect(post).toHaveBeenCalledWith('/api/v1/organizations/{organizationId}/cats', expect.objectContaining({
    headers: { 'X-XSRF-TOKEN': 'csrf' }, body: { chipId: 'CHIP', name: 'Miez', status: '', notes: '' }, cache: 'no-store',
  })));
  await screen.findByRole('list', { name: 'Katzen und Chips' });
  await vi.waitFor(() => expect(document.activeElement).toBe(screen.getByLabelText('Suche nach Name oder Chip-ID')));
});

test('nodes show the bowl label primarily and keep the technical node id visible', async () => {
  const named = { ...node, displayName: 'Der Grüne' };
  const unnamed = { ...node, nodeId: 'node-b' };
  get.mockImplementation(async (path: string) => ok(path.endsWith('/feeding-sites') ? [site]
    : path.endsWith('/nodes') ? [named, unnamed] : path.endsWith('/deployments') ? [
      { id: 'deployment-a', nodeId: named.nodeId, feedingSiteId: site.id, validFrom: '2025-01-01T00:00:00Z' },
    ] : []));
  render(<Management path="/nodes"/>);
  // Bowl label is shown for the named node; the unnamed node still shows its technical id.
  expect(await screen.findByText('Der Grüne')).toBeTruthy();
  expect(screen.getByText(named.nodeId)).toBeTruthy();
  expect(screen.getByText(unnamed.nodeId)).toBeTruthy();
  fireEvent.click(screen.getAllByRole('button', { name: 'Details' })[0]);
  expect((screen.getByLabelText('Napf-Bezeichnung (optional)') as HTMLInputElement).value).toBe('Der Grüne');
  fireEvent.change(screen.getByLabelText('Napf-Bezeichnung (optional)'), { target: { value: 'Silberner Napf' } });
  fireEvent.click(screen.getByRole('button', { name: 'Metadaten speichern' }));
  await vi.waitFor(() => expect(patch).toHaveBeenCalledWith('/api/v1/nodes/{nodeId}',
    expect.objectContaining({ body: expect.objectContaining({ displayName: 'Silberner Napf' }) })));
});


test('default Cat avatar is replaceable centrally without adding backend image fields', async () => {
  const { assets } = await import('../assets');
  const original = assets.placeholders.cat;
  try {
    assets.placeholders.cat = '/replacement-avatar.svg';
    get.mockImplementation(async (path: string) => ok(path.endsWith('/cats') ? [{ id: 'cat', chipId: 'CHIP', name: 'Miez' }] : []));
    const { container } = render(<Management path="/cats"/>);
    await screen.findByText('Miez');
    expect(container.querySelector('.avatar')?.getAttribute('src')).toBe('/replacement-avatar.svg');
  } finally { assets.placeholders.cat = original; }
});

test('cats overview uses mobile cards with search, filter and unknown-chip wording', async () => {
  get.mockImplementation(async (path: string) => ok(path.endsWith('/cats')
    ? [{ id: 'cat-luna', chipId: 'CHIP-LUNA', name: 'Luna', status: 'Aktiv', notes: 'Scheu' }]
    : path.endsWith('/chip-activity') ? [
      { chipId: 'CHIP-LUNA', lastSeenAtMillis: '1735689600000', lastReceivedAt: '2026-10-01T00:00:00Z', uncertainClockCount: 0, feedingSiteIds: ['site-a'] },
      { chipId: 'CHIP-UNKNOWN', lastSeenAtMillis: null, lastReceivedAt: '2026-10-08T12:00:00Z', uncertainClockCount: 1, feedingSiteIds: [] },
    ] : path.endsWith('/feeding-sites') ? [site] : path.endsWith('/visits') ? [] : []));
  render(<Management path="/cats"/>);
  const list = await screen.findByRole('list', { name: 'Katzen und Chips' });
  expect(within(list).getByRole('heading', { name: 'Luna' })).toBeTruthy();
  expect(within(list).getByRole('heading', { name: 'Unbekannter Chip' })).toBeTruthy();
  expect(screen.queryByText('Unbekannte Katze')).toBeNull();
  expect(screen.queryByRole('table')).toBeNull();
  expect(screen.getByLabelText('Suche nach Name oder Chip-ID')).toBeTruthy();
  expect(screen.getByRole('group', { name: 'Katzen und Chips filtern' })).toBeTruthy();
  expect(screen.getByText('CHIP-LUNA')).toBeTruthy();
  expect(screen.getByText(/Status: Aktiv/)).toBeTruthy();
  expect(screen.getByText(millis('1735689600000'))).toBeTruthy();
  expect(screen.getAllByText(/Serverempfang:/).length).toBe(2);
  expect(screen.getByText(/Bekannte Futterstellen: Garten/)).toBeTruthy();
  expect(screen.getByText('Keine verlässliche Sichtungszeit')).toBeTruthy();
  expect(screen.getByRole('button', { name: 'Details / bearbeiten' })).toBeTruthy();
  expect(screen.getByRole('button', { name: 'Katze dazu anlegen' })).toBeTruthy();
});

test('cats search and filter reuse loaded data without extra observation loads', async () => {
  get.mockImplementation(async (path: string) => ok(path.endsWith('/cats')
    ? [{ id: 'cat-luna', chipId: 'CHIP-LUNA', name: 'Luna' }, { id: 'cat-minka', chipId: 'CHIP-MINKA', name: 'Minka' }]
    : path.endsWith('/chip-activity') ? [
      { chipId: 'CHIP-LUNA', feedingSiteIds: [] },
      { chipId: 'CHIP-MINKA', feedingSiteIds: [] },
      { chipId: 'CHIP-UNKNOWN', feedingSiteIds: [] },
    ] : []));
  render(<Management path="/cats"/>);
  await screen.findByRole('list', { name: 'Katzen und Chips' });
  const initialCalls = get.mock.calls.length;
  expect(initialCalls).toBe(3);
  fireEvent.change(screen.getByLabelText('Suche nach Name oder Chip-ID'), { target: { value: 'luna' } });
  expect(screen.getByRole('heading', { name: 'Luna' })).toBeTruthy();
  expect(screen.queryByRole('heading', { name: 'Minka' })).toBeNull();
  expect(screen.queryByRole('heading', { name: 'Unbekannter Chip' })).toBeNull();
  expect(get.mock.calls.length).toBe(initialCalls);
  fireEvent.change(screen.getByLabelText('Suche nach Name oder Chip-ID'), { target: { value: 'CHIP-UNKNOWN' } });
  expect(screen.getByRole('heading', { name: 'Unbekannter Chip' })).toBeTruthy();
  expect(screen.queryByRole('heading', { name: 'Luna' })).toBeNull();
  fireEvent.change(screen.getByLabelText('Suche nach Name oder Chip-ID'), { target: { value: '' } });
  fireEvent.click(screen.getByRole('button', { name: 'Unbekannte Chips' }));
  expect(screen.queryByRole('heading', { name: 'Luna' })).toBeNull();
  expect(screen.getByRole('heading', { name: 'Unbekannter Chip' })).toBeTruthy();
  expect(screen.getByText(/1 von 3 Einträgen/)).toBeTruthy();
  fireEvent.click(screen.getByRole('button', { name: 'Bekannte Katzen' }));
  expect(screen.getByRole('heading', { name: 'Luna' })).toBeTruthy();
  expect(screen.queryByRole('heading', { name: 'Unbekannter Chip' })).toBeNull();
  fireEvent.click(screen.getByRole('button', { name: 'Alle' }));
  fireEvent.change(screen.getByLabelText('Suche nach Name oder Chip-ID'), { target: { value: 'nichts-passendes-xyz' } });
  expect(await screen.findByText('Keine Treffer für diese Suche.')).toBeTruthy();
  expect(get.mock.calls.length).toBe(initialCalls);
  expect(get.mock.calls.every(([path]) => !String(path).includes('/observations'))).toBe(true);
});

test('cat detail separates fields and preserves paginated historical visits', async () => {
  const visit = (id: string) => ({ id, chipId: 'CHIP-LUNA', feedingSiteId: 'site-a',
    startAtMillis: '1735689600000', endAtMillis: '1735689601000', observationCount: 2,
    algorithmVersion: 'visit-gap-v1', gapSeconds: 60, firstObservationId: 'first', lastObservationId: 'last' });
  get.mockImplementation(async (path: string, options?: { params?: { query?: { offset?: number } } }) => {
    if (path.endsWith('/cats')) return ok([{ id: 'cat-luna', chipId: 'CHIP-LUNA', name: 'Luna', status: 'Aktiv', notes: 'Scheue Notiz' }]);
    if (path.endsWith('/chip-activity')) return ok([{ chipId: 'CHIP-LUNA', lastSeenAtMillis: '1735689600000',
      lastReceivedAt: '2026-10-01T00:00:00Z', uncertainClockCount: 0, feedingSiteIds: ['site-a'] }]);
    if (path.endsWith('/feeding-sites')) return ok([site]);
    if (path.endsWith('/visits')) {
      const offset = options?.params?.query?.offset ?? 0;
      if (!offset) return ok(Array.from({ length: 20 }, (_, index) => visit(`visit-${index}`)));
      return ok([visit('visit-20')]);
    }
    return ok([]);
  });
  render(<Management path="/cats"/>);
  fireEvent.click(await screen.findByRole('button', { name: 'Details / bearbeiten' }));
  expect(screen.queryByRole('list', { name: 'Katzen und Chips' })).toBeNull();
  expect(document.activeElement?.getAttribute('aria-label')).toBe('Ausgewählte Katze');
  const detail = await screen.findByRole('region', { name: 'Katzendetails' });
  expect(within(detail).getByRole('heading', { name: 'Luna' })).toBeTruthy();
  expect(within(detail).getByText('Aktiv')).toBeTruthy();
  expect(within(detail).getByText('Scheue Notiz')).toBeTruthy();
  expect(within(detail).getAllByText('CHIP-LUNA').length).toBe(2);
  expect(within(detail).getByText(millis('1735689600000'))).toBeTruthy();
  expect(within(detail).getByText(/2026/)).toBeTruthy();
  const visits = await screen.findByRole('list', { name: 'Abgeleitete Besuche' });
  expect(within(visits).getAllByText(/Garten/).length).toBeGreaterThan(0);
  expect(within(visits).getAllByText('visit-gap-v1').length).toBe(20);
  expect(screen.getByText('Seite 1')).toBeTruthy();
  fireEvent.click(screen.getByRole('button', { name: 'Nächste Seite' }));
  expect(await screen.findByText('Seite 2')).toBeTruthy();
  fireEvent.click(screen.getByRole('button', { name: 'Vorherige Seite' }));
  expect(await screen.findByText('Seite 1')).toBeTruthy();
  fireEvent.click(within(detail).getByRole('button', { name: 'Zurück zur Liste' }));
  expect(screen.queryByRole('region', { name: 'Katzendetails' })).toBeNull();
  expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Details / bearbeiten' }));
});

test('an empty later visit page does not claim the cat has no visits', async () => {
  get.mockImplementation(async (path: string, options?: { params?: { query?: { offset?: number } } }) => {
    if (path.endsWith('/cats')) return ok([{ id: 'cat', chipId: 'CHIP', name: 'Luna' }]);
    if (path.endsWith('/visits')) return ok(options?.params?.query?.offset ? []
      : Array.from({ length: 20 }, (_, i) => ({ id: `visit-${i}`, chipId: 'CHIP' })));
    return ok([]);
  });
  render(<Management path="/cats"/>);
  fireEvent.click(await screen.findByRole('button', { name: 'Details / bearbeiten' }));
  fireEvent.click(await screen.findByRole('button', { name: 'Nächste Seite' }));
  await screen.findByText('Keine weiteren Besuche.');
  expect(screen.queryByText('Keine abgeleiteten Besuche vorhanden.')).toBeNull();
  fireEvent.click(screen.getByRole('button', { name: 'Vorherige Seite' }));
  await screen.findByRole('list', { name: 'Abgeleitete Besuche' });
});

test('unknown chip detail does not invent a cat and keeps creation', async () => {
  get.mockImplementation(async (path: string) => ok(path.endsWith('/chip-activity')
    ? [{ chipId: 'CHIP-UNKNOWN', lastSeenAtMillis: null, lastReceivedAt: '2026-10-08T12:00:00Z', uncertainClockCount: 1, feedingSiteIds: [] }]
    : path.endsWith('/visits') ? [] : []));
  render(<Management path="/cats"/>);
  fireEvent.click(await screen.findByRole('button', { name: 'Katze dazu anlegen' }));
  const detail = await screen.findByRole('region', { name: 'Katzendetails' });
  expect(within(detail).getByRole('heading', { name: 'Unbekannter Chip' })).toBeTruthy();
  expect(within(detail).getByText(/noch keine Katze angelegt/)).toBeTruthy();
  expect(within(detail).queryByText('Notizen')).toBeNull();
  expect((screen.getByLabelText('Chip-ID') as HTMLInputElement).value).toBe('CHIP-UNKNOWN');
});
