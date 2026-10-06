import 'fake-indexeddb/auto';
import { cleanup, fireEvent, render, screen, waitFor, act } from '@testing-library/react';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';

const { apiGet, apiPost, credentialLookup, credentialExpiry, collectorMount, collectorUnmount } = vi.hoisted(() => ({
  apiGet: vi.fn(), apiPost: vi.fn(), credentialLookup: vi.fn(), credentialExpiry: vi.fn(),
  collectorMount: vi.fn(), collectorUnmount: vi.fn(),
}));

vi.mock('./api/client', () => ({
  api: {
    GET: (...args: unknown[]) => apiGet(...args),
    POST: (...args: unknown[]) => apiPost(...args),
    PATCH: vi.fn().mockResolvedValue({ response: { ok: true, status: 200 }, data: {} }),
  },
}));

vi.mock('./platform/offline-identity', () => ({
  OfflineIdentityDatabase: class {
    credentials = { get: credentialExpiry };
    close() {}
  },
  OfflineIdentity: class { credential = credentialLookup; },
}));

vi.mock('./collector/CollectorShell', async () => {
  const { useEffect } = await import('react');
  return { CollectorShell: () => {
    useEffect(() => { collectorMount(); return collectorUnmount; }, []);
    return <div>CollectorShell</div>;
  } };
});

function setPath(path: string) {
  window.history.replaceState({}, '', path);
}

function setOnline(value: boolean) {
  Object.defineProperty(navigator, 'onLine', { configurable: true, value });
}

function sessionResponse(user: unknown) {
  return { data: user, error: undefined, response: { status: 200, ok: true } };
}

const verifiedUser = {
  userId: 'user', email: 'staff@example.org',
  memberships: [
    { membershipId: 'm1', organizationId: 'org-a', organizationName: 'Org A', role: 'MEMBER', status: 'ACTIVE' },
  ],
};

beforeEach(async () => {
  vi.resetModules();
  localStorage.clear();
  sessionStorage.clear();
  cleanup();
  collectorMount.mockReset(); collectorUnmount.mockReset();
  apiGet.mockReset();
  apiPost.mockReset();
  credentialLookup.mockReset();
  credentialLookup.mockResolvedValue(null);
  credentialExpiry.mockReset();
  credentialExpiry.mockImplementation(async () => ({ expiresAt: Date.now() + 60_000 }));
  setOnline(true);
  apiGet.mockImplementation(async (path: string) => {
    if (path === '/api/v1/auth/session') return { error: {}, response: { status: 401 } };
    if (path === '/api/v1/auth/csrf') return { data: { token: 'csrf' } };
    return { data: [], response: { ok: true, status: 200 } };
  });
  apiPost.mockResolvedValue({ response: { ok: true, status: 200 }, data: {} });
});

afterEach(() => { cleanup(); vi.useRealTimers(); });

test('reconnect waits for fresh verification and cannot reveal an expired session', async () => {
  setPath('/sites');
  apiGet.mockImplementation(async (path: string) => path === '/api/v1/auth/session'
    ? sessionResponse(verifiedUser)
    : { data: [], response: { ok: true, status: 200 } });
  const { App } = await import('./App');
  render(<App />);
  await screen.findByText('Futterstellen der aktiven Organisation.');
  act(() => { setOnline(false); window.dispatchEvent(new Event('offline')); });
  expect(screen.queryByRole('navigation')).toBeNull();
  let resolveSession!: (value: unknown) => void;
  apiGet.mockImplementation((path: string) => path === '/api/v1/auth/session'
    ? new Promise(resolve => { resolveSession = resolve; })
    : Promise.resolve({ data: [], response: { ok: true, status: 200 } }));
  act(() => { setOnline(true); window.dispatchEvent(new Event('online')); });
  expect(screen.queryByRole('navigation')).toBeNull();
  expect(screen.queryByText('Futterstellen der aktiven Organisation.')).toBeNull();
  await act(async () => { resolveSession({ error: {}, response: { status: 401 } }); });
  expect(await screen.findByRole('button', { name: 'Anmelden' })).toBeTruthy();
});

test('logout hides private content before the server responds', async () => {
  setPath('/sites');
  apiGet.mockImplementation(async (path: string) => path === '/api/v1/auth/session'
    ? sessionResponse(verifiedUser)
    : path === '/api/v1/auth/csrf' ? { data: { token: 'csrf' } }
      : { data: [{ id: 'site-a', name: 'Private A site' }], response: { ok: true, status: 200 } });
  const { App } = await import('./App');
  render(<App />);
  await screen.findByText('Private A site');
  let finishLogout!: (value: unknown) => void;
  apiPost.mockImplementation(() => new Promise(resolve => { finishLogout = resolve; }));
  const { logout } = await import('./platform/auth');
  let pending!: Promise<void>;
  await act(async () => { pending = logout(); await Promise.resolve(); });
  expect(screen.queryByText('Private A site')).toBeNull();
  expect(screen.queryByRole('navigation')).toBeNull();
  expect(screen.getByRole('button', { name: 'Anmelden' })).toBeTruthy();
  await act(async () => { finishLogout({ response: { ok: true, status: 200 } }); await pending; });
});

test('pagehide clears the entire protected shell before browser history freezes it', async () => {
  setPath('/sync');
  apiGet.mockResolvedValue(sessionResponse(verifiedUser));
  const { App } = await import('./App');
  render(<App />);
  await screen.findByText('CollectorShell');
  act(() => window.dispatchEvent(new PageTransitionEvent('pagehide', { persisted: true })));
  expect(screen.queryByRole('navigation')).toBeNull();
  expect(screen.queryByText('CollectorShell')).toBeNull();
  expect(screen.queryByText(/staff@example.org/)).toBeNull();
});

test('offline shell closes when its credential expires without another auth event', async () => {
  setOnline(false);
  setPath('/sync');
  localStorage.setItem('miezmerker-offline-session', JSON.stringify({ user: verifiedUser, activeOrganizationId: 'org-a' }));
  credentialLookup.mockResolvedValue('valid-credential');
  credentialExpiry.mockResolvedValue({ expiresAt: Date.now() + 1000 });
  const { App } = await import('./App');
  vi.useFakeTimers();
  render(<App />);
  await act(async () => { await vi.advanceTimersByTimeAsync(10); });
  expect(screen.getByText('CollectorShell')).toBeTruthy();
  await act(async () => { await vi.advanceTimersByTimeAsync(2000); });
  expect(screen.queryByText('CollectorShell')).toBeNull();
  expect(screen.getByRole('button', { name: 'Anmelden' })).toBeTruthy();
});

test('valid offline authorization keeps the collector mounted through disconnect and reconnect', async () => {
  setPath('/sync');
  apiGet.mockResolvedValue(sessionResponse(verifiedUser));
  credentialLookup.mockResolvedValue('valid-credential');
  const { App } = await import('./App');
  render(<App />);
  await screen.findByText('CollectorShell');
  await waitFor(() => expect(credentialExpiry).toHaveBeenCalled());
  act(() => { setOnline(false); window.dispatchEvent(new Event('offline')); });
  expect(screen.getByText('CollectorShell')).toBeTruthy();
  expect(collectorMount).toHaveBeenCalledTimes(1);
  expect(collectorUnmount).not.toHaveBeenCalled();
  let finish!: (value: unknown) => void;
  apiGet.mockImplementation(() => new Promise(resolve => { finish = resolve; }));
  act(() => { setOnline(true); window.dispatchEvent(new Event('online')); });
  expect(screen.getByText('CollectorShell')).toBeTruthy();
  expect(screen.queryByRole('link', { name: 'Futterstellen' })).toBeNull();
  await act(async () => { finish({ error: {}, response: { status: 401 } }); });
  expect(screen.getByRole('button', { name: 'Anmelden' })).toBeTruthy();
  expect(collectorMount).toHaveBeenCalledTimes(1);
  expect(collectorUnmount).not.toHaveBeenCalled();
});

test('credential eligibility from A is never reused while B is being checked', async () => {
  setOnline(false); setPath('/sync');
  const user = { ...verifiedUser, memberships: [...verifiedUser.memberships,
    { membershipId: 'm2', organizationId: 'org-b', organizationName: 'Org B', role: 'MEMBER', status: 'ACTIVE' }] };
  localStorage.setItem('miezmerker-offline-session', JSON.stringify({ user, activeOrganizationId: 'org-a' }));
  credentialLookup.mockImplementation(async (_user: string, org: string) => org === 'org-a' ? 'valid-credential' : null);
  const { App } = await import('./App');
  render(<App />);
  await screen.findByText('CollectorShell');
  const { selectOrganization } = await import('./platform/auth');
  act(() => { selectOrganization('org-b'); });
  expect(screen.queryByText('CollectorShell')).toBeNull();
  expect(await screen.findByRole('button', { name: 'Anmelden' })).toBeTruthy();
});

test('initial load shows neutral loading without navigation or protected content', async () => {
  setPath('/nodes');
  let resolveSession!: (value: unknown) => void;
  apiGet.mockImplementation((path: string) => {
    if (path === '/api/v1/auth/session') return new Promise(resolve => { resolveSession = resolve; });
    return Promise.resolve({ data: { token: 'csrf' } });
  });
  const { App } = await import('./App');
  render(<App />);
  expect(screen.getByText('Anmeldung wird geprüft …')).toBeTruthy();
  expect(screen.queryByRole('navigation', { name: 'Bereiche' })).toBeNull();
  expect(screen.queryByText('CollectorShell')).toBeNull();
  expect(screen.queryByText('Anmelden')).toBeNull();
  await act(async () => { resolveSession({ error: {}, response: { status: 401 } }); });
  expect(await screen.findByRole('button', { name: 'Anmelden' })).toBeTruthy();
});

test.each([
  ['/'], ['/sync'], ['/nodes'], ['/cats'],
  ['/sites'], ['/observations'], ['/visits'], ['/admin/members'],
])('fresh unauthenticated browser on %s sees only login', async (path) => {
  setPath(path);
  const { App } = await import('./App');
  render(<App />);
  expect(await screen.findByRole('button', { name: 'Anmelden' })).toBeTruthy();
  expect(screen.queryByRole('navigation', { name: 'Bereiche' })).toBeNull();
  expect(screen.queryByText('CollectorShell')).toBeNull();
  expect(screen.queryByText('Futterstellen')).toBeNull();
  expect(screen.queryByText('Garten')).toBeNull();
  expect(apiGet.mock.calls.every(([p]) =>
    p === '/api/v1/auth/session' || p === '/api/v1/auth/csrf')).toBe(true);
});

test('successful login reveals normal PWA without reload', async () => {
  setPath('/sites');
  let loggedIn = false;
  apiGet.mockImplementation(async (path: string) => {
    if (path === '/api/v1/auth/session') return loggedIn ? sessionResponse(verifiedUser) : { error: {}, response: { status: 401 } };
    if (path === '/api/v1/auth/csrf') return { data: { token: 'csrf' } };
    return { data: [], response: { ok: true, status: 200 } };
  });
  apiPost.mockImplementation(async (path: string) => {
    if (path === '/api/v1/auth/login') {
      loggedIn = true;
      return sessionResponse(verifiedUser);
    }
    return { response: { ok: true, status: 200 }, data: {} };
  });
  const { App } = await import('./App');
  render(<App />);
  expect(await screen.findByRole('button', { name: 'Anmelden' })).toBeTruthy();
  fireEvent.change(screen.getByLabelText('E-Mail'), { target: { value: 'staff@example.org' } });
  fireEvent.change(screen.getByLabelText('Passwort'), { target: { value: 'supersecret-password' } });
  fireEvent.click(screen.getByRole('button', { name: 'Anmelden' }));
  expect(await screen.findByRole('navigation', { name: 'Bereiche' })).toBeTruthy();
  expect(screen.getByRole('link', { name: 'Futterstellen' })).toBeTruthy();
  expect(await screen.findByText('Futterstellen der aktiven Organisation.')).toBeTruthy();
  expect(screen.queryByRole('button', { name: 'Anmelden' })).toBeNull();
  const { getAuthState } = await import('./platform/auth');
  expect(getAuthState().activeOrganizationId).toBe('org-a');
});

test('session expiration hides protected content and shows login without stale data', async () => {
  setPath('/sites');
  apiGet.mockImplementation(async (path: string) => {
    if (path === '/api/v1/auth/session') return sessionResponse(verifiedUser);
    if (path === '/api/v1/auth/csrf') return { data: { token: 'csrf' } };
    if (typeof path === 'string' && path.includes('/feeding-sites')) {
      return { data: [{ id: 'site-a', organizationId: 'org-a', name: 'Private A site' }],
        response: { ok: true, status: 200 } };
    }
    return { data: [], response: { ok: true, status: 200 } };
  });
  const { App } = await import('./App');
  render(<App />);
  expect(await screen.findByText('Private A site')).toBeTruthy();
  expect(screen.getByRole('navigation', { name: 'Bereiche' })).toBeTruthy();
  apiGet.mockImplementation(async (path: string) => {
    if (path === '/api/v1/auth/session') return { error: {}, response: { status: 401 } };
    if (path === '/api/v1/auth/csrf') return { data: { token: 'csrf' } };
    return { error: {}, response: { ok: false, status: 401 } };
  });
  const { fetchSession } = await import('./platform/auth');
  await act(async () => { await fetchSession().catch(() => {}); });
  expect(await screen.findByRole('button', { name: 'Anmelden' })).toBeTruthy();
  expect(screen.queryByText('Private A site')).toBeNull();
  expect(screen.queryByRole('navigation', { name: 'Bereiche' })).toBeNull();
  expect(screen.queryByText('CollectorShell')).toBeNull();
});

test('session expiration preserves offline-sync eligibility when a valid credential exists', async () => {
  setPath('/sync');
  apiGet.mockImplementation(async (path: string) => {
    if (path === '/api/v1/auth/session') return sessionResponse(verifiedUser);
    if (path === '/api/v1/auth/csrf') return { data: { token: 'csrf' } };
    return { data: [], response: { ok: true, status: 200 } };
  });
  credentialLookup.mockResolvedValue('valid-credential');
  const { App } = await import('./App');
  render(<App />);
  expect(await screen.findByText('CollectorShell')).toBeTruthy();
  expect(screen.queryByRole('button', { name: 'Anmelden' })).toBeNull();
  apiGet.mockImplementation(async (path: string) => {
    if (path === '/api/v1/auth/session') return { error: {}, response: { status: 401 } };
    if (path === '/api/v1/auth/csrf') return { data: { token: 'csrf' } };
    return { data: [], response: { ok: true, status: 200 } };
  });
  const { fetchSession } = await import('./platform/auth');
  await act(async () => { await fetchSession().catch(() => {}); });
  expect(await screen.findByText('CollectorShell')).toBeTruthy();
  expect(screen.getByRole('button', { name: 'Anmelden' })).toBeTruthy();
  expect(screen.queryByRole('link', { name: 'Futterstellen' })).toBeNull();
});

test('logout immediately hides shell and discards management state', async () => {
  setPath('/sites');
  apiGet.mockImplementation(async (path: string) => {
    if (path === '/api/v1/auth/session') return sessionResponse(verifiedUser);
    if (path === '/api/v1/auth/csrf') return { data: { token: 'csrf' } };
    if (typeof path === 'string' && path.includes('/feeding-sites')) {
      return { data: [{ id: 'site-a', organizationId: 'org-a', name: 'Private A site' }],
        response: { ok: true, status: 200 } };
    }
    return { data: [], response: { ok: true, status: 200 } };
  });
  apiPost.mockImplementation(async (path: string) => {
    if (path === '/api/v1/auth/logout') return { response: { ok: true, status: 200 }, data: {} };
    return { response: { ok: true, status: 200 }, data: {} };
  });
  const { App } = await import('./App');
  render(<App />);
  expect(await screen.findByText('Private A site')).toBeTruthy();
  const { logout } = await import('./platform/auth');
  await act(async () => { await logout(); });
  expect(await screen.findByRole('button', { name: 'Anmelden' })).toBeTruthy();
  expect(screen.queryByText('Private A site')).toBeNull();
  expect(screen.queryByRole('navigation', { name: 'Bereiche' })).toBeNull();
  expect(screen.queryByText('Futterstellen')).toBeNull();
});

test('offline with valid credential keeps /sync usable but management locked', async () => {
  setOnline(false);
  localStorage.setItem('miezmerker-offline-session', JSON.stringify({
    user: { userId: 'user', email: 'field@example.org', memberships: [
      { organizationId: 'org-a', organizationName: 'Field Org', role: 'MEMBER', status: 'ACTIVE' },
    ] }, activeOrganizationId: 'org-a',
  }));
  credentialLookup.mockResolvedValue('valid-credential');
  setPath('/sync');
  const syncModule = await import('./App');
  const { unmount } = render(<syncModule.App />);
  expect(await screen.findByText('CollectorShell')).toBeTruthy();
  expect(screen.queryByRole('link', { name: 'Futterstellen' })).toBeNull();
  expect(screen.queryByRole('button', { name: 'Anmelden' })).toBeNull();
  unmount();
  cleanup();
  vi.resetModules();
  localStorage.setItem('miezmerker-offline-session', JSON.stringify({
    user: { userId: 'user', email: 'field@example.org', memberships: [
      { organizationId: 'org-a', organizationName: 'Field Org', role: 'MEMBER', status: 'ACTIVE' },
    ] }, activeOrganizationId: 'org-a',
  }));
  apiGet.mockImplementation(async (p: string) => {
    if (p === '/api/v1/auth/csrf') return { data: { token: 'csrf' } };
    return { error: {}, response: { status: 401 } };
  });
  setPath('/nodes');
  const mgmtModule = await import('./App');
  render(<mgmtModule.App />);
  expect(await screen.findByRole('button', { name: 'Anmelden' })).toBeTruthy();
  expect(screen.queryByText('CollectorShell')).toBeNull();
  expect(screen.queryByText('Private')).toBeNull();
});

test('offline without valid credential locks /sync to login', async () => {
  setOnline(false);
  localStorage.setItem('miezmerker-offline-session', JSON.stringify({
    user: { userId: 'user', email: 'field@example.org', memberships: [
      { organizationId: 'org-a', organizationName: 'Field Org', role: 'MEMBER', status: 'ACTIVE' },
    ] }, activeOrganizationId: 'org-a',
  }));
  credentialLookup.mockResolvedValue(null);
  setPath('/sync');
  const { App } = await import('./App');
  render(<App />);
  expect(await screen.findByRole('button', { name: 'Anmelden' })).toBeTruthy();
  expect(screen.queryByText('CollectorShell')).toBeNull();
});

test('direct navigation to protected routes never renders navigation before auth', async () => {
  for (const path of ['/nodes', '/cats', '/sites', '/observations', '/visits', '/admin/members']) {
    cleanup();
    vi.resetModules();
    localStorage.clear();
    sessionStorage.clear();
    apiGet.mockImplementation(async (p: string) => {
      if (p === '/api/v1/auth/session') return { error: {}, response: { status: 401 } };
      if (p === '/api/v1/auth/csrf') return { data: { token: 'csrf' } };
      return { data: [], response: { ok: true, status: 200 } };
    });
    setOnline(true);
    setPath(path);
    expect(window.location.pathname).toBe(path);
    const mod = await import('./App');
    render(<mod.App />);
    expect(await screen.findByRole('button', { name: 'Anmelden' })).toBeTruthy();
    expect(window.location.pathname).toBe(path);
    expect(screen.queryByRole('navigation', { name: 'Bereiche' })).toBeNull();
  }
});

test('organization switch does not leak previous organization data', async () => {
  setPath('/sites');
  const orgA = { ...verifiedUser, memberships: [
    { membershipId: 'm1', organizationId: 'org-a', organizationName: 'Org A', role: 'MEMBER', status: 'ACTIVE' },
    { membershipId: 'm2', organizationId: 'org-b', organizationName: 'Org B', role: 'MEMBER', status: 'ACTIVE' },
  ] };
  apiGet.mockImplementation(async (path: string) => {
    if (path === '/api/v1/auth/session') return sessionResponse(orgA);
    if (path === '/api/v1/auth/csrf') return { data: { token: 'csrf' } };
    if (typeof path === 'string' && path.includes('/feeding-sites')) {
      return { data: [], response: { ok: true, status: 200 } };
    }
    return { data: [], response: { ok: true, status: 200 } };
  });
  const { App } = await import('./App');
  render(<App />);
  await waitFor(async () => {
    const { getAuthState } = await import('./platform/auth');
    expect(getAuthState().user).not.toBeNull();
  });
  const { selectOrganization, getAuthState } = await import('./platform/auth');
  expect(getAuthState().activeOrganizationId).toBeNull();
  expect(await screen.findByText('Bitte eine aktive Organisation auswählen.')).toBeTruthy();
  await act(async () => { selectOrganization('org-a'); });
  expect(await screen.findByText(/Noch keine Futterstellen/)).toBeTruthy();
});
