import 'fake-indexeddb/auto';
import { liveQuery } from 'dexie';
import { describe, expect, test, vi } from 'vitest';
import { isCollectorPath, offlineSyncExpiresAt, resolveGateStatus } from './gate';
import { OfflineIdentity, OfflineIdentityDatabase } from './offline-identity';
import type { AuthState } from './auth';

function auth(overrides: Partial<AuthState>): AuthState {
  return {
    user: null, csrfToken: null, activeOrganizationId: null,
    sessionChecked: false, sessionVerified: false, ...overrides,
  };
}

const user = { userId: 'u', email: 'a@example.org', memberships: [
  { organizationId: 'org', status: 'ACTIVE' as const, role: 'MEMBER' as const },
] };

describe('centralized app-shell gate (#31)', () => {
  test('collector paths are / and /sync only', () => {
    expect(isCollectorPath('/')).toBe(true);
    expect(isCollectorPath('/sync')).toBe(true);
    expect(isCollectorPath('/nodes')).toBe(false);
    expect(isCollectorPath('/cats')).toBe(false);
    expect(isCollectorPath('/sites')).toBe(false);
    expect(isCollectorPath('/observations')).toBe(false);
    expect(isCollectorPath('/visits')).toBe(false);
    expect(isCollectorPath('/admin/members')).toBe(false);
  });

  test('unresolved session never renders protected content', () => {
    const pending = auth({ user, activeOrganizationId: 'org', sessionChecked: false, sessionVerified: false });
    for (const path of ['/', '/sync', '/nodes', '/sites', '/admin/members']) {
      expect(resolveGateStatus(pending, true, false, path)).toBe('loading');
      expect(resolveGateStatus(pending, true, true, path)).toBe(isCollectorPath(path) ? 'offline-sync' : 'loading');
      expect(resolveGateStatus(pending, true, null, path)).toBe('loading');
    }
  });

  test('online verified session renders authenticated app', () => {
    const verified = auth({ user, activeOrganizationId: 'org', sessionChecked: true, sessionVerified: true });
    for (const path of ['/', '/sync', '/nodes', '/cats', '/sites', '/observations', '/visits', '/admin/members']) {
      expect(resolveGateStatus(verified, true, false, path)).toBe('authenticated');
    }
  });

  test('verified session offline does not render management', () => {
    const verified = auth({ user, activeOrganizationId: 'org', sessionChecked: true, sessionVerified: true });
    expect(resolveGateStatus(verified, false, false, '/nodes')).toBe('login');
    expect(resolveGateStatus(verified, false, true, '/nodes')).toBe('login');
    expect(resolveGateStatus(verified, false, true, '/sync')).toBe('offline-sync');
  });

  test('fresh unauthenticated browser sees only login', () => {
    const anon = auth({ sessionChecked: true, sessionVerified: false });
    for (const path of ['/', '/sync', '/nodes', '/cats', '/sites', '/observations', '/visits', '/admin/members']) {
      expect(resolveGateStatus(anon, true, false, path)).toBe('login');
      expect(resolveGateStatus(anon, true, null, path)).toBe('login');
    }
  });

  test('snapshot without verified session never renders management', () => {
    const snapshot = auth({ user, activeOrganizationId: 'org', sessionChecked: true, sessionVerified: false });
    for (const path of ['/nodes', '/cats', '/sites', '/observations', '/visits', '/admin/members']) {
      expect(resolveGateStatus(snapshot, true, true, path)).toBe('login');
      expect(resolveGateStatus(snapshot, true, false, path)).toBe('login');
    }
  });

  test('offline-sync exception keeps only /sync usable and is not anonymous', () => {
    const snapshot = auth({ user, activeOrganizationId: 'org', sessionChecked: true, sessionVerified: false });
    expect(resolveGateStatus(snapshot, false, true, '/')).toBe('offline-sync');
    expect(resolveGateStatus(snapshot, false, true, '/sync')).toBe('offline-sync');
    expect(resolveGateStatus(snapshot, false, true, '/nodes')).toBe('login');
    expect(resolveGateStatus(snapshot, false, true, '/sites')).toBe('login');
    expect(resolveGateStatus(snapshot, false, false, '/sync')).toBe('login');
    expect(resolveGateStatus(snapshot, false, false, '/')).toBe('login');
    const noOrg = auth({ user, activeOrganizationId: null, sessionChecked: true, sessionVerified: false });
    expect(resolveGateStatus(noOrg, false, true, '/sync')).toBe('login');
    const anon = auth({ sessionChecked: true, sessionVerified: false });
    expect(resolveGateStatus(anon, false, true, '/sync')).toBe('login');
  });

  test('pending credential lookup stays neutral for collector, login for management', () => {
    const snapshot = auth({ user, activeOrganizationId: 'org', sessionChecked: true, sessionVerified: false });
    expect(resolveGateStatus(snapshot, false, null, '/sync')).toBe('loading');
    expect(resolveGateStatus(snapshot, false, null, '/')).toBe('loading');
    expect(resolveGateStatus(snapshot, false, null, '/nodes')).toBe('login');
  });

  test('an offline credential cannot replace an ACTIVE membership', () => {
    for (const status of ['PENDING', 'DISABLED'] as const) {
      const snapshot = auth({ user: { ...user, memberships: [{ organizationId: 'org', role: 'MEMBER', status }] },
        activeOrganizationId: 'org', sessionChecked: true });
      expect(resolveGateStatus(snapshot, false, true, '/sync')).toBe('login');
    }
  });
});

test('offline eligibility observes credential removal through another database connection', async () => {
  const database = new OfflineIdentityDatabase();
  const identity = new OfflineIdentity(database);
  const keys = await identity.keys('u');
  await database.identities.put({ userId: 'u', deviceId: 'device', keys: keys.keyHandles() });
  const expiresAt = Date.now() + 60_000;
  await database.credentials.put({ userId: 'u', organizationId: 'org', deviceId: 'device',
    credential: 'bound-credential', expiresAt });
  const observed: (number | null)[] = [];
  const subscription = liveQuery(() => offlineSyncExpiresAt('u', 'org')).subscribe(value => observed.push(value));
  try {
    await vi.waitFor(() => expect(observed.at(-1)).toBe(expiresAt));
    await identity.forgetCredentials('u');
    await vi.waitFor(() => expect(observed.at(-1)).toBeNull());
  } finally {
    subscription.unsubscribe(); await database.delete();
  }
});
