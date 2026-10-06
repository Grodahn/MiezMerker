import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';

const { get, post, patch, csrf, auth, listeners } = vi.hoisted(() => ({
  get: vi.fn(), post: vi.fn(), patch: vi.fn(), csrf: vi.fn(),
  auth: { user: { userId: 'user', email: 'staff@example.org', memberships: [
    { organizationId: 'org-a', status: 'ACTIVE', role: 'MEMBER' },
    { organizationId: 'org-b', status: 'ACTIVE', role: 'MEMBER' },
  ] }, activeOrganizationId: 'org-a' as string | null }, listeners: new Set<() => void>(),
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
const observation = { id: 'read-1', organizationId: 'org-a', nodeId: 'node-a', sequence: '9007199254740993', chipId: 'CHIP-NEW',
  clockStatus: 'UNKNOWN', receivedAt: '2026-10-01T10:00:00Z', monotonicMs: '9007199254740995' };
beforeEach(() => {
  get.mockReset(); post.mockReset(); patch.mockReset(); csrf.mockReset(); csrf.mockResolvedValue('csrf');
  auth.activeOrganizationId = 'org-a'; auth.user.userId = 'user';
  auth.user.memberships.forEach(m => { m.role = 'MEMBER'; m.status = 'ACTIVE'; });
  changed();
  get.mockImplementation(async () => ok([])); post.mockResolvedValue(ok({})); patch.mockResolvedValue(ok({}));
});
afterEach(cleanup);

test.each([
  ['/sites', 'Noch keine Futterstellen vorhanden.'], ['/nodes', 'Noch keine Nodes vorhanden.'],
  ['/cats', 'Noch keine Katzen oder Chips vorhanden.'], ['/observations', 'Keine Rohbeobachtungen vorhanden.'],
  ['/visits', 'Keine abgeleiteten Besuche vorhanden.'],
])('empty installation is usable on %s', async (path, text) => {
  render(<Management path={path}/>);
  expect(await screen.findByText(new RegExp(text))).toBeTruthy();
});

test('MEMBER creates and edits a feeding site with the generated API and CSRF', async () => {
  get.mockImplementation(async (path: string) => ok(path.endsWith('/feeding-sites') ? [site] : []));
  render(<Management path="/sites"/>);
  await screen.findByText('Garten');
  fireEvent.click(screen.getByRole('button', { name: 'Futterstelle anlegen' }));
  fireEvent.change(screen.getByLabelText('Name / Bezeichnung'), { target: { value: 'Scheune' } });
  fireEvent.change(screen.getByLabelText('Ort / Wegbeschreibung'), { target: { value: 'Am Eingang' } });
  fireEvent.click(screen.getByRole('button', { name: 'Speichern' }));
  await vi.waitFor(() => expect(post).toHaveBeenCalledWith('/api/v1/organizations/{organizationId}/feeding-sites',
    expect.objectContaining({ params: { path: { organizationId: 'org-a' } }, headers: { 'X-XSRF-TOKEN': 'csrf' },
      body: expect.objectContaining({ name: 'Scheune', locationLabel: 'Am Eingang' }), cache: 'no-store' })));
  await vi.waitFor(() => expect(screen.queryByLabelText('Name / Bezeichnung')).toBeNull());
  fireEvent.click(screen.getByRole('button', { name: 'Details / bearbeiten' }));
  fireEvent.change(screen.getByLabelText('Name / Bezeichnung'), { target: { value: 'Neuer Garten' } });
  fireEvent.click(screen.getByRole('button', { name: 'Speichern' }));
  await vi.waitFor(() => expect(patch).toHaveBeenCalledWith('/api/v1/organizations/{organizationId}/feeding-sites/{siteId}',
    expect.objectContaining({ body: expect.objectContaining({ name: 'Neuer Garten' }) })));
});

test('node assignment is one atomic call and displays temporal history', async () => {
  get.mockImplementation(async (path: string) => ok(path.endsWith('/feeding-sites') ? [site, { ...site, id: 'site-b', name: 'Scheune' }]
    : path.endsWith('/nodes') ? [node] : path.endsWith('/deployments') ? [
      { id: 'deployment-a', nodeId: node.nodeId, feedingSiteId: site.id, validFrom: '2025-01-01T00:00:00Z' },
    ] : []));
  render(<Management path="/nodes"/>);
  await screen.findByText('Garten');
  fireEvent.click(screen.getByRole('button', { name: 'Details / zuordnen' }));
  expect(screen.getByRole('heading', { name: 'Deployment-Historie' })).toBeTruthy();
  expect(screen.getByText('Offen')).toBeTruthy();
  fireEvent.change(screen.getByLabelText('Neue Futterstelle'), { target: { value: 'site-b' } });
  fireEvent.change(screen.getByLabelText('Gültig ab (lokale Uhrzeit)'), { target: { value: '2026-10-02T12:00:00' } });
  fireEvent.click(screen.getByRole('button', { name: 'Zuordnung speichern' }));
  await vi.waitFor(() => expect(post).toHaveBeenCalledWith('/api/v1/organizations/{organizationId}/deployments/move',
    expect.objectContaining({ body: { nodeId: node.nodeId, feedingSiteId: 'site-b', validFrom: new Date('2026-10-02T12:00:00').toISOString() } })));
  expect(patch).not.toHaveBeenCalled();
});

test('unknown chip and clock are visible and the chip can become a named cat', async () => {
  get.mockImplementation(async (path: string) => ok(path === '/api/v1/observations' ? [observation] : []));
  render(<Management path="/observations"/>);
  expect(await screen.findByText('Uhrzeit unbekannt')).toBeTruthy();
  expect(screen.getByText('9007199254740993')).toBeTruthy();
  expect(screen.getByText('9007199254740995')).toBeTruthy();
  fireEvent.click(screen.getByRole('button', { name: 'Katze dazu anlegen' }));
  const editor = screen.getByRole('region', { name: 'Katze pflegen' });
  expect((within(editor).getByLabelText('Chip-ID') as HTMLInputElement).value).toBe('CHIP-NEW');
  fireEvent.change(within(editor).getByLabelText('Name (optional)'), { target: { value: 'Miez' } });
  fireEvent.click(within(editor).getByRole('button', { name: 'Speichern' }));
  await vi.waitFor(() => expect(post).toHaveBeenCalledWith('/api/v1/organizations/{organizationId}/cats',
    expect.objectContaining({ body: { chipId: 'CHIP-NEW', name: 'Miez', status: '', notes: '' } })));
});

test('cats include observed unknown chips and separate last sighting from late server receipt', async () => {
  get.mockImplementation(async (path: string) => ok(path.endsWith('/feeding-sites') ? [site] : path.endsWith('/chip-activity') ? [
    { chipId: 'CHIP-NEW', lastSeenAtMillis: '1735689600000', lastReceivedAt: '2026-10-01T00:00:00Z', uncertainClockCount: 2, feedingSiteIds: ['site-a'] },
  ] : []));
  render(<Management path="/cats"/>);
  expect(await screen.findByText('CHIP-NEW')).toBeTruthy();
  expect(screen.getByText('Garten')).toBeTruthy();
  expect(screen.getByText('2 Read(s) ohne verlässliche Uhrzeit')).toBeTruthy();
  expect(screen.getByText(millis('1735689600000'))).toBeTruthy();
});

test('missing or invalid clock values never become a precise sighting', async () => {
  get.mockImplementation(async (path: string) => ok(path === '/api/v1/observations' ? [
    { ...observation, clockStatus: undefined, observedAtMillis: '1735689600000' },
    { ...observation, id: 'invalid', clockStatus: 'SYNCED', observedAtMillis: '0' },
  ] : []));
  render(<Management path="/observations"/>);
  expect(await screen.findByText('Clock-Status fehlt / ungültig')).toBeTruthy();
  expect(screen.getByText('Ungültige Zeit (Rohwert: 0)')).toBeTruthy();
  expect(screen.queryByText(millis('1735689600000'))).toBeNull();
});

test('raw filters stay in view state, use all documented server parameters and paginate', async () => {
  const rows = Array.from({ length: 50 }, (_, i) => ({ ...observation, id: `row-${i}` }));
  get.mockImplementation(async (path: string) => ok(path.endsWith('/feeding-sites') ? [site] : path.endsWith('/nodes') ? [node]
    : path === '/api/v1/observations' ? rows : []));
  render(<Management path="/observations"/>);
  await screen.findByText('Seite 1 · 50 Einträge');
  fireEvent.change(screen.getByLabelText('Futterstelle'), { target: { value: site.id } });
  fireEvent.change(screen.getByLabelText('Node'), { target: { value: node.nodeId } });
  fireEvent.change(screen.getByLabelText('Chip-ID'), { target: { value: 'CHIP-NEW' } });
  fireEvent.change(screen.getByLabelText('Von (einschließlich)'), { target: { value: '2026-10-01T10:00' } });
  fireEvent.change(screen.getByLabelText('Bis (ausschließlich)'), { target: { value: '2026-10-02T10:00' } });
  fireEvent.click(screen.getByRole('button', { name: 'Filter anwenden' }));
  await vi.waitFor(() => expect(get).toHaveBeenCalledWith('/api/v1/observations', expect.objectContaining({
    params: { query: { organizationId: 'org-a', feedingSiteId: site.id, nodeId: node.nodeId, chipId: 'CHIP-NEW',
      fromMillis: Date.parse('2026-10-01T10:00'), toMillis: Date.parse('2026-10-02T10:00'), limit: 50, offset: 0, newestFirst: true } }, cache: 'no-store',
  })));
  await screen.findByText('Seite 1 · 50 Einträge');
  fireEvent.click(screen.getByRole('button', { name: 'Weiter' }));
  await vi.waitFor(() => expect(get).toHaveBeenLastCalledWith('/api/v1/observations', expect.objectContaining({
    params: { query: expect.objectContaining({ offset: 50 }) },
  })));
  expect(window.location.search).toBe('');
});

test('visits render version, count and precise duration separately from raw observations', async () => {
  get.mockImplementation(async (path: string) => ok(path.endsWith('/feeding-sites') ? [site] : path.endsWith('/visits') ? [
    { id: 'visit', feedingSiteId: site.id, chipId: 'CHIP-NEW', startAtMillis: '1735689600000', endAtMillis: '1735689601500',
      observationCount: 3, algorithmVersion: 'visit-gap-v1', gapSeconds: 60 },
  ] : []));
  render(<Management path="/visits"/>);
  expect(await screen.findByText('visit-gap-v1')).toBeTruthy();
  expect(screen.getByText('1,500 s')).toBeTruthy(); expect(screen.getByText('3 Reads')).toBeTruthy();
  expect(screen.queryByRole('button', { name: 'Besuche neu berechnen' })).toBeNull();
  expect(get.mock.calls.some(([path]) => path === '/api/v1/observations')).toBe(false);
});

test('organization changes clear visible records and abort stale A responses, including A -> B -> A', async () => {
  let resolveA: (value: unknown) => void = () => {};
  get.mockImplementation((path: string, options: { params: { path: { organizationId: string } } }) => {
    if (options.params.path.organizationId === 'org-a') return new Promise(resolve => { resolveA = resolve; });
    return Promise.resolve(ok([{ ...site, id: 'site-b', organizationId: 'org-b', name: 'Org B site' }]));
  });
  render(<Management path="/sites"/>);
  await vi.waitFor(() => expect(get).toHaveBeenCalledTimes(1));
  const oldSignal = get.mock.calls[0][1].signal as AbortSignal;
  await act(async () => { auth.activeOrganizationId = 'org-b'; changed(); });
  expect(await screen.findByText('Org B site')).toBeTruthy();
  expect(oldSignal.aborted).toBe(true);
  const firstResolve = resolveA;
  await act(async () => { auth.activeOrganizationId = 'org-a'; changed(); });
  expect(screen.queryByText('Org B site')).toBeNull();
  await act(async () => firstResolve(ok([{ ...site, name: 'Stale A site' }])));
  expect(screen.queryByText('Stale A site')).toBeNull();
  await act(async () => resolveA(ok([site])));
  expect(await screen.findByText('Garten')).toBeTruthy();
});

test('switching organization while CSRF resolves prevents a mutation in the old context', async () => {
  let resolveCsrf: (value: string) => void = () => {};
  csrf.mockImplementation(() => new Promise(resolve => { resolveCsrf = resolve; }));
  render(<Management path="/sites"/>);
  await screen.findByText(/Noch keine Futterstellen/);
  fireEvent.click(screen.getByRole('button', { name: 'Futterstelle anlegen' }));
  fireEvent.change(screen.getByLabelText('Name / Bezeichnung'), { target: { value: 'Old A site' } });
  fireEvent.click(screen.getByRole('button', { name: 'Speichern' }));
  await act(async () => { auth.activeOrganizationId = 'org-b'; changed(); resolveCsrf('csrf'); });
  expect(post).not.toHaveBeenCalled(); expect(screen.queryByLabelText('Name / Bezeichnung')).toBeNull();
});

test('leaving a management document clears records and drafts before browser history retains it', async () => {
  get.mockImplementation(async (path: string) => ok(path.endsWith('/feeding-sites') ? [site] : []));
  let resolveCsrf: (value: string) => void = () => {};
  csrf.mockImplementation(() => new Promise(resolve => { resolveCsrf = resolve; }));
  render(<Management path="/sites"/>);
  await screen.findByText('Garten');
  const signal = get.mock.calls[0][1].signal as AbortSignal;
  fireEvent.click(screen.getByRole('button', { name: 'Futterstelle anlegen' }));
  fireEvent.change(screen.getByLabelText('Name / Bezeichnung'), { target: { value: 'Private draft' } });
  fireEvent.click(screen.getByRole('button', { name: 'Speichern' }));
  fireEvent(window, new PageTransitionEvent('pagehide', { persisted: true }));
  expect(screen.queryByText('Garten')).toBeNull();
  expect(screen.queryByLabelText('Name / Bezeichnung')).toBeNull();
  expect(signal.aborted).toBe(true);
  await act(async () => resolveCsrf('csrf'));
  expect(post).not.toHaveBeenCalled();
});

test('member cannot load roster; ADMIN uses existing member API', async () => {
  const view = render(<Management path="/admin/members"/>);
  expect(screen.getByRole('alert').textContent).toContain('ADMIN'); expect(get).not.toHaveBeenCalled();
  await act(async () => { auth.user.memberships[0].role = 'ADMIN'; changed(); });
  expect(await screen.findByText('Keine Mitglieder vorhanden.')).toBeTruthy();
  expect(get).toHaveBeenCalledWith('/api/v1/organizations/{organizationId}/members', expect.objectContaining({ params: { path: { organizationId: 'org-a' } } }));
  view.unmount();
});

test('members show display name, email, role and status with email fallback', async () => {
  get.mockImplementation(async () => ok([
    { membershipId: 'm-1', userId: 'u-1', email: 'ada@example.org', displayName: 'Ada Lovelace', role: 'ADMIN', status: 'ACTIVE' },
    { membershipId: 'm-2', userId: 'u-2', email: 'plain@example.org', displayName: null, role: 'MEMBER', status: 'ACTIVE' },
  ]));
  await act(async () => { auth.user.memberships[0].role = 'ADMIN'; changed(); });
  const view = render(<Management path="/admin/members"/>);
  // #32: Name column uses the global display name; missing names fall back to email.
  expect(await screen.findByText('Ada Lovelace')).toBeTruthy();
  expect(screen.getByText('ada@example.org')).toBeTruthy();
  // Fallback renders the email in both the Name and E-Mail cells.
  expect(screen.getAllByText('plain@example.org').length).toBe(2);
  const rows = screen.getAllByRole('row');
  expect(rows.length).toBe(3);
  expect(rows[1].textContent).toContain('Ada Lovelace');
  expect(rows[1].textContent).toContain('ADMIN');
  expect(rows[2].textContent).toContain('plain@example.org');
  view.unmount();
});

test('inactive membership, no selected organization and offline management cannot fetch business data', async () => {
  auth.activeOrganizationId = null; changed();
  const view = render(<Management path="/sites"/>);
  expect(screen.getByText('Bitte eine aktive Organisation auswählen.')).toBeTruthy(); expect(get).not.toHaveBeenCalled();
  await act(async () => { auth.activeOrganizationId = 'org-a'; auth.user.memberships[0].status = 'DISABLED'; changed(); });
  expect(get).not.toHaveBeenCalled();
  view.unmount();
  Object.defineProperty(navigator, 'onLine', { configurable: true, value: false });
  auth.user.memberships[0].status = 'ACTIVE'; changed();
  render(<Management path="/sites"/>);
  expect(screen.getByText(/Verwaltung benötigt eine Serververbindung/)).toBeTruthy(); expect(get).not.toHaveBeenCalled();
  Object.defineProperty(navigator, 'onLine', { configurable: true, value: true });
});

test('session and server failures are actionable without leaking response details', async () => {
  get.mockResolvedValue({ error: { detail: 'PRIVATE-CHIP' }, response: { ok: false, status: 401 } });
  render(<Management path="/sites"/>);
  expect(await screen.findByText('Sitzung abgelaufen. Bitte erneut anmelden.')).toBeTruthy();
  expect(screen.queryByText('PRIVATE-CHIP')).toBeNull();
  get.mockResolvedValue(ok([])); fireEvent.click(screen.getByRole('button', { name: 'Erneut versuchen' }));
  expect(await screen.findByText(/Noch keine Futterstellen/)).toBeTruthy();
});
