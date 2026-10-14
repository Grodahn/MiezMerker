import Dexie, { type Table } from 'dexie';
import { api } from '../api/client';
import { fetchCsrfToken } from './auth';
import { WebCryptoDeviceKeys, fromBase64Url } from './device-keys';
import { authorizationPermits, authorizationStatus } from './authorization-status';

interface DeviceIdentity { userId: string; keys: CryptoKeyPair; deviceId?: string }
interface CachedCredential {
  userId: string; organizationId: string; deviceId: string;
  credential: string; expiresAt: number;
  credentialGeneration?: number;
  organizationGeneration?: number;
}

export class OfflineIdentityDatabase extends Dexie {
  identities!: Table<DeviceIdentity, string>;
  credentials!: Table<CachedCredential, [string, string]>;
  invalidations!: Table<{ userId: string; generation: number }, string>;
  organizationInvalidations!: Table<{ userId: string; organizationId: string; generation: number }, [string, string]>;
  constructor(name = 'miezmerker-offline-identity') {
    super(name);
    this.version(1).stores({ identities: 'userId', credentials: '[userId+organizationId], userId' });
    this.version(2).stores({ invalidations: 'userId' });
    this.version(3).stores({ organizationInvalidations: '[userId+organizationId]' });
  }
}

// The caller supplies the previously authenticated user, never an arbitrary credential owner.
// Account-specific identities avoid re-registering another user's installation key.
export class OfflineIdentity {
  constructor(private readonly database: OfflineIdentityDatabase) {}

  async keys(userId: string): Promise<WebCryptoDeviceKeys> {
    const existing = await this.database.identities.get(userId);
    if (existing) return WebCryptoDeviceKeys.restore(existing.keys);
    const generated = await WebCryptoDeviceKeys.generate();
    // Generate outside the transaction (Web Crypto can let an IDB transaction expire).
    // Recheck inside it so concurrent tabs share the same durable identity.
    const pair = await this.database.transaction('rw', this.database.identities, async () => {
      const saved = await this.database.identities.get(userId);
      if (saved) return saved.keys;
      await this.database.identities.add({ userId, keys: generated.keyHandles() });
      return generated.keyHandles();
    });
    return WebCryptoDeviceKeys.restore(pair);
  }

  async renew(userId: string, organizationId: string, expectedGeneration?: number): Promise<string> {
    if (!authorizationPermits(userId, organizationId)) throw new Error('Offline access revoked');
    const startingStatus = authorizationStatus(userId);
    const orgGeneration = (await this.database.organizationInvalidations.get([userId, organizationId]))?.generation ?? 0;
    const currentGeneration = (await this.database.invalidations.get(userId))?.generation ?? 0;
    const generation = expectedGeneration ?? currentGeneration;
    if (generation !== currentGeneration) throw new Error('Offline credential renewal superseded by logout');
    const keys = await this.keys(userId);
    // Fresh CSRF token also works immediately after Spring rotates it on login.
    const token = await fetchCsrfToken();
    const headers = { 'X-XSRF-TOKEN': token };
    const { data: device, error: registrationError } = await api.POST('/api/v1/devices', {
      signal: AbortSignal.timeout(10_000),
      body: { publicKeyX: keys.coordinates().x, publicKeyY: keys.coordinates().y }, headers,
    });
    if (registrationError || !device?.id) throw new Error('AppDevice registration denied');
    await this.database.identities.update(userId, { deviceId: device.id });
    const startedAt = Date.now();
    const { data, error } = await api.POST('/api/v1/devices/{deviceId}/credentials', {
      signal: AbortSignal.timeout(10_000),
      params: { path: { deviceId: device.id } }, body: { organizationId }, headers,
    });
    if (error || !data?.credential || !data.expiresInSeconds
        || data.organizationId !== organizationId || data.deviceId !== device.id) {
      throw new Error('Offline credential issuance denied');
    }
    // Bind the response to the authenticated account captured before issuance, even if
    // another tab changes the session while the request is in flight.
    const parts = data.credential.split('.');
    if (parts.length !== 3) throw new Error('Malformed offline credential');
    const claims = JSON.parse(new TextDecoder().decode(fromBase64Url(parts[1])));
    if (claims.sub !== userId || claims.org !== organizationId || claims.dev !== device.id
        || claims.dpk_x !== keys.coordinates().x || claims.dpk_y !== keys.coordinates().y
        || !Number.isSafeInteger(claims.exp)) throw new Error('Offline credential binding mismatch');
    const status = authorizationStatus(userId);
    const cached: CachedCredential = { userId, organizationId, deviceId: device.id,
      credentialGeneration: status?.credentialGeneration ?? 0,
      organizationGeneration: status?.organizationGenerations[organizationId] ?? 0,
      credential: data.credential, expiresAt: Math.min(claims.exp * 1000,
        startedAt + data.expiresInSeconds * 1000 - 1000) };
    await this.database.transaction('rw', this.database.invalidations, this.database.organizationInvalidations, this.database.credentials, async () => {
      if (((await this.database.invalidations.get(userId))?.generation ?? 0) !== generation) {
        throw new Error('Offline credential renewal superseded by logout');
      }
      if (((await this.database.organizationInvalidations.get([userId, organizationId]))?.generation ?? 0) !== orgGeneration
          || !authorizationPermits(userId, organizationId)
          || (authorizationStatus(userId)?.credentialGeneration ?? 0) !== (startingStatus?.credentialGeneration ?? 0)
          || (authorizationStatus(userId)?.organizationGenerations[organizationId] ?? 0) !== (startingStatus?.organizationGenerations[organizationId] ?? 0)) {
        throw new Error('Offline credential renewal superseded by authorization status');
      }
      await this.database.credentials.put(cached);
    });
    return data.credential;
  }

  async credential(userId: string, organizationId: string): Promise<string | null> {
    if (!authorizationPermits(userId, organizationId)) return null;
    const generation = (await this.database.invalidations.get(userId))?.generation ?? 0;
    const cached = await this.database.credentials.get([userId, organizationId]);
    const identity = await this.database.identities.get(userId);
    const status = authorizationStatus(userId);
    if (!cached || !identity || cached.deviceId !== identity.deviceId
        || (cached.credentialGeneration ?? 0) !== (status?.credentialGeneration ?? 0)
        || (cached.organizationGeneration ?? 0) !== (status?.organizationGenerations[organizationId] ?? 0)
        || Date.now() >= cached.expiresAt || !authorizationPermits(userId, organizationId)) return null;
    if (((await this.database.invalidations.get(userId))?.generation ?? 0) !== generation) return null;
    // No await may follow this fence: another tab can revoke while the final
    // IndexedDB read is pending, before its storage event updates this tab.
    const latest = authorizationStatus(userId);
    if (Date.now() >= cached.expiresAt || !authorizationPermits(userId, organizationId)
        || (cached.credentialGeneration ?? 0) !== (latest?.credentialGeneration ?? 0)
        || (cached.organizationGeneration ?? 0) !== (latest?.organizationGenerations[organizationId] ?? 0)) return null;
    return cached.credential;
  }

  // Call on an online authenticated session and on connectivity restoration.
  async renewActive(userId: string, organizationIds: string[], force = true): Promise<void> {
    const failures: unknown[] = [];
    const generation = (await this.database.invalidations.get(userId))?.generation ?? 0;
    for (const organizationId of organizationIds) {
      try {
        const cached = await this.database.credentials.get([userId, organizationId]);
        if (force || !cached || cached.expiresAt <= Date.now() + 300000) await this.renew(userId, organizationId, generation);
      } catch (error) { failures.push(error); }
    }
    if (failures.length) throw new AggregateError(failures, 'Some offline credentials could not be renewed');
  }

  async forgetCredentials(userId: string, expectedRevision?: string): Promise<void> {
    await this.database.transaction('rw', this.database.invalidations, this.database.credentials, async () => {
      const generation = (await this.database.invalidations.get(userId))?.generation ?? 0;
      if (expectedRevision && authorizationStatus(userId)?.revision !== expectedRevision) return;
      await this.database.invalidations.put({ userId, generation: generation + 1 });
      await this.database.credentials.where('userId').equals(userId).delete();
    });
  }

  async forgetOrganization(userId: string, organizationId: string, expectedRevision?: string): Promise<void> {
    await this.database.transaction('rw', this.database.organizationInvalidations, this.database.credentials, async () => {
      const generation = (await this.database.organizationInvalidations.get([userId, organizationId]))?.generation ?? 0;
      if (expectedRevision && authorizationStatus(userId)?.revision !== expectedRevision) return;
      await this.database.organizationInvalidations.put({ userId, organizationId, generation: generation + 1 });
      await this.database.credentials.delete([userId, organizationId]);
    });
  }

  // Include cached organizations absent from the restored snapshot. Removal is
  // serialized with issuance; credentials for still-active organizations survive.
  async reconcileOrganizations(userId: string, organizationIds: string[], revision = authorizationStatus(userId)?.revision): Promise<void> {
    const cached = await this.database.credentials.where('userId').equals(userId).toArray();
    for (const organizationId of new Set(cached.map(row => row.organizationId))) {
      if (!organizationIds.includes(organizationId)) await this.forgetOrganization(userId, organizationId, revision);
    }
  }
}
