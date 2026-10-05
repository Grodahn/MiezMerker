// @vitest-environment node
import 'fake-indexeddb/auto';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';

const { get, post } = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn() }));
vi.mock('../api/client', () => ({ api: { GET: get, POST: post } }));
import { fetchSession } from '../platform/auth';
import { OfflineIdentity, OfflineIdentityDatabase } from '../platform/offline-identity';
import { CollectorDatabase } from '../platform/offline-store';
import { CollectorObservationStore } from './observation-store';
import { BackendUploader } from './backend-upload';
import { claimNode } from './claim-node';

function stalledRequest(_path: string, options: { signal?: AbortSignal }): Promise<never> {
  return new Promise((_, reject) => {
    options.signal?.addEventListener('abort', () => reject(options.signal!.reason), { once: true });
  });
}

beforeEach(() => {
  get.mockReset(); post.mockReset();
  Object.defineProperty(globalThis, 'navigator', { value: { onLine: true }, configurable: true });
  // Exercise actual abort-aware request failures without ten-second tests.
  const timeout = AbortSignal.timeout.bind(AbortSignal);
  vi.spyOn(AbortSignal, 'timeout').mockImplementation(() => timeout(20));
  get.mockImplementation(async (path: string) => path === '/api/v1/auth/session'
    ? { data: { userId: 'user', email: 'admin@example.org', memberships: [
      { organizationId: 'org', status: 'ACTIVE', role: 'ADMIN' },
    ] } } : { data: { token: 'csrf' } });
});
afterEach(() => vi.restoreAllMocks());

test('a stalled CSRF request ends the upload and leaves observations retryable', async () => {
  get.mockImplementation(stalledRequest);
  const db = new CollectorDatabase(`timeout-outbox-${Math.random()}`);
  const store = new CollectorObservationStore(db);
  try {
    await store.putObservations([{
      nodeId: '44444444-4444-4444-8444-444444444444',
      incarnation: '55555555-5555-4555-9555-555555555555', sequence: 1n,
      chipId: 'chip', clockStatus: 0, epochMs: null, monotonicMs: 1n, bootCounter: 1,
    }], 'org');
    const result = await new BackendUploader(store, { maxRetries: 0 }).upload('org');
    expect(result.state).toBe('failed');
    expect(await store.pendingUploads('org')).toHaveLength(1);
    expect(post).not.toHaveBeenCalled();
  } finally { db.close(); await db.delete(); }
});

test.each(['/api/v1/devices', '/api/v1/devices/{deviceId}/credentials'])('stalled %s renewal terminates without caching a credential', async path => {
  post.mockImplementation((requestedPath: string, options: { signal?: AbortSignal }) =>
    requestedPath === path ? stalledRequest(requestedPath, options) : Promise.resolve({ data: { id: 'device' } }));
  const db = new OfflineIdentityDatabase(`timeout-identity-${Math.random()}`);
  const identity = new OfflineIdentity(db);
  try {
    await expect(identity.renew('user', 'org')).rejects.toThrow();
    expect(await identity.credential('user', 'org')).toBeNull();
    expect(await db.identities.get('user')).toBeDefined();
  } finally { db.close(); await db.delete(); }
});

test('a stalled claim reservation terminates so the same claim can be retried', async () => {
  await fetchSession();
  post.mockImplementation(stalledRequest);
  await expect(claimNode('org', {
    nodeId: '44444444-4444-4444-8444-444444444444', publicKeyX: 'x', publicKeyY: 'y',
    claimSignature: 'signature', timestampMillis: 1,
  }, { claimModeConfirmed: true })).rejects.toThrow();
  expect(post).toHaveBeenCalledTimes(1);
});
