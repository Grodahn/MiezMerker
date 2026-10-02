import 'fake-indexeddb/auto';
import { afterEach, expect, test, vi } from 'vitest';
import { OfflineIdentity, OfflineIdentityDatabase } from './offline-identity';
const post = vi.fn();
vi.mock('../api/client', () => ({ api: { POST: (...args: unknown[]) => post(...args) } }));
vi.mock('./auth', () => ({ fetchCsrfToken: async () => 'fresh-csrf' }));
let db = new OfflineIdentityDatabase('identity-test');
afterEach(async () => { await db.delete(); db = new OfflineIdentityDatabase('identity-test'); post.mockReset(); });

test('reloads the same non-exportable signing key and converges across tabs', async () => {
  const [first, second] = await Promise.all([
    new OfflineIdentity(db).keys('user-a'), new OfflineIdentity(db).keys('user-a'),
  ]);
  expect(await first.publicKey()).toEqual(await second.publicKey());
  db.close();
  db = new OfflineIdentityDatabase('identity-test');
  const restored = await new OfflineIdentity(db).keys('user-a');
  expect(restored.coordinates()).toEqual(first.coordinates());
  await expect(crypto.subtle.exportKey('jwk', restored.keyHandles().privateKey)).rejects.toThrow();
  const challenge = new Uint8Array(32).fill(7);
  const signature = await restored.signChallenge(challenge);
  const publicKey = await crypto.subtle.importKey('jwk', await first.publicKey(),
    { name: 'ECDSA', namedCurve: 'P-256' }, true, ['verify']);
  expect(await crypto.subtle.verify({ name: 'ECDSA', hash: 'SHA-256' }, publicKey,
    new Uint8Array(signature), challenge)).toBe(true);
  expect((await new OfflineIdentity(db).keys('user-b')).coordinates()).not.toEqual(first.coordinates());
});

test('cached credentials survive restart and work without backend access until expiry', async () => {
  const keys = await new OfflineIdentity(db).keys('user-a');
  const token = 'header.' + btoa(JSON.stringify({ sub: 'user-a', org: 'org-a', dev: 'device-a',
    dpk_x: keys.coordinates().x, dpk_y: keys.coordinates().y, exp: Math.floor(Date.now() / 1000) + 60 })) + '.signature';
  post.mockImplementation(async (path: string) => path === '/api/v1/devices'
    ? { data: { id: 'device-a' } }
    : { data: { credential: token, expiresInSeconds: 60,
      organizationId: 'org-a', deviceId: 'device-a' } });
  const identity = new OfflineIdentity(db);
  await identity.renewActive('user-a', ['org-a']);
  expect(post).toHaveBeenCalledTimes(2);
  db.close();
  db = new OfflineIdentityDatabase('identity-test');
  post.mockRejectedValue(new Error('offline'));
  const offline = new OfflineIdentity(db);
  expect(await offline.credential('user-a', 'org-a')).toBe(token);
  expect(await offline.credential('user-b', 'org-a')).toBeNull();
  expect(await offline.credential('user-a', 'org-b')).toBeNull();
  const now = vi.spyOn(Date, 'now').mockReturnValue(Date.now() + 61000);
  expect(await offline.credential('user-a', 'org-a')).toBeNull();
  now.mockRestore();
  await offline.forgetCredentials('user-a');
  expect(await offline.credential('user-a', 'org-a')).toBeNull();
  expect(post).toHaveBeenCalledTimes(2);
});

test('a response issued for another logged-in account cannot be cached', async () => {
  const keys = await new OfflineIdentity(db).keys('user-a');
  const token = 'header.' + btoa(JSON.stringify({ sub: 'user-b', org: 'org-a', dev: 'device-a',
    dpk_x: keys.coordinates().x, dpk_y: keys.coordinates().y, exp: Math.floor(Date.now() / 1000) + 60 })) + '.signature';
  post.mockImplementation(async (path: string) => path === '/api/v1/devices'
    ? { data: { id: 'device-a' } }
    : { data: { credential: token, expiresInSeconds: 60, organizationId: 'org-a', deviceId: 'device-a' } });
  const identity = new OfflineIdentity(db);
  await expect(identity.renew('user-a', 'org-a')).rejects.toThrow('binding mismatch');
  expect(await identity.credential('user-a', 'org-a')).toBeNull();
});

test('logout in another tab invalidates a renewal already waiting for the backend', async () => {
  const identity = new OfflineIdentity(db);
  const keys = await identity.keys('user-a');
  const token = 'header.' + btoa(JSON.stringify({ sub: 'user-a', org: 'org-a', dev: 'device-a',
    dpk_x: keys.coordinates().x, dpk_y: keys.coordinates().y, exp: Math.floor(Date.now() / 1000) + 60 })) + '.signature';
  let finish!: (value: unknown) => void;
  let requested!: () => void;
  const waiting = new Promise<void>(resolve => { requested = resolve; });
  post.mockImplementation(async (path: string) => {
    if (path === '/api/v1/devices') return { data: { id: 'device-a' } };
    requested();
    return new Promise(resolve => { finish = resolve; });
  });
  const renewal = identity.renew('user-a', 'org-a');
  const rejected = expect(renewal).rejects.toThrow('superseded by logout');
  await waiting;
  const otherTab = new OfflineIdentityDatabase('identity-test');
  await new OfflineIdentity(otherTab).forgetCredentials('user-a');
  otherTab.close();
  finish({ data: { credential: token, expiresInSeconds: 60, organizationId: 'org-a', deviceId: 'device-a' } });
  await rejected;
  expect(await identity.credential('user-a', 'org-a')).toBeNull();
});

test('one organization failure does not block renewal for the remaining organizations', async () => {
  const identity = new OfflineIdentity(db);
  const keys = await identity.keys('user-a');
  const token = 'header.' + btoa(JSON.stringify({ sub: 'user-a', org: 'org-b', dev: 'device-a',
    dpk_x: keys.coordinates().x, dpk_y: keys.coordinates().y, exp: Math.floor(Date.now() / 1000) + 60 })) + '.signature';
  post.mockImplementation(async (path: string, options: { body: { organizationId: string } }) => {
    if (path === '/api/v1/devices') return { data: { id: 'device-a' } };
    if (options.body.organizationId === 'org-a') return { error: { status: 403 } };
    return { data: { credential: token, expiresInSeconds: 60, organizationId: 'org-b', deviceId: 'device-a' } };
  });
  await expect(identity.renewActive('user-a', ['org-a', 'org-b'])).rejects.toThrow('Some offline credentials');
  expect(await identity.credential('user-a', 'org-a')).toBeNull();
  expect(await identity.credential('user-a', 'org-b')).toBe(token);
});
