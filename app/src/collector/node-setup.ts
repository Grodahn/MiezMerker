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
  | 'no-sites'
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
  const get = api.GET as (...args: never[]) => Promise<{ data?: unknown; error?: unknown; response?: Response }>;
  const nodeReq = await get('/api/v1/nodes/{nodeId}' as never, {
    params: { path: { nodeId } },
    signal,
  } as never);
  if (nodeReq.error || !nodeReq.data) {
    throw toSetupError(nodeReq.response?.status,
      'Napf-Status konnte nicht geladen werden. Bitte erneut versuchen.', { nodeId });
  }
  const node = nodeReq.data as NodeView;
  if (node.organizationId && node.organizationId !== organizationId) {
    throw new SetupError('foreign', `Node ${nodeId} gehört einer anderen Organisation.`);
  }
  const depReq = await get('/api/v1/organizations/{organizationId}/deployments' as never, {
    params: { path: { organizationId }, query: { nodeId } },
    signal,
  } as never);
  if (depReq.error || !depReq.data) {
    throw toSetupError(depReq.response?.status,
      'Zuordnungen konnten nicht geladen werden. Bitte erneut versuchen.', { nodeId });
  }
  const all = (depReq.data ?? []) as DeploymentView[];
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
  const get = api.GET as (...args: never[]) => Promise<{ data?: unknown; error?: unknown; response?: Response }>;
  const res = await get('/api/v1/organizations/{organizationId}/feeding-sites' as never, {
    params: { path: { organizationId } },
    signal: options.signal ?? AbortSignal.timeout(10_000),
  } as never);
  if (res.error || !res.data) {
    throw toSetupError(res.response?.status,
      'Futterstellen konnten nicht geladen werden. Bitte erneut versuchen.');
  }
  return (res.data ?? []) as FeedingSiteView[];
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
  const patch = api.PATCH as (...args: never[]) => Promise<{ data?: unknown; error?: unknown; response?: Response }>;
  const res = await patch('/api/v1/nodes/{nodeId}' as never, {
    signal: AbortSignal.timeout(10_000),
    params: { path: { nodeId } },
    headers,
    body: { displayName: normalized },
  } as never);
  if (res.error || !res.data) {
    throw toSetupError(res.response?.status,
      'Napf-Name konnte nicht gespeichert werden. Claim bleibt gültig; bitte erneut versuchen.',
      { nodeId });
  }
  return (res.data as NodeView).displayName ?? normalized;
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
  const get = api.GET as (...args: never[]) => Promise<{ data?: unknown; error?: unknown; response?: Response }>;
  const post = api.POST as (...args: never[]) => Promise<{ data?: unknown; error?: unknown; response?: Response }>;
  const listOnce = async (): Promise<DeploymentView[]> => {
    const res = await get('/api/v1/organizations/{organizationId}/deployments' as never, {
      params: { path: { organizationId }, query: { nodeId } },
      signal: AbortSignal.timeout(10_000),
    } as never);
    if (res.error || !res.data) {
      throw toSetupError(res.response?.status,
        'Zuordnungen konnten nicht geprüft werden. Bitte erneut versuchen.', { nodeId });
    }
    return (res.data ?? []) as DeploymentView[];
  };
  // Frontend pre-check (not concurrency-safe alone): avoids the POST when an
  // open assignment is already visible.
  const before = await listOnce();
  const openBefore = before.find(d => !d.validUntil) ?? null;
  if (openBefore) {
    return { deployment: openBefore, created: false, alreadyAssigned: true };
  }
  const validFrom = options.validFrom ?? new Date().toISOString();
  const created = await post('/api/v1/organizations/{organizationId}/deployments' as never, {
    signal: AbortSignal.timeout(10_000),
    params: { path: { organizationId } },
    headers,
    body: { nodeId, feedingSiteId, validFrom },
  } as never);
  if (!created.error && created.data) {
    return { deployment: created.data as DeploymentView, created: true, alreadyAssigned: false };
  }
  const status = created.response?.status;
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
