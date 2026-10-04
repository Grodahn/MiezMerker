// Authorization/session handshake orchestration (issue #8).
//
// Uses the existing AppDevice/offline credential implementation from #17:
// for each protected session a valid organization-bound offline credential is
// required and the exact challenge/proof flow from #6 runs inside SyncEngine.
// Node authenticity is validated per #18/#6 via the backend-pinned device key.
// The protocol is never bypassed for tests or convenience.
//
// If the credential is expired and Internet is available, it is renewed. If no
// valid credential is available offline, sync cannot proceed and the UI
// explains it clearly.

import { getAuthState } from '../platform/auth';
import type { OfflineIdentity } from '../platform/offline-identity';

export type CredentialFailureKind =
  | 'no-session'
  | 'no-membership'
  | 'missing-credential'
  | 'expired-offline';

export class CredentialError extends Error {
  constructor(
    readonly kind: CredentialFailureKind,
    message: string,
  ) {
    super(message);
    this.name = 'CredentialError';
  }
}

export interface CredentialResolution {
  credential: string;
  userId: string;
  organizationId: string;
  renewed: boolean;
}

function activeRole(organizationId: string): string | null {
  const user = getAuthState().user;
  const membership = user?.memberships.find(m => m.organizationId === organizationId && m.status === 'ACTIVE');
  return (membership?.role as string | undefined) ?? null;
}

export function requireActiveRole(organizationId: string): string {
  const role = activeRole(organizationId);
  if (!role) {
    const loggedIn = Boolean(getAuthState().user);
    throw new CredentialError(loggedIn ? 'no-membership' : 'no-session',
      loggedIn
        ? 'Keine aktive Mitgliedschaft für diese Organisation. Bitte Organisation wählen oder erneut anmelden.'
        : 'Bitte zuerst anmelden. Ohne Anmeldung und gültiges Offline-Credential kann kein Node-Sync starten.');
  }
  return role;
}

// Resolves a valid organization-bound offline credential. Renewal happens only
// when online; offline expiry is a clear terminal error for the UI.
export async function resolveCredential(
  identity: OfflineIdentity,
  organizationId: string,
  options: { allowRenew?: boolean } = {},
): Promise<CredentialResolution> {
  const user = getAuthState().user;
  if (!user) {
    throw new CredentialError('no-session',
      'Bitte zuerst anmelden. Ohne Anmeldung und gültiges Offline-Credential kann kein Node-Sync starten.');
  }
  requireActiveRole(organizationId);
  const cached = await identity.credential(user.userId, organizationId);
  if (cached) return { credential: cached, userId: user.userId, organizationId, renewed: false };

  const online = typeof navigator === 'undefined' ? true : navigator.onLine;
  if ((options.allowRenew ?? true) && online) {
    try {
      const renewed = await identity.renew(user.userId, organizationId);
      return { credential: renewed, userId: user.userId, organizationId, renewed: true };
    } catch (error) {
      throw new CredentialError('missing-credential',
        `Offline-Credential konnte nicht erneuert werden (${error instanceof Error ? error.message : 'Backend nicht erreichbar'}). ` +
        'Ohne gültiges Credential kann der Node-Sync nicht starten.');
    }
  }
  throw new CredentialError('expired-offline',
    'Kein gültiges Offline-Credential vorhanden (abgelaufen oder noch nie geladen). ' +
    'Node-Sync kann nicht starten — bitte einmal mit Internet anmelden und Credential erneuern, dann erneut versuchen.');
}


