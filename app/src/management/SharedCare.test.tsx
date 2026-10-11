import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
const { get, post, auth, listeners, expired } = vi.hoisted(() => ({
  get: vi.fn(), post: vi.fn(), expired: vi.fn(),
  auth: { user: { userId: 'user', memberships: [
    { organizationId: 'org-a', status: 'ACTIVE', role: 'ADMIN' },
    { organizationId: 'org-c', status: 'ACTIVE', role: 'ADMIN' },
  ] } as { userId: string; memberships: unknown[] } | null, activeOrganizationId: 'org-a', sessionVerified: true },
  listeners: new Set<() => void>(),
}));
vi.mock('../api/client', () => ({ api: { GET: get, POST: post } }));
vi.mock('../platform/auth', () => ({ getAuthState: () => auth,
  subscribeAuth: (fn: () => void) => { listeners.add(fn); return () => listeners.delete(fn); },
  fetchCsrfToken: async () => 'csrf', markSessionExpired: expired,
}));
import { Management } from './Management';
const ok = (data: unknown) => ({ data, response: { ok: true, status: 200 } });
const profiles = { items: [{ chipId: 'CHIP', catDisplayName: 'Fremdname B', source: { organizationId: 'org-b', displayName: 'Organisation B' } }] };
const changed = () => { for (const fn of listeners) fn(); };
beforeEach(() => {
  get.mockReset(); post.mockReset(); expired.mockReset();
  auth.user = { userId: 'user', memberships: ['org-a', 'org-c'].map(organizationId => ({ organizationId, status: 'ACTIVE', role: 'ADMIN' })) };
  auth.activeOrganizationId = 'org-a'; auth.sessionVerified = true; changed();
  get.mockImplementation(async (path: string) => ok(path.endsWith('/cats') ? [{ id: 'local', chipId: 'CHIP', name: 'Eigenname A' }]
    : path === '/api/v1/observations' ? [{ id: 'proof', organizationId: auth.activeOrganizationId, chipId: 'CHIP', clockStatus: 'UNKNOWN' }] : []));
  post.mockImplementation(async (path: string) => ok(path.endsWith('/resolve') ? profiles : { items: [] }));
});
afterEach(cleanup);
async function open() {
  render(<Management path="/cats"/>); await screen.findByText('Eigenname A');
  fireEvent.click(screen.getByRole('button', { name: 'Details / bearbeiten' }));
  await screen.findByText('Name bei Organisation B: Fremdname B');
}

test('local profile remains authoritative; generated API adapter uses observation proof, CSRF and no-store', async () => {
  await open();
  const shared = screen.getByRole('region', { name: 'Geteilte Betreuung' });
  expect(within(shared).queryByRole('textbox')).toBeNull();
  expect((screen.getByLabelText('Name (optional)') as HTMLInputElement).value).toBe('Eigenname A');
  expect(get).toHaveBeenCalledWith('/api/v1/observations', expect.objectContaining({ cache: 'no-store',
    params: { query: { organizationId: 'org-a', chipId: 'CHIP', limit: 1, offset: 0, newestFirst: true } } }));
  expect(post).toHaveBeenCalledWith('/api/v1/organizations/{organizationId}/shared-care/resolve', expect.objectContaining({
    cache: 'no-store', headers: { 'X-XSRF-TOKEN': 'csrf' }, body: { ownObservationRefs: ['proof'], limit: 5, offset: 0 },
  }));
});

test.each(['org switch', 'logout', 'expiry', 'navigation'])('%s unmounts foreign data and cancels late projections', async transition => {
  await open(); let finish!: (value: unknown) => void;
  post.mockImplementationOnce(() => new Promise(resolve => { finish = resolve; }));
  fireEvent.click(screen.getByRole('button', { name: 'Freigaben neu prüfen' }));
  await waitFor(() => expect(finish).toBeTypeOf('function'));
  const signal = post.mock.calls.at(-1)![1].signal as AbortSignal;
  act(() => {
    if (transition === 'org switch') auth.activeOrganizationId = 'org-c';
    if (transition === 'logout') auth.user = null;
    if (transition === 'expiry') auth.sessionVerified = false;
    if (transition === 'navigation') fireEvent.click(screen.getByRole('button', { name: 'Zurück zur Liste' }));
    else changed();
  });
  await act(async () => finish(ok(profiles)));
  expect(signal.aborted).toBe(true); expect(screen.queryByText(/Fremdname B/)).toBeNull();
});

test('401 from shared API uses existing expired-session transition', async () => {
  post.mockResolvedValue({ response: { ok: false, status: 401 } });
  render(<Management path="/cats"/>); await screen.findByText('Eigenname A');
  fireEvent.click(screen.getByRole('button', { name: 'Details / bearbeiten' }));
  await waitFor(() => expect(expired).toHaveBeenCalled());
  expect(screen.queryByText(/Fremdname B/)).toBeNull();
});
