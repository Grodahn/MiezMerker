import { beforeEach, describe, expect, test, vi } from 'vitest';

const get = vi.fn();
const post = vi.fn();
vi.mock('../api/client', () => ({
  api: {
    GET: (...args: unknown[]) => get(...args),
    POST: (...args: unknown[]) => post(...args),
  },
}));

import {
  activeOrganizationIds,
  fetchCsrfToken,
  fetchSession,
  getAuthState,
  login,
  logout,
  subscribeAuth,
  selectOrganization,
} from './auth';

describe('auth session (#16)', () => {
  beforeEach(() => {
    get.mockReset();
    post.mockReset();
  });

  test('fetches CSRF token and stores it', async () => {
    get.mockResolvedValue({ data: { token: 'csrf-123' }, error: undefined });
    const token = await fetchCsrfToken();
    expect(token).toBe('csrf-123');
    expect(getAuthState().csrfToken).toBe('csrf-123');
  });

  test('login posts credentials with CSRF header and stores the session', async () => {
    get.mockResolvedValue({ data: { token: 'csrf-123' }, error: undefined });
    post.mockResolvedValue({
      data: {
        userId: 'u-1',
        email: 'admin@example.org',
        memberships: [
          { membershipId: 'm-1', organizationId: 'o-1', organizationSlug: 'org-a',
            organizationName: 'Org A', role: 'ADMIN', status: 'ACTIVE' },
        ],
      },
      error: undefined,
    });
    const user = await login('admin@example.org', 'supersecret-password');
    expect(user.email).toBe('admin@example.org');
    expect(getAuthState().user?.userId).toBe('u-1');
    expect(post).toHaveBeenCalledWith('/api/v1/auth/login', {
      body: { email: 'admin@example.org', password: 'supersecret-password' },
      headers: { 'X-XSRF-TOKEN': 'csrf-123' },
    });
  });

  test('logout clears the session', async () => {
    get.mockResolvedValue({ data: { token: 'csrf-123' }, error: undefined });
    post.mockImplementation(async (path: string) => {
      if (path.includes('/auth/login')) {
        return { data: { userId: 'u-1', email: 'a@example.org', memberships: [] }, error: undefined };
      }
      return { data: undefined, error: undefined, response: { ok: true, status: 200 } };
    });
    await login('a@example.org', 'supersecret-password');
    expect(getAuthState().user).not.toBeNull();
    await logout();
    expect(getAuthState().user).toBeNull();
    expect(getAuthState().csrfToken).toBeNull();
  });

  test('fetchSession returns null when unauthenticated', async () => {
    get.mockResolvedValue({ data: undefined, error: {}, response: { status: 401 } });
    expect(await fetchSession()).toBeNull();
    expect(getAuthState().user).toBeNull();
  });

  test('a rejected logout keeps the authenticated state', async () => {
    get.mockResolvedValue({ data: { token: 'fresh' } });
    post.mockResolvedValueOnce({ data: { userId: 'u-1', email: 'a@example.org', memberships: [] } });
    await login('a@example.org', 'password');
    post.mockResolvedValueOnce({ error: {}, response: { status: 403 } });
    await expect(logout()).rejects.toThrow('Abmelden fehlgeschlagen');
    expect(getAuthState().user?.userId).toBe('u-1');
    expect(post).toHaveBeenLastCalledWith('/api/v1/auth/logout', { headers: { 'X-XSRF-TOKEN': 'fresh' } });
  });

  test('multiple memberships require a selection and revocation clears it', async () => {
    const memberships = [
      { membershipId: 'm-1', organizationId: 'o-1', role: 'MEMBER', status: 'ACTIVE' },
      { membershipId: 'm-2', organizationId: 'o-2', role: 'ADMIN', status: 'ACTIVE' },
    ];
    get.mockResolvedValue({ data: { userId: 'u-multi', email: 'multi@example.org', memberships } });
    await fetchSession();
    expect(getAuthState().activeOrganizationId).toBeNull();
    selectOrganization('o-2');
    expect(getAuthState().activeOrganizationId).toBe('o-2');
    expect(() => selectOrganization('foreign')).toThrow();
    get.mockResolvedValue({ data: { userId: 'u-multi', email: 'multi@example.org',
      memberships: memberships.map(m => ({ ...m, status: 'DISABLED' })) } });
    await fetchSession();
    expect(getAuthState().activeOrganizationId).toBeNull();
  });

  test('network failures do not silently clear an authenticated session', async () => {
    get.mockResolvedValue({ data: { userId: 'u-1', email: 'a@example.org', memberships: [] } });
    await fetchSession();
    get.mockResolvedValue({ error: {}, response: { status: 503 } });
    await expect(fetchSession()).rejects.toThrow();
    expect(getAuthState().user?.userId).toBe('u-1');
  });

  test('activeOrganizationIds only includes ACTIVE memberships', async () => {
    get.mockResolvedValue({
      data: {
        userId: 'u-1',
        email: 'multi@example.org',
        memberships: [
          { membershipId: 'm-1', organizationId: 'o-1', organizationSlug: 'a',
            organizationName: 'A', role: 'ADMIN', status: 'ACTIVE' },
          { membershipId: 'm-2', organizationId: 'o-2', organizationSlug: 'b',
            organizationName: 'B', role: 'MEMBER', status: 'PENDING' },
          { membershipId: 'm-3', organizationId: 'o-3', organizationSlug: 'c',
            organizationName: 'C', role: 'MEMBER', status: 'DISABLED' },
        ],
      },
      error: undefined,
    });
    await fetchSession();
    expect(activeOrganizationIds()).toEqual(['o-1']);
  });

  test('subscribers are notified of auth changes', async () => {
    const states: string[] = [];
    const unsubscribe = subscribeAuth((s) => states.push(s.user?.email ?? 'anon'));
    get.mockResolvedValue({ data: { token: 't' }, error: undefined });
    post.mockResolvedValue({
      data: { userId: 'u-1', email: 'a@example.org', memberships: [] },
      error: undefined,
    });
    await login('a@example.org', 'supersecret-password');
    unsubscribe();
    expect(states.at(-1)).toBe('a@example.org');
    expect(getAuthState().user?.email).toBe('a@example.org');
  });
});
