// Durable cooperative revocation fence, shared by auth and credential storage.
// Only a confirmed backend session/login may publish a grant. Storage events
// reduce other tabs' rights; they never authenticate another tab.
export const authorizationStatusKey = 'miezmerker-authorization-status:';
export const authenticationEpochKey = 'miezmerker-authentication-epoch';
export interface AuthorizationStatus {
  revision: string;
  disabled: boolean;
  organizations: string[];
  credentialGeneration: number;
  organizationGenerations: Record<string, number>;
}
const volatile = new Map<string, AuthorizationStatus>();
export function authorizationStatus(userId: string): AuthorizationStatus | null {
  if (volatile.has(userId)) return volatile.get(userId)!;
  try {
    return JSON.parse(localStorage.getItem(authorizationStatusKey + userId) ?? 'null');
  } catch { return volatile.get(userId) ?? null; }
}
export function authorizationPermits(userId: string, organizationId: string): boolean {
  const status = authorizationStatus(userId);
  return !status || (!status.disabled && status.organizations.includes(organizationId));
}
export function publishAuthorization(userId: string, disabled: boolean, organizations: string[], previousOrganizations: string[] = []): AuthorizationStatus {
  const previous = authorizationStatus(userId);
  const organizationGenerations = { ...previous?.organizationGenerations };
  for (const id of new Set([...(previous?.organizations ?? []), ...previousOrganizations])) {
    if (!organizations.includes(id)) organizationGenerations[id] = (organizationGenerations[id] ?? 0) + 1;
  }
  const status = { revision: crypto.randomUUID(), disabled, organizations, organizationGenerations,
    credentialGeneration: (previous?.credentialGeneration ?? 0) + (disabled ? 1 : 0) };
  try {
    localStorage.setItem(authorizationStatusKey + userId, JSON.stringify(status));
    volatile.delete(userId);
  } catch {
    volatile.set(userId, status);
    // IDB invalidation still removes credentials when localStorage is unavailable.
  }
  return status;
}
export function authenticationEpoch(): string | null {
  try { return localStorage.getItem(authenticationEpochKey); } catch { return null; }
}
export function advanceAuthenticationEpoch(clearUserId?: string): string {
  const epoch = JSON.stringify({ revision: crypto.randomUUID(), clearUserId });
  try { localStorage.setItem(authenticationEpochKey, epoch); } catch { /* Per-tab generation remains enforced. */ }
  return epoch;
}

// LocalStorage has no compare-and-swap: check + publish must be serialized
// across documents. Web Locks is available in the supported secure PWA;
// an IndexedDB write transaction provides the same fence on older browsers.
export async function withAuthorizationLock<T>(operation: () => T): Promise<T> {
  if (typeof navigator !== 'undefined' && navigator.locks) {
    return navigator.locks.request('miezmerker-authorization', operation);
  }
  if (typeof indexedDB === 'undefined') return operation(); // No durable offline identity in this environment.
  const { OfflineIdentityDatabase } = await import('./offline-identity');
  const { default: Dexie } = await import('dexie');
  const database = new OfflineIdentityDatabase();
  try {
    return await database.transaction('rw', database.invalidations, async () => {
      await database.invalidations.get('__authorization-lock__');
      return Dexie.ignoreTransaction(operation);
    });
  } finally { database.close(); }
}
