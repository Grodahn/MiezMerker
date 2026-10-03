import { afterEach, expect, test, vi } from 'vitest';
const mocks = vi.hoisted(() => ({
  state: { user: null as null | { userId: string; memberships: { organizationId: string; role: string }[] } },
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
let stop = () => {};
afterEach(() => { stop(); vi.resetAllMocks(); });

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
  expect(mocks.forget).toHaveBeenCalledWith('user-a');
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
