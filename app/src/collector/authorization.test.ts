import 'fake-indexeddb/auto';
import { beforeEach, describe, expect, test, vi } from 'vitest';

vi.mock('../platform/auth', () => ({
  getAuthState: vi.fn(),
}));
import { getAuthState } from '../platform/auth';
import { resolveCredential, CredentialError } from './authorization';

const mockedAuth = vi.mocked(getAuthState);

describe('resolveCredential (offline credential handshake)', () => {
  beforeEach(() => {
    mockedAuth.mockReset();
    Object.defineProperty(globalThis, 'navigator', { value: { onLine: true }, configurable: true });
  });

  test('valid cached credential is reused without renewal', async () => {
    mockedAuth.mockReturnValue({
      user: { userId: 'u1', email: 'a@b.c', memberships: [
        { organizationId: 'org-a', status: 'ACTIVE', role: 'MEMBER' },
      ] }, csrfToken: null, activeOrganizationId: 'org-a',
      sessionChecked: true, sessionVerified: true,
    });
    const identity = { credential: async () => 'cached-jwt', renew: async () => 'renewed' };
    const resolved = await resolveCredential(identity as never, 'org-a');
    expect(resolved.credential).toBe('cached-jwt');
    expect(resolved.renewed).toBe(false);
  });

  test('expired credential renews when online', async () => {
    mockedAuth.mockReturnValue({
      user: { userId: 'u1', email: 'a@b.c', memberships: [
        { organizationId: 'org-a', status: 'ACTIVE', role: 'MEMBER' },
      ] }, csrfToken: null, activeOrganizationId: 'org-a',
      sessionChecked: true, sessionVerified: true,
    });
    const identity = { credential: async () => null, renew: async () => 'fresh-jwt' };
    const resolved = await resolveCredential(identity as never, 'org-a');
    expect(resolved.credential).toBe('fresh-jwt');
    expect(resolved.renewed).toBe(true);
  });

  test('no valid credential offline explains clearly that sync cannot proceed', async () => {
    mockedAuth.mockReturnValue({
      user: { userId: 'u1', email: 'a@b.c', memberships: [
        { organizationId: 'org-a', status: 'ACTIVE', role: 'MEMBER' },
      ] }, csrfToken: null, activeOrganizationId: 'org-a',
      sessionChecked: true, sessionVerified: true,
    });
    Object.defineProperty(globalThis, 'navigator', { value: { onLine: false }, configurable: true });
    const identity = { credential: async () => null, renew: async () => { throw new Error('nope'); } };
    await expect(resolveCredential(identity as never, 'org-a')).rejects.toMatchObject({ name: 'CredentialError' });
    await expect(resolveCredential(identity as never, 'org-a')).rejects.toThrow('Node-Sync kann nicht starten');
  });

  test('without login no credential is issued', async () => {
    mockedAuth.mockReturnValue({ user: null, csrfToken: null, activeOrganizationId: null,
      sessionChecked: true, sessionVerified: false });
    const identity = { credential: async () => 'x', renew: async () => 'y' };
    await expect(resolveCredential(identity as never, 'org-a')).rejects.toBeInstanceOf(CredentialError);
  });
});
