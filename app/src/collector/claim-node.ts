// Node claiming orchestration (issue #8, provisioning #18).
//
// For an UNCLAIMED node only ACTIVE ADMIN may claim it, the node must be in
// physical claim mode (#18/#6), the existing backend/provisioning API is used,
// and interrupted/retried claiming stays safe (idempotent same-owner retry).
// MEMBER never receives a hidden escalation path: the client gates on role
// and the backend enforces ACTIVE ADMIN again server-side.

import { api } from '../api/client';
import { getAuthState } from '../platform/auth';

export type ClaimFailureKind =
  | 'forbidden-role'
  | 'claim-mode'
  | 'conflict-foreign'
  | 'backend'
  | 'offline';

export class ClaimError extends Error {
  constructor(
    readonly kind: ClaimFailureKind,
    message: string,
  ) {
    super(message);
    this.name = 'ClaimError';
  }
}

export interface ClaimInput {
  nodeId: string;
  publicKeyX: string;
  publicKeyY: string;
  firmwareVersion?: string;
  claimSignature: string;
  timestampMillis: number;
}

export interface ClaimOutcome {
  nodeId: string;
  organizationId: string;
  receipt: string | undefined;
  retried: boolean;
}

function activeMembership(organizationId: string) {
  return getAuthState().user?.memberships.find(
    m => m.organizationId === organizationId && m.status === 'ACTIVE');
}

// MEMBER must not receive a hidden escalation path.
export function assertAdminMayClaim(organizationId: string): void {
  const membership = activeMembership(organizationId);
  if (!membership) {
    throw new ClaimError('forbidden-role', 'Keine aktive Mitgliedschaft. Claiming nicht möglich.');
  }
  if (membership.role !== 'ADMIN') {
    throw new ClaimError('forbidden-role', 'Nur ADMIN kann einen UNCLAIMED Node claimen. MEMBER hat keinen Zugriff.');
  }
}

function csrfHeaders(): Record<string, string> {
  const token = getAuthState().csrfToken;
  return token ? { 'X-XSRF-TOKEN': token } : {};
}

// Calls the existing backend provisioning API. Safe to retry: the backend
// returns a fresh receipt for same-owner/same-key redelivery and 409 for a
// node already owned by another organization (factory reset required).
export async function claimNode(
  organizationId: string,
  input: ClaimInput,
  options: { claimModeConfirmed: boolean } = { claimModeConfirmed: false },
): Promise<ClaimOutcome> {
  assertAdminMayClaim(organizationId);
  if (!options.claimModeConfirmed) {
    throw new ClaimError('claim-mode',
      'Physischer Claim-Modus am Node erforderlich (Taste/Power-On-Geste per #4). Bitte am Gerät bestätigen.');
  }
  if (typeof navigator !== 'undefined' && !navigator.onLine) {
    throw new ClaimError('offline', 'Claiming benötigt Internet (Backend-Provisionierung). Bitte online erneut versuchen.');
  }
  const { data, error, response } = await api.POST('/api/v1/nodes/claim', {
    body: {
      nodeId: input.nodeId,
      publicKeyX: input.publicKeyX,
      publicKeyY: input.publicKeyY,
      firmwareVersion: input.firmwareVersion,
      organizationId,
      timestampMillis: input.timestampMillis,
      claimSignature: input.claimSignature,
    },
    headers: csrfHeaders(),
  });
  if (error || !data) {
    const status = (response as Response | undefined)?.status;
    if (status === 403) {
      throw new ClaimError('forbidden-role',
        'Claiming verweigert: ACTIVE ADMIN erforderlich oder Claim-Modus-Nachweis ungültig.');
    }
    if (status === 409) {
      throw new ClaimError('conflict-foreign',
        'Node gehört bereits einer anderen Organisation. Übernahme nur per Factory Reset mit neuer Node-Identität.');
    }
    throw new ClaimError('backend', 'Claiming fehlgeschlagen. Unverändert erneut versuchen (idempotent).');
  }
  return {
    nodeId: data.nodeId ?? input.nodeId,
    organizationId: data.organizationId ?? organizationId,
    receipt: data.receipt,
    retried: false,
  };
}
