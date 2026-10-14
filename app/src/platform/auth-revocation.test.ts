import 'fake-indexeddb/auto';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import type * as Auth from './auth';
import type { OfflineIdentityDatabase, OfflineIdentity } from './offline-identity';
const { get, post } = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn() }));
vi.mock('../api/client', () => ({ api: { GET: get, POST: post } }));
const user = { userId: 'field-user', email: 'field@example.org', memberships: [
  { organizationId: 'a', role: 'ADMIN', status: 'ACTIVE' },
  { organizationId: 'b', role: 'MEMBER', status: 'ACTIVE' },
] };
const disabled = () => ({ error: {}, response: { status: 401, headers: new Headers({
  'X-MM-Auth-Status': 'ACCOUNT_DISABLED', 'X-MM-User-Id': user.userId,
}) } });
let auth: typeof Auth;
let db: OfflineIdentityDatabase;
let identity: OfflineIdentity;
beforeEach(async () => {
  vi.resetModules(); get.mockReset(); post.mockReset(); localStorage.clear(); sessionStorage.clear();
  localStorage.setItem('miezmerker-offline-session', JSON.stringify({ user, activeOrganizationId: 'a' }));
  auth = await import('./auth');
  const module = await import('./offline-identity');
  db = new module.OfflineIdentityDatabase(); identity = new module.OfflineIdentity(db);
  await identity.keys(user.userId); await db.identities.update(user.userId, { deviceId: 'device' });
  for (const organizationId of ['a', 'b']) await db.credentials.put({
    userId: user.userId, organizationId, deviceId: 'device', credential: `cached-${organizationId}`, expiresAt: Date.now() + 60_000,
  });
  get.mockResolvedValue({ data: user });
});
afterEach(async () => { await db.delete(); });

test('active session revocation blocks immediately, deletes only account credentials and survives reload and a stale snapshot', async () => {
  await auth.fetchSession();
  await db.credentials.put({ userId: 'other', organizationId: 'a', deviceId: 'other', credential: 'other', expiresAt: Date.now() + 60_000 });
  get.mockResolvedValue(disabled());
  await auth.fetchSession();
  expect(auth.getAuthState()).toMatchObject({ user: null, activeOrganizationId: null, sessionVerified: false,
    revocationMessage: 'Dein Zugang wurde vom Betreiber deaktiviert.' });
  expect(await db.credentials.where('userId').equals(user.userId).count()).toBe(0);
  expect(await db.credentials.get(['other', 'a'])).toBeDefined();
  // Another tab writing its old snapshot must not resurrect the identity.
  localStorage.setItem('miezmerker-offline-session', JSON.stringify({ user, activeOrganizationId: 'a' }));
  vi.resetModules(); const restored = await import('./auth');
  expect(restored.getAuthState().user).toBeNull();
  expect(await identity.credential(user.userId, 'a')).toBeNull();
});

test.each(['expired', 'offline', 'timeout', '500', 'incomplete', 'wrong-user'])('%s is not account revocation', async kind => {
  const failure = kind === 'expired' ? { error: {}, response: { status: 401 } }
    : kind === '500' ? { error: {}, response: { status: 500 } }
      : kind === 'incomplete' ? { data: { userId: user.userId, email: user.email } }
        : { ...disabled(), response: { status: 401, headers: new Headers({ 'X-MM-Auth-Status': 'ACCOUNT_DISABLED', 'X-MM-User-Id': 'other' }) } };
  if (kind === 'offline' || kind === 'timeout') get.mockRejectedValue(new DOMException('unreachable', kind === 'timeout' ? 'TimeoutError' : 'NetworkError'));
  else get.mockResolvedValue(failure);
  await auth.fetchSession().catch(() => {});
  expect(auth.getAuthState().user?.userId).toBe(user.userId);
  expect(auth.getAuthState().revocationMessage).toBeUndefined();
  expect(await identity.credential(user.userId, 'a')).toBe('cached-a');
  expect(await db.credentials.count()).toBe(2);
});

test('an incomplete login response cannot remove otherwise valid offline credentials', async () => {
  get.mockResolvedValue({ data: { token: 'csrf' } });
  post.mockResolvedValue({ data: { userId: user.userId, email: user.email } });
  await expect(auth.login(user.email, 'password')).rejects.toThrow('Login fehlgeschlagen');
  expect(auth.getAuthState().user?.userId).toBe(user.userId);
  expect(await identity.credential(user.userId, 'a')).toBe('cached-a');
  expect(await db.credentials.count()).toBe(2);
});

test.each(['missing-role', 'empty-organization', 'duplicate-organization'])('%s session replies cannot replace offline authorization', async kind => {
  get.mockResolvedValue({ data: { ...user, memberships: kind === 'missing-role'
    ? [{ organizationId: 'a', status: 'ACTIVE' }]
    : kind === 'empty-organization' ? [{ ...user.memberships[0], organizationId: '' }]
      : [user.memberships[0], { ...user.memberships[0], status: 'DISABLED' }] } });
  await expect(auth.fetchSession()).rejects.toThrow('Sitzung konnte nicht geladen werden');
  expect(auth.activeOrganizationIds()).toEqual(['a', 'b']);
  expect(await db.credentials.count()).toBe(2);
});

test('blocked localStorage does not crash authentication startup', async () => {
  const access = vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new DOMException('blocked', 'SecurityError'); });
  try {
    vi.resetModules();
    const fresh = await import('./auth');
    expect(fresh.getAuthState().user).toBeNull();
  } finally { access.mockRestore(); }
});

test('a failed authentication lock cannot suppress subsequent confirmed revocation', async () => {
  const status = await import('./authorization-status');
  const lock = vi.spyOn(status, 'withAuthorizationLock').mockRejectedValueOnce(new Error('lock unavailable'));
  try {
    await expect(auth.login(user.email, 'password')).rejects.toThrow('lock unavailable');
    get.mockResolvedValue(disabled());
    await auth.fetchSession();
    expect(auth.getAuthState().user).toBeNull();
  } finally { lock.mockRestore(); }
});

test.each(['account', 'organization'])('%s revocation during the last credential read is observed without a storage event', async scope => {
  await auth.fetchSession();
  const status = await import('./authorization-status');
  const original = db.invalidations.get.bind(db.invalidations);
  let reads = 0;
  const read = vi.spyOn(db.invalidations, 'get').mockImplementation(key => original(key).then(result => {
    if (++reads === 2) status.publishAuthorization(user.userId, scope === 'account', scope === 'account' ? [] : ['b']);
    return result;
  }));
  try {
    expect(await identity.credential(user.userId, 'a')).toBeNull();
    expect(auth.getAuthState().user?.memberships[0].status).toBe('ACTIVE'); // Event has not arrived.
  } finally { read.mockRestore(); }
});

test.each(['account', 'organization'])('%s revocation blocks BLE and provisioning before the storage event arrives', async scope => {
  const { bindNodeOperation } = await import('../collector/node-operation');
  const { requireActiveRole } = await import('../collector/authorization');
  const { assertAdminMayProvision } = await import('../collector/node-setup');
  const raw = { connect: vi.fn(), read: vi.fn(), write: vi.fn(), disconnect: vi.fn().mockResolvedValue(undefined) };
  const operation = bindNodeOperation(raw, user.userId, 'a');
  try {
    (await import('./authorization-status')).publishAuthorization(user.userId, scope === 'account', scope === 'account' ? [] : ['b']);
    expect(() => requireActiveRole('a')).toThrow('Keine aktive Mitgliedschaft');
    expect(() => assertAdminMayProvision('a')).toThrow('Keine aktive Mitgliedschaft');
    await expect(operation.transport.write(new Uint8Array([1]))).rejects.toThrow('abgebrochen');
    expect(raw.write).not.toHaveBeenCalled();
    expect(raw.disconnect).toHaveBeenCalledOnce();
  } finally { await operation.close(); }
});

test('evidence persistence rechecks revocation after its pending database read', async () => {
  const { CollectorDatabase } = await import('./offline-store');
  const { CollectorObservationStore } = await import('../collector/observation-store');
  const collector = new CollectorDatabase(`evidence-fence-${crypto.randomUUID()}`);
  const store = new CollectorObservationStore(collector, user.userId);
  const status = await import('./authorization-status');
  const original = collector.timeEvidence.get.bind(collector.timeEvidence);
  const read = vi.spyOn(collector.timeEvidence, 'get').mockImplementation(key => original(key).then(row => {
    status.publishAuthorization(user.userId, true, []);
    return row;
  }));
  try {
    await expect(store.putTimeEvidence({ id: 'late', organizationId: 'a', nodeId: 'node', incarnation: 'inc',
      bootCounter: '1', kind: 0, payload: 'exact-boot-bytes', uploaded: false })).rejects.toThrow('Offline-Daten');
    expect(await collector.timeEvidence.count()).toBe(0);
  } finally { read.mockRestore(); await collector.delete(); }
});

test.each(['DISABLED', 'removed', 'inactive-organization'])('%s removes A while retaining B and prevents stale restoration', async reason => {
  await auth.fetchSession();
  const memberships = reason === 'DISABLED' ? [{ ...user.memberships[0], status: 'DISABLED' }, user.memberships[1]] : [user.memberships[1]];
  get.mockResolvedValue({ data: { ...user, memberships } }); await auth.fetchSession();
  expect(auth.activeOrganizationIds()).toEqual(['b']);
  expect(auth.getAuthState().activeOrganizationId).toBe('b');
  expect(auth.getAuthState().revocationMessage).toContain('keinen Zugriff');
  expect(await db.credentials.get([user.userId, 'a'])).toBeUndefined();
  expect(await identity.credential(user.userId, 'b')).toBe('cached-b');
  localStorage.setItem('miezmerker-offline-session', JSON.stringify({ user, activeOrganizationId: 'a' }));
  vi.resetModules(); const restored = await import('./auth');
  expect(restored.activeOrganizationIds()).toEqual(['b']);
});

test('another tab reduces permissions, cancels a running BLE write, and defeats an older status promise', async () => {
  let finish!: (value: unknown) => void;
  get.mockImplementationOnce(() => new Promise(resolve => { finish = resolve; }));
  const pending = auth.fetchSession();
  const { bindNodeOperation } = await import('../collector/node-operation');
  let finishWrite!: () => void;
  const transport = { disconnect: vi.fn().mockResolvedValue(undefined), connect: vi.fn(), read: vi.fn(),
    write: vi.fn().mockImplementation(() => new Promise<void>(resolve => { finishWrite = resolve; })) };
  const operation = bindNodeOperation(transport, user.userId, 'a');
  const inFlight = expect(operation.transport.write(new Uint8Array([1]))).rejects.toThrow('abgebrochen');
  const status = await import('./authorization-status');
  status.publishAuthorization(user.userId, true, []);
  status.advanceAuthenticationEpoch();
  window.dispatchEvent(new StorageEvent('storage', { key: status.authorizationStatusKey + user.userId }));
  expect(auth.getAuthState().user).toBeNull();
  expect(transport.disconnect).toHaveBeenCalledOnce();
  finishWrite(); await inFlight;
  await expect(operation.transport.write(new Uint8Array([1]))).rejects.toThrow('abgebrochen');
  expect(transport.write).toHaveBeenCalledOnce();
  finish({ data: user }); await pending;
  expect(auth.getAuthState().user).toBeNull();
  await operation.close();
});

test.each(['account', 'organization'])('%s revocation defeats in-flight issuance and requires backend-confirmed reactivation', async scope => {
  await auth.fetchSession();
  const keys = await identity.keys(user.userId);
  const credential = 'header.' + btoa(JSON.stringify({ sub: user.userId, org: 'a', dev: 'device',
    dpk_x: keys.coordinates().x, dpk_y: keys.coordinates().y, exp: Math.floor(Date.now() / 1000) + 60 })) + '.signature';
  let finish!: (value: unknown) => void;
  post.mockImplementation(async path => path === '/api/v1/devices' ? { data: { id: 'device' } }
    : new Promise(resolve => { finish = resolve; }));
  get.mockImplementation(async path => path === '/api/v1/auth/csrf' ? { data: { token: 'csrf' } } : { data: user });
  const renewal = identity.renew(user.userId, 'a');
  const rejected = expect(renewal).rejects.toThrow(/superseded/);
  await vi.waitFor(() => expect(finish).toBeDefined());
  get.mockResolvedValue(scope === 'account' ? disabled() : { data: { ...user, memberships: [user.memberships[1]] } });
  await auth.fetchSession();
  finish({ data: { credential, expiresInSeconds: 60, organizationId: 'a', deviceId: 'device' } }); await rejected;
  await expect(identity.renew(user.userId, 'a')).rejects.toThrow('revoked');
  expect(await identity.credential(user.userId, 'a')).toBeNull();
  get.mockResolvedValue({ data: user }); await auth.fetchSession();
  expect(auth.activeOrganizationIds()).toEqual(['a', 'b']);
  expect(await identity.credential(user.userId, 'a')).toBeNull(); // Needs fresh issuance, never the old cached token.
  get.mockImplementation(async path => path === '/api/v1/auth/csrf' ? { data: { token: 'csrf' } } : { data: user });
  post.mockImplementation(async path => path === '/api/v1/devices' ? { data: { id: 'device' } }
    : { data: { credential, expiresInSeconds: 60, organizationId: 'a', deviceId: 'device' } });
  await identity.renew(user.userId, 'a');
  expect(await identity.credential(user.userId, 'a')).toBe(credential);
});

test('failed cleanup cannot make old credentials usable after backend reactivation, and old cleanup cannot delete a newer grant', async () => {
  const prototype = (await import('./offline-identity')).OfflineIdentity.prototype;
  const cleanup = vi.spyOn(prototype, 'forgetCredentials').mockRejectedValueOnce(new Error('IDB unavailable'));
  await db.credentials.update([user.userId, 'a'], { expiresAt: Date.now() + 3_600_000 });
  get.mockResolvedValue(disabled()); await auth.fetchSession();
  expect(await db.credentials.count()).toBe(2);
  expect(await identity.credential(user.userId, 'a')).toBeNull();
  const revoked = (await import('./authorization-status')).authorizationStatus(user.userId)!;
  get.mockResolvedValue({ data: user }); await auth.fetchSession();
  expect(await identity.credential(user.userId, 'a')).toBeNull();
  await identity.forgetCredentials(user.userId, revoked.revision);
  expect(await db.credentials.count()).toBe(2); // Obsolete cleanup is ignored.
  const keys = await identity.keys(user.userId);
  const credential = 'header.' + btoa(JSON.stringify({ sub: user.userId, org: 'a', dev: 'device',
    dpk_x: keys.coordinates().x, dpk_y: keys.coordinates().y, exp: Math.floor(Date.now() / 1000) + 3600 })) + '.signature';
  get.mockResolvedValue({ data: { token: 'csrf' } });
  post.mockImplementation(async path => path === '/api/v1/devices' ? { data: { id: 'device' } }
    : { data: { credential, expiresInSeconds: 3600, organizationId: 'a', deviceId: 'device' } });
  await identity.renewActive(user.userId, ['a'], false);
  expect(await identity.credential(user.userId, 'a')).toBe(credential); // Timer retries unusable, long-lived cached tokens.
  cleanup.mockRestore();
});

test('account switch and logout defeat status responses that began under the previous identity', async () => {
  let finish!: (value: unknown) => void;
  get.mockImplementationOnce(() => new Promise(resolve => { finish = resolve; }));
  const pending = auth.fetchSession();
  get.mockResolvedValue({ data: { token: 'csrf' } });
  post.mockResolvedValue({ data: { userId: 'new', email: 'new@example.org', memberships: [] } });
  await auth.login('new@example.org', 'password'); finish(disabled()); await pending;
  expect(auth.getAuthState().user?.userId).toBe('new');
  get.mockImplementationOnce(() => new Promise(resolve => { finish = resolve; }));
  const old = auth.fetchSession();
  get.mockResolvedValue({ data: { token: 'csrf' } }); post.mockResolvedValue({ response: { ok: true, status: 204 } });
  await auth.logout(); finish({ data: user }); await old;
  expect(auth.getAuthState().user).toBeNull();
});

test.each(['login', 'status'])('a confirmed %s account switch withdraws the previous tab grant without storage events or after later refreshes', async mode => {
  const previousAuth = auth;
  const { bindNodeOperation } = await import('../collector/node-operation');
  const { requireActiveRole } = await import('../collector/authorization');
  const raw = { connect: vi.fn(), read: vi.fn(), write: vi.fn(), disconnect: vi.fn().mockResolvedValue(undefined) };
  const operation = bindNodeOperation(raw, user.userId, 'a');
  try {
    vi.resetModules();
    const otherTab = await import('./auth');
    const next = { ...user, userId: 'replacement-user' };
    if (mode === 'login') {
      get.mockResolvedValue({ data: { token: 'csrf' } }); post.mockResolvedValue({ data: next });
      await otherTab.login(next.email, 'password');
    } else {
      get.mockResolvedValue({ data: next }); await otherTab.fetchSession();
    }
    expect(previousAuth.getAuthState().user?.userId).toBe(user.userId); // No storage event delivered.
    expect(() => requireActiveRole('a')).toThrow('Keine aktive Mitgliedschaft');
    await expect(operation.transport.write(new Uint8Array([1]))).rejects.toThrow('abgebrochen');
    expect(raw.write).not.toHaveBeenCalled();
    get.mockResolvedValue({ data: next }); await otherTab.fetchSession();
    expect(await identity.credential(user.userId, 'a')).toBeNull();
    expect(() => requireActiveRole('a')).toThrow('Keine aktive Mitgliedschaft');
    expect(await db.credentials.where('userId').equals(user.userId).count()).toBe(0);
    post.mockResolvedValue({ data: user }); get.mockResolvedValue({ data: { token: 'csrf' } });
    await otherTab.login(user.email, 'password');
    expect(otherTab.activeOrganizationIds()).toEqual(['a', 'b']);
    expect(await identity.credential(user.userId, 'a')).toBeNull(); // Original key requires fresh issuance.
  } finally { await operation.close(); }
});

test('pending RFID records, boot/time evidence and sync metadata survive revocation and stay with their original account', async () => {
  const { CollectorDatabase } = await import('./offline-store');
  const { CollectorObservationStore } = await import('../collector/observation-store');
  const collector = new CollectorDatabase(`revocation-data-${crypto.randomUUID()}`);
  const store = new CollectorObservationStore(collector, user.userId);
  try {
    await store.putObservations([{ nodeId: 'node', incarnation: 'incarnation', sequence: 1n, chipId: 'secret-chip',
      clockStatus: 0, epochMs: null, monotonicMs: 25n, bootCounter: 1 }], 'a');
    await store.putTimeEvidence({ id: 'boot', organizationId: 'a', nodeId: 'node', incarnation: 'incarnation',
      bootCounter: '1', kind: 0, payload: 'unchanged-boot-bytes', uploaded: false });
    await store.putTimeEvidence({ id: 'time', organizationId: 'a', nodeId: 'node', incarnation: 'incarnation',
      bootCounter: '1', kind: 1, payload: 'unchanged-signed-bytes', uploaded: false });
    await store.putTimeEvidence({ id: 'phone', organizationId: 'a', nodeId: 'node', incarnation: 'incarnation',
      bootCounter: '1', kind: 2, payload: 'unchanged-phone-bytes', uploaded: false });
    await store.saveSession({ nodeId: 'node', incarnation: 'incarnation', watermark: '1', phase: 'acked', updatedAt: 25 });
    await collector.uploads.put({ id: 'raw', organizationId: 'a', appDeviceId: 'device', nodeId: 'node',
      protocolVersion: 1, payload: new Uint8Array([1, 2, 3]), createdAt: 25 });
    get.mockResolvedValue(disabled()); await auth.fetchSession();
    expect(await store.pendingUploads('a')).toEqual([]);
    expect(await store.pendingTimeEvidence('a')).toEqual([]);
    expect(await collector.observations.count()).toBe(1);
    expect(await collector.timeEvidence.count()).toBe(3);
    expect(await collector.syncSessions.get('node')).toMatchObject({ watermark: '1' });
    expect(Array.from((await collector.uploads.get('raw'))!.payload)).toEqual([1, 2, 3]);
    // A different account with membership in A cannot adopt the old outbox.
    get.mockResolvedValue({ data: { token: 'csrf' } });
    post.mockResolvedValue({ data: { ...user, userId: 'new-account' } });
    await auth.login(user.email, 'password');
    const other = new CollectorObservationStore(collector, 'new-account');
    expect(await other.pendingUploads('a')).toEqual([]);
    expect(await other.pendingTimeEvidence('a')).toEqual([]);
    expect(await other.pendingUploads('b')).toEqual([]);
    const record = { nodeId: 'node', incarnation: 'incarnation', sequence: '1' };
    await other.markUploading([record]); await other.markUploaded([record]);
    await other.markUploadResult(record, true, null);
    await other.confirmTimeEvidence('boot'); await other.confirmNodeEvidenceReceipt('time');
    expect((await collector.observations.get(['node', 'incarnation', '1']))?.uploadState).toBe('pending');
    expect((await collector.timeEvidence.get('boot'))?.uploaded).toBe(false);
    expect((await collector.timeEvidence.get('time'))?.nodeAcknowledged).toBeUndefined();
    await expect(other.putObservations([{ nodeId: 'node', incarnation: 'incarnation', sequence: 1n, chipId: 'secret-chip',
      clockStatus: 0, epochMs: null, monotonicMs: 25n, bootCounter: 1 }], 'a')).rejects.toThrow('another offline account');
    // Original user plus confirmed current authorization can recover the queue.
    post.mockResolvedValue({ data: user }); await auth.login(user.email, 'password');
    expect(await store.pendingUploads('a')).toHaveLength(1);
    expect((await store.pendingTimeEvidence('a')).map(r => r.payload)).toEqual(['unchanged-boot-bytes', 'unchanged-phone-bytes', 'unchanged-signed-bytes']);
    await store.confirmTimeEvidence('boot'); await store.confirmTimeEvidence('phone');
    expect((await store.uploadedTimeEvidence('a', 'node', 'incarnation')).map(r => r.id)).toEqual(['boot']);
  } finally { await collector.delete(); }
});

test('legacy database migration uses the original snapshot and quarantines records without proven ownership', async () => {
  const { default: Dexie } = await import('dexie');
  const { CollectorDatabase } = await import('./offline-store');
  const { CollectorObservationStore } = await import('../collector/observation-store');
  const name = `legacy-owner-${crypto.randomUUID()}`;
  const old = new Dexie(name);
  old.version(3).stores({ uploads: 'id, organizationId, [organizationId+nodeId]',
    observations: '[nodeId+incarnation+sequence], nodeId, [nodeId+uploadState], uploadState',
    syncSessions: 'nodeId', nodeMeta: 'nodeId', timeEvidence: 'id, organizationId, [organizationId+nodeId]' });
  await old.table('observations').bulkPut([
    { nodeId: 'node', incarnation: 'inc', sequence: '1', organizationId: 'a', uploadState: 'pending', chipId: 'original' },
    { nodeId: 'foreign', incarnation: 'inc', sequence: '1', organizationId: 'unknown', uploadState: 'pending', chipId: 'quarantined' },
  ]);
  await old.table('timeEvidence').put({ id: 'boot', organizationId: 'a', payload: 'original-evidence', uploaded: false });
  old.close();
  get.mockResolvedValue(disabled()); await auth.fetchSession(); // Snapshot removed before the database upgrade.
  const upgraded = new CollectorDatabase(name);
  try {
    expect((await upgraded.observations.get(['node', 'inc', '1']))?.ownerUserId).toBe(user.userId);
    expect((await upgraded.timeEvidence.get('boot'))?.ownerUserId).toBe(user.userId);
    expect((await upgraded.observations.get(['foreign', 'inc', '1']))?.ownerUserId).toBeUndefined();
    expect(await upgraded.observations.count()).toBe(2);
    expect(await new CollectorObservationStore(upgraded, user.userId).pendingUploads('a')).toEqual([]);
    get.mockResolvedValue({ data: user }); await auth.fetchSession();
    expect(await new CollectorObservationStore(upgraded, user.userId).pendingUploads('a')).toHaveLength(1);
  } finally { await upgraded.delete(); }
});
