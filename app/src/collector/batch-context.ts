// Feeding-site + bowl context for confirmed batch results (issue #76).
//
// Reuses the existing data model (FeedingSite, Node, NodeDeployment) and the
// existing organization-scoped read APIs (#49/#54). No new bowl model, no
// artificial 1:1 relationship, no invented observations, assignments,
// timestamps or activity counts.
//
// Rules:
// - Context is resolved only AFTER a confirmed local sync (durable persist +
//   correct ACK). Never before, never as a pre-selection.
// - Node identity comes from the authenticated nodeId, never from BLE display
//   names or MAC addresses.
// - Bowl names come from Node.displayName where available; otherwise an honest
//   "Ohne Namen" fallback with the technical node id as detail.
// - Nodes without an active deployment are reported honestly as unassigned.
// - Historical deployments are preserved: only the currently valid assignment
//   (validFrom <= now, no validUntil or validUntil in the future) is shown.
// - Cat activity reuses #54 (feeding-site cat-activity) with a small preview
//   (default 3). Offline, auth-restricted or missing data omits the list with
//   an honest "not available" note instead of fake data.

import { api } from '../api/client';
import type { components } from '../api/generated';

export type NodeView = components['schemas']['NodeView'];
export type DeploymentView = components['schemas']['DeploymentView'];
export type FeedingSiteView = components['schemas']['FeedingSiteView'];
export type FeedingSiteCatActivity = components['schemas']['FeedingSiteCatActivityView'];

export interface BatchNodeContext {
  nodeId: string;
  /** Trusted bowl label from the backend; null when unnamed. */
  displayName: string | null;
  siteId: string | null;
  siteName: string | null;
  /** Small #54 preview (max 3); null when unavailable (offline/error). */
  activity: FeedingSiteCatActivity[] | null;
  contextAvailable: boolean;
  contextNote: string;
}

export interface BatchContextOptions {
  signal?: AbortSignal;
  /** Preview size for #54 activity (default 3). */
  activityLimit?: number;
  now?: () => number;
}

function bowlName(node: NodeView | undefined): string | null {
  const name = node?.displayName?.trim();
  return name ? name : null;
}

function siteName(sites: FeedingSiteView[], id: string | null | undefined): string | null {
  if (!id) return null;
  const found = sites.find(s => s.id === id);
  const name = found?.name?.trim();
  return name ? name : null;
}

export function activeDeploymentFor(
  deployments: DeploymentView[],
  nodeId: string,
  nowMs: number,
): DeploymentView | null {
  // The backend rejects overlapping open deployments (409), but if history
  // ever contains several currently-valid rows, the latest validFrom wins
  // instead of depending on list order.
  let best: DeploymentView | null = null;
  let bestFrom = Number.NEGATIVE_INFINITY;
  for (const d of deployments) {
    if (d.nodeId !== nodeId) continue;
    const from = Date.parse(d.validFrom ?? '');
    if (!Number.isFinite(from) || from > nowMs) continue;
    if (d.validUntil) {
      const until = Date.parse(d.validUntil);
      if (!Number.isFinite(until) || until <= nowMs) continue;
    }
    if (from > bestFrom) {
      best = d;
      bestFrom = from;
    }
  }
  return best;
}

function isOffline(): boolean {
  return typeof navigator !== 'undefined' && !navigator.onLine;
}

type Loader = {
  get: (path: string, options: unknown) => Promise<{ data?: unknown; error?: unknown; response?: unknown }>;
};

async function loadList<T>(promise: Promise<{ data?: T; error?: unknown; response?: unknown }>): Promise<T | null> {
  try {
    const { data, error } = await promise;
    if (error || data == null) return null;
    return data as T;
  } catch {
    return null;
  }
}

// Resolves context for authenticated nodeIds. Never throws for offline or
// backend failures: affected nodes get contextAvailable=false with an honest
// note, while the confirmed local sync stays valid.
export async function resolveBatchContexts(
  nodeIds: string[],
  organizationId: string,
  options: BatchContextOptions = {},
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  loader: Loader = { get: (path: string, opts: unknown) => (api.GET as any)(path as never, opts as never) as never },
): Promise<Map<string, BatchNodeContext>> {
  const unique = [...new Set(nodeIds.filter(Boolean))];
  const out = new Map<string, BatchNodeContext>();
  const unavailable = (nodeId: string, note: string): BatchNodeContext => ({
    nodeId, displayName: null, siteId: null, siteName: null,
    activity: null, contextAvailable: false, contextNote: note,
  });
  if (!unique.length) return out;
  if (isOffline()) {
    for (const nodeId of unique) {
      out.set(nodeId, unavailable(nodeId,
        'Futterstellenkontext offline nicht verfügbar. Lokale Übernahme bleibt gültig.'));
    }
    return out;
  }
  // Bound every read: callers pass a cancellation signal (batch abort /
  // organization change) that alone never times out, so combine it with a
  // 10s timeout like the management read paths (see requestOptions).
  const timeout = AbortSignal.timeout(10_000);
  const signal = options.signal ? AbortSignal.any([options.signal, timeout]) : timeout;
  const base = { signal, cache: 'no-store' as const };
  const [nodes, deployments, sites] = await Promise.all([
    loadList<NodeView[]>((loader.get('/api/v1/nodes',
      { ...base, params: { query: { organizationId } } }) as unknown as Promise<{ data?: NodeView[]; error?: unknown }>)),
    loadList<DeploymentView[]>((loader.get('/api/v1/organizations/{organizationId}/deployments',
      { ...base, params: { path: { organizationId } } }) as unknown as Promise<{ data?: DeploymentView[]; error?: unknown }>)),
    loadList<FeedingSiteView[]>((loader.get('/api/v1/organizations/{organizationId}/feeding-sites',
      { ...base, params: { path: { organizationId } } }) as unknown as Promise<{ data?: FeedingSiteView[]; error?: unknown }>)),
  ]);
  if (!nodes || !deployments || !sites) {
    for (const nodeId of unique) {
      out.set(nodeId, unavailable(nodeId,
        'Futterstellenkontext derzeit nicht verfügbar (keine Serververbindung oder keine Berechtigung). Lokale Übernahme bleibt gültig.'));
    }
    return out;
  }
  const nowMs = options.now?.() ?? Date.now();
  const byNode = new Map(nodes.map(n => [String(n.nodeId), n]));
  const siteIds = new Set<string>();
  const assignments = new Map<string, DeploymentView | null>();
  for (const nodeId of unique) {
    const active = activeDeploymentFor(deployments, nodeId, nowMs);
    assignments.set(nodeId, active);
    if (active?.feedingSiteId) siteIds.add(active.feedingSiteId);
  }
  const activityLimit = options.activityLimit ?? 3;
  const activityBySite = new Map<string, FeedingSiteCatActivity[] | null>();
  await Promise.all([...siteIds].map(async siteId => {
    const rows = await loadList<FeedingSiteCatActivity[]>(
      (loader.get('/api/v1/organizations/{organizationId}/feeding-sites/{siteId}/cat-activity',
        { ...base, params: { path: { organizationId, siteId }, query: { limit: activityLimit, offset: 0 } } }) as unknown as Promise<{ data?: FeedingSiteCatActivity[]; error?: unknown }>));
    activityBySite.set(siteId, rows ? rows.slice(0, activityLimit) : null);
  }));
  for (const nodeId of unique) {
    const node = byNode.get(nodeId);
    if (!node) {
      out.set(nodeId, unavailable(nodeId,
        'Napf in dieser Organisation nicht verfügbar (fremd oder unbekannt). Keine Futterstellenannahme.'));
      continue;
    }
    const active = assignments.get(nodeId) ?? null;
    const feedingSiteId = active?.feedingSiteId ?? null;
    out.set(nodeId, {
      nodeId,
      displayName: bowlName(node),
      siteId: feedingSiteId,
      siteName: feedingSiteId ? (siteName(sites, feedingSiteId) ?? 'Futterstelle nicht verfügbar') : null,
      activity: feedingSiteId ? (activityBySite.get(feedingSiteId) ?? null) : null,
      contextAvailable: true,
      contextNote: feedingSiteId
        ? ''
        : 'Noch keiner Futterstelle zugeordnet.',
    });
  }
  return out;
}
