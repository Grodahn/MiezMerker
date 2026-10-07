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
  expect(screen.getByText('Garten')).toBeTruthy();
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
});
