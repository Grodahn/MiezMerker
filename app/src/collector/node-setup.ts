// Field-PWA initial bowl setup after cryptographic claiming (issue #53).
//
// Claiming (provision-node.ts, #18) and business setup are separate phases.
// A successful claim stays successful even if name/deployment fails. A node
// with CLAIMED + displayName=null + no active deployment is a valid,
// resumable intermediate state -- never rolled back, never re-claimed.
//
// This module reuses the existing authoritative backends:
// - Node displayName PATCH from #50 (trim, max 100, Unicode, duplicates ok)
// - ADMIN-only Deployment create/list from #51 (overlap-safe, 409 on conflict)
// - FeedingSite list (read-only; no creation in the PWA)
// Tenant isolation stays server-side; the client only gates on ACTIVE ADMIN
// for UX and relies on backend 403/404 for authority.

import { api } from '../api/client';
import { fetchCsrfToken, getAuthState, markSessionExpired } from '../platform/auth';
import type { components } from '../api/generated';

export type SetupFailureKind =
  | 'forbidden-role'
  | 'unauthorized'
  | 'offline'
  | 'validation'
  | 'conflict-existing'
  | 'foreign'
  | 'backend';

export class SetupError extends Error {
  constructor(
    readonly kind: SetupFailureKind,
    message: string,
  ) {
    super(message);
    this.name = 'SetupError';
  }
}

export type NodeView = components['schemas']['NodeView'];
export type FeedingSiteView = components['schemas']['FeedingSiteView'];
export type DeploymentView = components['schemas']['DeploymentView'];

export interface NodeSetupState {
  nodeId: string;
  organizationId: string;
  claimed: boolean;
  displayName: string | null;
  activeDeployment: DeploymentView | null;
  deploymentCount: number;
}

function activeMembership(organizationId: string) {
  return getAuthState().user?.memberships.find(
    m => m.organizationId === organizationId && m.status === 'ACTIVE');
}

// Provisioning setup stays ACTIVE ADMIN-only in the PWA. The backend
// enforces ADMIN again for deployments (and 404 for foreign ids); the
// displayName PATCH itself remains ACTIVE-member capable per #50, so the
// wizard gate plus deployment gate together prevent MEMBER provisioning.
export function assertAdminMayProvision(organizationId: string): void {
  const membership = activeMembership(organizationId);
  if (!membership) {
    throw new SetupError('forbidden-role', 'Keine aktive Mitgliedschaft. Einrichtung nicht möglich.');
  }
  if (membership.role !== 'ADMIN') {
    throw new SetupError('forbidden-role', 'Nur ADMIN kann einen Napf einrichten. MEMBER hat keinen Zugriff.');
  }
}

function requireOnline(): void {
  if (typeof navigator !== 'undefined' && !navigator.onLine) {
    throw new SetupError('offline',
      'Backend nicht erreichbar (offline). Der Claim bleibt gültig; Name und Futterstellenzuordnung benötigen Internet.');
  }
}

async function csrfHeaders(): Promise<Record<string, string>> {
  const token = getAuthState().csrfToken ?? await fetchCsrfToken();
  return token ? { 'X-XSRF-TOKEN': token } : {};
}

function markExpired(): void {
  try { markSessionExpired(); } catch { /* Message stays authoritative. */ }
}

// Mirrors NodeDevice.normalizeDisplayName: trim Unicode whitespace at both
// ends; blank-only becomes null. Internal spacing is preserved.
export function normalizeBowlName(input: string | null | undefined): string | null {
  if (input == null) return null;
  const trimmed = input.trim();
  return trimmed === '' ? null : trimmed;
}

export const MAX_BOWL_NAME_LENGTH = 100;

export function validateBowlName(input: string | null | undefined): string {
  const normalized = normalizeBowlName(input);
  if (normalized == null) {
    throw new SetupError('validation', 'Bitte einen Napf-Namen eingeben (z. B. „Der Grüne“).');
  }
  if (normalized.length > MAX_BOWL_NAME_LENGTH) {
    throw new SetupError('validation',
      `Napf-Name darf höchstens ${MAX_BOWL_NAME_LENGTH} Zeichen haben.`);
  }
  return normalized;
}

function toSetupError(status: number | undefined, fallback: string, opts: { nodeId?: string } = {}): SetupError {
  if (status === 401) {
    markExpired();
    return new SetupError('unauthorized', 'Sitzung abgelaufen. Bitte erneut anmelden; der Claim bleibt gültig.');
  }
  if (status === 403) {
    return new SetupError('forbidden-role',
      'Einrichtung verweigert: ACTIVE ADMIN erforderlich oder fremde Organisation.');
  }
  if (status === 404) {
    return new SetupError('foreign',
      opts.nodeId
        ? `Node ${opts.nodeId} ist in dieser Organisation nicht verfügbar (fremd oder unbekannt).`
        : 'Eintrag in dieser Organisation nicht verfügbar (fremde ID oder unbekannt).');
  }
  if (status === 409) {
    return new SetupError('conflict-existing',
      'Es existiert bereits eine Zuordnung. Aktuellen Stand neu laden statt erneut anzulegen.');
  }
  return new SetupError('backend', fallback);
}

// Authoritative setup snapshot: backend node + deployments for this node.
// Works without BLE or the original claim tab (Scenario F); page reloads
// simply refetch (Scenario E). Never triggers cryptographic claiming.
export async function fetchNodeSetupState(
  organizationId: string,
  nodeId: string,
  options: { signal?: AbortSignal } = {},
): Promise<NodeSetupState> {
  assertAdminMayProvision(organizationId);
  requireOnline();
  const signal = options.signal ?? AbortSignal.timeout(10_000);
  // Destructure (like platform/node-identity.ts): openapi-fetch returns a
  // discriminated union where property access on the narrowed object can
  // collapse to never; destructured bindings stay accessible in both branches.
  const nodeRes = await api.GET('/api/v1/nodes/{nodeId}', {
    signal,
    params: { path: { nodeId } },
  });
  const { data: nodeData, error: nodeError, response: nodeResponse } = nodeRes;
  if (nodeError || !nodeData) {
    throw toSetupError((nodeResponse as Response | undefined)?.status,
      'Napf-Status konnte nicht geladen werden. Bitte erneut versuchen.', { nodeId });
  }
  const node = nodeData;
  if (node.organizationId && node.organizationId !== organizationId) {
    throw new SetupError('foreign', `Node ${nodeId} gehört einer anderen Organisation.`);
  }
  const depRes = await api.GET('/api/v1/organizations/{organizationId}/deployments', {
    signal,
    params: { path: { organizationId }, query: { nodeId } },
  });
  const { data: depData, error: depError, response: depResponse } = depRes;
  if (depError || !depData) {
    throw toSetupError((depResponse as Response | undefined)?.status,
      'Zuordnungen konnten nicht geladen werden. Bitte erneut versuchen.', { nodeId });
  }
  const all = depData ?? [];
  const active = all.find(d => !d.validUntil) ?? null;
  return {
    nodeId,
    organizationId,
    claimed: node.state === 'CLAIMED',
    displayName: node.displayName ?? null,
    activeDeployment: active,
    deploymentCount: all.length,
  };
}

export async function listFeedingSiteOptions(
  organizationId: string,
  options: { signal?: AbortSignal } = {},
): Promise<FeedingSiteView[]> {
  assertAdminMayProvision(organizationId);
  requireOnline();
  const siteRes = await api.GET('/api/v1/organizations/{organizationId}/feeding-sites', {
    signal: options.signal ?? AbortSignal.timeout(10_000),
    params: { path: { organizationId } },
  });
  const { data: siteData, error: siteError, response: siteResponse } = siteRes;
  if (siteError || !siteData) {
    throw toSetupError((siteResponse as Response | undefined)?.status,
      'Futterstellen konnten nicht geladen werden. Bitte erneut versuchen.');
  }
  return siteData ?? [];
}

// Saves the human-readable bowl label via the existing #50 PATCH.
// Duplicates allowed, Unicode allowed, never part of technical identity.
// Preserves a previously saved name on retry (Scenario B): callers prefill
// from fetchNodeSetupState and only overwrite on explicit user input.
export async function saveBowlName(
  organizationId: string,
  nodeId: string,
  displayName: string,
): Promise<string> {
  assertAdminMayProvision(organizationId);
  requireOnline();
  const normalized = validateBowlName(displayName);
  const headers = await csrfHeaders();
  const patchRes = await api.PATCH('/api/v1/nodes/{nodeId}', {
    signal: AbortSignal.timeout(10_000),
    params: { path: { nodeId } },
    headers,
    body: { displayName: normalized },
  });
  const { data: patchData, error: patchError, response: patchResponse } = patchRes;
  if (patchError || !patchData) {
    throw toSetupError((patchResponse as Response | undefined)?.status,
      'Napf-Name konnte nicht gespeichert werden. Claim bleibt gültig; bitte erneut versuchen.',
      { nodeId });
  }
  return patchData.displayName ?? normalized;
}

export interface InitialDeploymentOutcome {
  deployment: DeploymentView;
  created: boolean;
  alreadyAssigned: boolean;
}

// Idempotent initial assignment. Never overwrites or moves an existing open
// deployment (later moves belong to Admin #52). On 409 / lost-response
// retries the persisted assignment is returned instead of duplicating.
export async function ensureInitialDeployment(
  organizationId: string,
  nodeId: string,
  feedingSiteId: string,
  options: { validFrom?: string } = {},
): Promise<InitialDeploymentOutcome> {
  assertAdminMayProvision(organizationId);
  requireOnline();
  if (!feedingSiteId) {
    throw new SetupError('validation', 'Bitte eine vorhandene Futterstelle auswählen.');
  }
  const headers = await csrfHeaders();
  const listOnce = async (): Promise<DeploymentView[]> => {
    const listRes = await api.GET('/api/v1/organizations/{organizationId}/deployments', {
      signal: AbortSignal.timeout(10_000),
      params: { path: { organizationId }, query: { nodeId } },
    });
    const { data: listData, error: listError, response: listResponse } = listRes;
    if (listError || !listData) {
      throw toSetupError((listResponse as Response | undefined)?.status,
        'Zuordnungen konnten nicht geprüft werden. Bitte erneut versuchen.', { nodeId });
    }
    return listData ?? [];
  };
  // Frontend pre-check (not concurrency-safe alone): avoids the POST when an
  // open assignment is already visible.
  const before = await listOnce();
  const openBefore = before.find(d => !d.validUntil) ?? null;
  if (openBefore) {
    return { deployment: openBefore, created: false, alreadyAssigned: true };
  }
  const validFrom = options.validFrom ?? new Date().toISOString();
  const createRes = await api.POST('/api/v1/organizations/{organizationId}/deployments', {
    signal: AbortSignal.timeout(10_000),
    params: { path: { organizationId } },
    headers,
    body: { nodeId, feedingSiteId, validFrom },
  });
  const { data: createData, error: createError, response: createResponse } = createRes;
  if (!createError && createData) {
    return { deployment: createData, created: true, alreadyAssigned: false };
  }
  const status = (createResponse as Response | undefined)?.status;
  if (status === 409) {
    // Lost response (Scenario C) or concurrent ADMIN: the backend invariant
    // rejected the overlap. Surface the persisted state, never blind-retry.
    const after = await listOnce();
    const openAfter = after.find(d => !d.validUntil) ?? null;
    if (openAfter) {
      return { deployment: openAfter, created: false, alreadyAssigned: true };
    }
    throw new SetupError('conflict-existing',
      'Zuordnung steht im Konflikt mit der Historie. Aktuellen Stand prüfen und ggf. im Admin-Backend umziehen.');
  }
  throw toSetupError(status,
    'Erstzuordnung konnte nicht gespeichert werden. Name bleibt gespeichert; bitte erneut versuchen.',
    { nodeId });
}

export function isSetupComplete(state: Pick<NodeSetupState, 'displayName' | 'activeDeployment'>): boolean {
  return Boolean(state.displayName) && state.activeDeployment !== null;
}
