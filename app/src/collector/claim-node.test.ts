import 'fake-indexeddb/auto';
import { beforeEach, describe, expect, test, vi } from 'vitest';

const post = vi.hoisted(() => vi.fn());
vi.mock('../api/client', () => ({ api: { POST: post } }));
vi.mock('../platform/auth', () => ({ getAuthState: vi.fn() }));
import { getAuthState } from '../platform/auth';
import { assertAdminMayClaim, claimNode, ClaimError } from './claim-node';
import { CollectorDatabase } from '../platform/offline-store';

const mockedAuth = vi.mocked(getAuthState);

function authAs(role: string | null) {
  mockedAuth.mockReturnValue(role === null
    ? { user: null, csrfToken: null, activeOrganizationId: null }
    : {
        user: { userId: 'u1', email: 'a@b.c', memberships: [
          { organizationId: 'org-a', status: 'ACTIVE', role },
        ] },
        csrfToken: 'csrf', activeOrganizationId: 'org-a',
      });
}

describe('claiming (UNCLAIMED only ACTIVE ADMIN, #18)', () => {
  beforeEach(() => {
    post.mockReset();
    mockedAuth.mockReset();
    Object.defineProperty(globalThis, 'navigator', { value: { onLine: true }, configurable: true });
  });

  test('MEMBER receives no hidden escalation path', () => {
    authAs('MEMBER');
    expect(() => assertAdminMayClaim('org-a')).toThrow(ClaimError);
    expect(() => assertAdminMayClaim('org-a')).toThrow('Nur ADMIN');
  });

  test('claim requires physical claim mode confirmation', async () => {
    authAs('ADMIN');
    await expect(claimNode('org-a', {
      nodeId: '44444444-4444-4444-8444-444444444444',
      publicKeyX: 'x'.repeat(43), publicKeyY: 'y'.repeat(43),
      claimSignature: 's'.repeat(86), timestampMillis: 1,
    }, { claimModeConfirmed: false })).rejects.toThrow('Claim-Modus');
    expect(post).not.toHaveBeenCalled();
  });

  test('ADMIN can claim UNCLAIMED node; retry is safe', async () => {
    authAs('ADMIN');
    post.mockResolvedValue({ data: { nodeId: '44444444-4444-4444-8444-444444444444', organizationId: 'org-a', receipt: 'jwt' }, response: { status: 200 } });
    const outcome = await claimNode('org-a', {
      nodeId: '44444444-4444-4444-8444-444444444444',
      publicKeyX: 'x'.repeat(43), publicKeyY: 'y'.repeat(43),
      claimSignature: 's'.repeat(86), timestampMillis: 1,
    }, { claimModeConfirmed: true });
    expect(outcome.receipt).toBe('jwt');
    expect(post.mock.calls[0][1].body.organizationId).toBe('org-a');
    const db = new CollectorDatabase();
    const saved = await db.nodeMeta.get(outcome.nodeId);
    expect(saved?.claimReceipt).toBe('jwt');
    expect(saved?.claimState).toBe(0);
    db.close();
  });

  test('node of another organization conflicts without takeover', async () => {
    authAs('ADMIN');
    post.mockResolvedValue({ error: { status: 409 }, response: { status: 409 } });
    await expect(claimNode('org-a', {
      nodeId: '44444444-4444-4444-8444-444444444444',
      publicKeyX: 'x'.repeat(43), publicKeyY: 'y'.repeat(43),
      claimSignature: 's'.repeat(86), timestampMillis: 1,
    }, { claimModeConfirmed: true })).rejects.toThrow('anderen Organisation');
  });

  test('offline claiming is explicitly blocked', async () => {
    authAs('ADMIN');
    Object.defineProperty(globalThis, 'navigator', { value: { onLine: false }, configurable: true });
    await expect(claimNode('org-a', {
      nodeId: 'n', publicKeyX: 'x', publicKeyY: 'y', claimSignature: 's', timestampMillis: 1,
    }, { claimModeConfirmed: true })).rejects.toThrow('Internet');
  });


});
