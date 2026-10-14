import { afterEach, expect, test, vi } from 'vitest';
const mocks = vi.hoisted(() => ({
  state: { sessionVerified: true, user: null as null | { userId: string; memberships: { organizationId: string; role: string }[] } },
  listener: () => {}, renew: vi.fn(), forget: vi.fn(), close: vi.fn(),
}));
vi.mock('./auth', () => ({
  getAuthState: () => mocks.state,
  activeOrganizationIds: () => mocks.state.user?.memberships.map(m => m.organizationId) ?? [],
  fetchSession: async () => mocks.state.user,
  subscribeAuth: (listener: () => void) => { mocks.listener = listener; return () => {}; },
}));
vi.mock('./offline-identity', () => ({
  OfflineIdentityDatabase: class { close = mocks.close; },
  OfflineIdentity: class { renewActive = mocks.renew; forgetCredentials = mocks.forget; },
}));
import { startOfflineRenewal } from './offline-renewal';
import { publishAuthorization } from './authorization-status';
let stop = () => {};
afterEach(() => {
  stop(); vi.resetAllMocks(); localStorage.clear();
  Object.defineProperty(navigator, 'onLine', { configurable: true, value: true });
});

test('logout on a cold offline start clears credentials before any renewal ran', () => {
  Object.defineProperty(navigator, 'onLine', { configurable: true, value: false });
  mocks.state.user = { userId: 'offline-user', memberships: [{ organizationId: 'org', role: 'MEMBER' }] };
  mocks.forget.mockResolvedValue(undefined);
  stop = startOfflineRenewal();
  mocks.state.user = null;
  mocks.listener();
  expect(mocks.forget).toHaveBeenCalledWith('offline-user', undefined);
});

test('logout cleanup runs immediately while renewal is waiting on a network response', async () => {
  mocks.state.user = { userId: 'user-a', memberships: [{ organizationId: 'org-a', role: 'MEMBER' }] };
  let finish!: () => void;
  mocks.renew.mockImplementation(() => new Promise<void>(resolve => { finish = resolve; }));
  mocks.forget.mockResolvedValue(undefined);
  stop = startOfflineRenewal();
  mocks.listener();
  await vi.waitFor(() => expect(mocks.renew).toHaveBeenCalledTimes(1));
  mocks.state.user = null;
  mocks.listener();
  expect(mocks.forget).toHaveBeenCalledWith('user-a', undefined);
  finish();
});

test('membership role changes trigger renewal even when organization IDs stay the same', async () => {
  mocks.state.user = { userId: 'user-a', memberships: [{ organizationId: 'org-a', role: 'ADMIN' }] };
  mocks.renew.mockResolvedValue(undefined);
  stop = startOfflineRenewal();
  mocks.listener();
  await vi.waitFor(() => expect(mocks.renew).toHaveBeenCalledTimes(1));
  mocks.state.user = { userId: 'user-a', memberships: [{ organizationId: 'org-a', role: 'MEMBER' }] };
  mocks.listener();
  await vi.waitFor(() => expect(mocks.renew).toHaveBeenCalledTimes(2));
});

test('queued account cleanup carries its original revision rather than deleting a newer grant', () => {
  Object.defineProperty(navigator, 'onLine', { configurable: true, value: false });
  mocks.state.user = { userId: 'user-a', memberships: [{ organizationId: 'org-a', role: 'MEMBER' }] };
  const original = publishAuthorization('user-a', false, ['org-a']);
  mocks.forget.mockResolvedValue(undefined);
  stop = startOfflineRenewal();
  mocks.state.user = null;
  mocks.listener();
  publishAuthorization('user-a', false, ['org-a']);
  expect(mocks.forget).toHaveBeenCalledWith('user-a', original.revision);
});
