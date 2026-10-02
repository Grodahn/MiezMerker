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
} from './auth';

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

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
      return { data: undefined, error: undefined };
    });
    await login('a@example.org', 'supersecret-password');
    expect(getAuthState().user).not.toBeNull();
    await logout();
    expect(getAuthState().user).toBeNull();
    expect(getAuthState().csrfToken).toBeNull();
  });

  test('fetchSession returns null when unauthenticated', async () => {
    get.mockResolvedValue({ data: undefined, error: { message: 'unauthorized' } });
    expect(await fetchSession()).toBeNull();
    expect(getAuthState().user).toBeNull();
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
