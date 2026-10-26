import 'fake-indexeddb/auto';
import { beforeEach, describe, expect, test, vi } from 'vitest';

const post = vi.hoisted(() => vi.fn());
vi.mock('../api/client', () => ({ api: { POST: post } }));
vi.mock('../platform/auth', () => ({ getAuthState: vi.fn() }));
import { ClaimAttempt } from '../diagnostics/claim-attempt';
import { getAuthState } from '../platform/auth';
import { assertAdminMayClaim, claimNode, ClaimError } from './claim-node';
import { CollectorDatabase } from '../platform/offline-store';

const mockedAuth = vi.mocked(getAuthState);

function authAs(role: string | null) {
  mockedAuth.mockReturnValue(role === null
    ? { user: null, csrfToken: null, activeOrganizationId: null, sessionChecked: true, sessionVerified: false }
    : {
        user: { userId: 'u1', email: 'a@b.c', memberships: [
          { organizationId: 'org-a', status: 'ACTIVE', role },
        ] },
        csrfToken: 'csrf', activeOrganizationId: 'org-a', sessionChecked: true, sessionVerified: true,
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

  test('unauthenticated user cannot claim', async () => {
    authAs(null);
    await expect(claimNode('org-a', {
      nodeId: '44444444-4444-4444-8444-444444444444',
      publicKeyX: 'x'.repeat(43), publicKeyY: 'y'.repeat(43),
      claimSignature: 's'.repeat(86), timestampMillis: 1,
    })).rejects.toThrow('aktive Mitgliedschaft');
    expect(post).not.toHaveBeenCalled();
  });

  test('ADMIN can claim UNCLAIMED node; retry is safe', async () => {
    authAs('ADMIN');
    post.mockResolvedValue({ data: { nodeId: '44444444-4444-4444-8444-444444444444', organizationId: 'org-a', receipt: 'jwt' }, response: { status: 200 } });
    const outcome = await claimNode('org-a', {
      nodeId: '44444444-4444-4444-8444-444444444444',
      publicKeyX: 'x'.repeat(43), publicKeyY: 'y'.repeat(43),
      claimSignature: 's'.repeat(86), timestampMillis: 1,
    });
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
    })).rejects.toThrow('anderen Organisation');
  });

  test('offline claiming is explicitly blocked', async () => {
    authAs('ADMIN');
    Object.defineProperty(globalThis, 'navigator', { value: { onLine: false }, configurable: true });
    await expect(claimNode('org-a', {
      nodeId: 'n', publicKeyX: 'x', publicKeyY: 'y', claimSignature: 's', timestampMillis: 1,
    })).rejects.toThrow('Internet');
  });


  test.each([403,409,503])('backend %s shares UI/event code and attempt header without proof in events', async status => {
    authAs('ADMIN'); const attempt=new ClaimAttempt();
    const code = status === 403 ? 'MM-BACKEND-403' : status === 409 ? 'MM-API-002' : 'MM-API-003';
    post.mockResolvedValue({ error: { code }, response: { status } });
    await expect(claimNode('org-a', { nodeId: 'node-secret', publicKeyX: 'key-secret',publicKeyY:'key-secret',
      claimSignature:'signature-secret',timestampMillis:1 }, { attempt })).rejects.toMatchObject({ code });
    expect(attempt.events.at(-1)).toMatchObject({ phase:'backend_claim',status:'rejected',code });
    expect(post.mock.calls[0][1].headers['X-MM-Claim-Attempt']).toBe(attempt.attemptId);
    expect(attempt.export()).not.toMatch(/key-secret|signature-secret|node-secret/);
  });
});
