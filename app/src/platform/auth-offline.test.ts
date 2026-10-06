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

test('server rejection preserves the offline snapshot for offline-sync eligibility', async () => {
  localStorage.setItem('miezmerker-offline-session', JSON.stringify({
    user: { userId: 'user', email: 'field@example.org', memberships: [] },
  }));
  const { api } = await import('../api/client');
  vi.mocked(api.GET).mockResolvedValue({ error: {}, response: { status: 401 } });
  const { fetchSession, getAuthState } = await import('./auth');
  await fetchSession();
  // #31: session expiration locks management but must not destroy the
  // snapshot that offline-sync eligibility (#8) depends on.
  expect(getAuthState().user?.userId).toBe('user');
  expect(getAuthState().sessionVerified).toBe(false);
  expect(localStorage.getItem('miezmerker-offline-session')).not.toBeNull();
});
