import { beforeEach, expect, test, vi } from 'vitest';

vi.mock('../api/client', () => ({ api: { GET: vi.fn(), POST: vi.fn() } }));

beforeEach(() => { vi.resetModules(); localStorage.clear(); sessionStorage.clear(); });

test('cold offline start restores account and chosen active organization without a CSRF token', async () => {
  localStorage.setItem('miezmerker-offline-session', JSON.stringify({
    user: { userId: 'user', email: 'field@example.org', memberships: [
      { organizationId: 'org-a', status: 'ACTIVE', role: 'MEMBER' },
      { organizationId: 'org-b', status: 'ACTIVE', role: 'ADMIN' },
      { organizationId: 'disabled', status: 'DISABLED', role: 'ADMIN' },
    ] }, activeOrganizationId: 'org-b', csrfToken: 'stale',
  }));
  const { getAuthState } = await import('./auth');
  expect(getAuthState().user?.userId).toBe('user');
  expect(getAuthState().activeOrganizationId).toBe('org-b');
  expect(getAuthState().csrfToken).toBeNull();
  expect(getAuthState().user?.memberships).toHaveLength(2);
});

test('server rejection clears the offline account snapshot', async () => {
  localStorage.setItem('miezmerker-offline-session', JSON.stringify({
    user: { userId: 'user', email: 'field@example.org', memberships: [] },
  }));
  const { api } = await import('../api/client');
  vi.mocked(api.GET).mockResolvedValue({ error: {}, response: { status: 401 } } as never);
  const { fetchSession, getAuthState } = await import('./auth');
  await fetchSession();
  expect(getAuthState().user).toBeNull();
  expect(localStorage.getItem('miezmerker-offline-session')).toBeNull();
});
