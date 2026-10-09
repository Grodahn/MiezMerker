// Sequential multi-node batch synchronization (issue #76).
//
// One user-initiated sync operation synchronizes all previously
// browser-authorized, currently reachable, and organization-authorized
// NapfNodes sequentially. Multiple bowls at one feeding site are normal;
// authorized nodes from different feeding sites are handled in the same batch.
//
// Correctness boundaries (never changed here):
// - Signed offline credential + proof-of-possession per node (#17) via the
//   existing single-node collector (collector-sync.ts runFieldSync).
// - BLE transfer protocol (#6) is never duplicated; runFieldSync stays the
//   only transfer implementation.
// - Durable Dexie persistence BEFORE ACK + contiguous high-watermark semantics
//   (#8) are owned by SyncEngine/CollectorObservationStore.
// - Backend upload stays separate from local BLE sync; (node_id, sequence)
//   ingest idempotency (#9) is reused by backend-upload.ts.
//
// Discovery boundaries:
// - This module never calls navigator.bluetooth.requestDevice(). New devices
//   require an explicit user gesture ("Weiteren Napf freigeben") owned by the
//   UI layer, preserving Web Bluetooth user-activation requirements.
// - Only devices the browser already granted permission for (via
//   getDevices(), where supported) are passed in. Never silently discover or
//   connect to devices lacking browser permission.
// - BLE display names / MAC addresses are untrusted browser labels only.
//   Actual node identity + organization authorization are verified through the
//   existing security contracts inside runFieldSync (public probe, credential,
//   backend-pinned node key, node proof).

import type { NodeTransport } from '../platform/node-transport';
import {
  initialCollectorView, runFieldSync, type CollectorViewState,
} from './collector-sync';

export const BATCH_PER_NODE_TIMEOUT_MS = 120_000;

export type BatchNodeKind =
  | 'success'
  | 'unclaimed'
  | 'foreign'
  | 'unauthorized'
  | 'unreachable'
  | 'failed'
  | 'cancelled'
  | 'skipped';

export interface BatchNodeInput {
  /** Browser permission handle (never a trusted node identity). */
  device: unknown;
  /** Stable browser-side id for dedupe/progress (e.g. BluetoothDevice.id). */
  browserId: string;
  /** Untrusted browser label (device.name); never shown as bowl name. */
  browserLabel: string;
}

export interface BatchNodeResult {
  browserId: string;
  browserLabel: string;
  kind: BatchNodeKind;
  /** Authenticated node id, only when the node proved its identity. */
  nodeId: string | null;
  ownerNodeId: string | null;
  recordsReceived: number;
  watermark: string | null;
  message: string;
  foreignOrganizationName: string | null;
  unclaimed: boolean;
  durationMs: number;
}

export interface BatchSyncSummary {
  total: number;
  succeeded: number;
  failed: number;
  cancelled: boolean;
}

export interface BatchSingleNodeRunner {
  (transport: NodeTransport, signal: AbortSignal | undefined,
    onUpdate: (view: CollectorViewState) => void): Promise<CollectorViewState>;
}

export interface BatchSyncCallbacks {
  onNodeStart?: (index: number, total: number, input: BatchNodeInput) => void;
  onNodeUpdate?: (index: number, total: number, input: BatchNodeInput, view: CollectorViewState) => void;
  onNodeDone?: (index: number, total: number, result: BatchNodeResult) => void;
  signal?: AbortSignal;
  /** Bounded per-node operation timeout (default 120s). */
  perNodeTimeoutMs?: number;
  now?: () => number;
  createTransport?: (device: unknown) => NodeTransport | Promise<NodeTransport>;
  runSingleNode?: BatchSingleNodeRunner;
  trustedNowS?: () => number;
}

export function dedupeBatchInputs(inputs: BatchNodeInput[]): BatchNodeInput[] {
  const seen = new Set<string>();
  const out: BatchNodeInput[] = [];
  for (const input of inputs) {
    if (seen.has(input.browserId)) continue;
    seen.add(input.browserId);
    out.push(input);
  }
  return out;
}

export function summarizeBatch(results: BatchNodeResult[]): BatchSyncSummary {
  const succeeded = results.filter(r => r.kind === 'success').length;
  const failed = results.filter(r => r.kind !== 'success' && r.kind !== 'cancelled' && r.kind !== 'skipped').length;
  const cancelled = results.some(r => r.kind === 'cancelled');
  return { total: results.length, succeeded, failed, cancelled };
}

export function batchSummaryMessage(results: BatchNodeResult[]): string {
  const { total, succeeded } = summarizeBatch(results);
  if (total === 0) return 'Keine freigegebenen Näpfe gefunden.';
  if (succeeded === total) {
    return total === 1 ? '1 von 1 freigegebenem Napf ausgelesen.' : `${succeeded} von ${total} freigegebenen Näpfen ausgelesen.`;
  }
  return `${succeeded} von ${total} freigegebenen Näpfen ausgelesen.`;
}

function isCancelledError(error: unknown): boolean {
  if (!error || typeof error !== 'object') return false;
  const name = (error as { name?: unknown }).name;
  if (name === 'NodeOperationCancelledError') return true;
  if (name === 'AbortError') return true;
  const message = error instanceof Error ? error.message : String(error);
  return /abgebrochen.*(Benutzer|Organisation)|Node-Vorgang abgebrochen/i.test(message);
}

function viewMessage(view: CollectorViewState): string {
  return `${view.nodeMessage ?? ''} ${view.credentialState ?? ''}`.trim();
}

// Maps a completed single-node view to a batch outcome kind. BLE labels are
// never trusted; nodeId is only taken from the authenticated owner hint.
export function classifyBatchView(view: CollectorViewState, signalAborted: boolean): {
  kind: BatchNodeKind; nodeId: string | null; message: string;
} {
  if (signalAborted && view.nodeState !== 'complete') {
    return { kind: 'cancelled', nodeId: view.owner?.nodeId ?? null, message: viewMessage(view) || 'Sammel-Sync abgebrochen.' };
  }
  if (view.fertig && view.nodeState === 'complete') {
    return { kind: 'success', nodeId: view.owner?.nodeId ?? null, message: viewMessage(view) };
  }
  if (view.unclaimed) {
    return { kind: 'unclaimed', nodeId: view.owner?.nodeId ?? null, message: viewMessage(view) };
  }
  if (view.foreign) {
    return { kind: 'foreign', nodeId: view.owner?.nodeId ?? null, message: viewMessage(view) };
  }
  const text = viewMessage(view);
  if (/Offline-Credential|Credential|Autorisierung abgelehnt|Node-Authentizität|Node-Identität offline unbekannt|Keine aktive Mitgliedschaft|Bitte zuerst anmelden|Zugriff nicht erlaubt/i.test(text)) {
    return { kind: 'unauthorized', nodeId: view.owner?.nodeId ?? null, message: text };
  }
  if (/Kein Node gefunden|nicht erreichbar|BLE-Verbindung|Verbindung.*(fehlgeschlagen|abgebrochen|unterbrochen)|disconnected|NetworkError|zeitüberschritten|Zeitüberschreitung|Timeout|Bluetooth.*(verweigert|nicht verfügbar)|kein.*ausgewählt|Geräteauswahl/i.test(text)) {
    return { kind: 'unreachable', nodeId: view.owner?.nodeId ?? null, message: text };
  }
  return { kind: 'failed', nodeId: view.owner?.nodeId ?? null, message: text || 'Sync fehlgeschlagen.' };
}

function classifyThrown(error: unknown, signalAborted: boolean): { kind: BatchNodeKind; message: string } {
  if (signalAborted || isCancelledError(error)) {
    return { kind: 'cancelled', message: error instanceof Error ? error.message : 'Sammel-Sync abgebrochen.' };
  }
  const text = error instanceof Error ? error.message : String(error);
  const name = error && typeof error === 'object' && 'name' in error ? String((error as { name: unknown }).name) : '';
  const kind = (error as { kind?: unknown }).kind;
  if (kind === 'timeout' || name === 'NetworkError' || /zeitüberschritten|Timeout|Verbindung|disconnected|nicht erreichbar|Kein Node gefunden/i.test(text)) {
    return { kind: 'unreachable', message: text };
  }
  if (/Credential|Autorisierung|Mitgliedschaft|anmelden/i.test(text)) {
    return { kind: 'unauthorized', message: text };
  }
  return { kind: 'failed', message: text || 'Sync fehlgeschlagen.' };
}

async function disconnectQuietly(transport: NodeTransport | undefined): Promise<void> {
  if (!transport) return;
  try {
    await transport.disconnect();
  } catch {
    // Cleanup must not mask the operation result.
  }
}

// Sequential orchestration: exactly one GATT session at a time. A failing
// node never stops the remaining nodes; remaining nodes can be retried
// without repeating successes by re-running only their inputs.
export async function runBatchSync(
  inputs: BatchNodeInput[],
  callbacks: BatchSyncCallbacks = {},
): Promise<BatchNodeResult[]> {
  const devices = dedupeBatchInputs(inputs);
  const results: BatchNodeResult[] = [];
  const perNodeTimeoutMs = callbacks.perNodeTimeoutMs ?? BATCH_PER_NODE_TIMEOUT_MS;
  const startedAt = callbacks.now ?? (() => Date.now());

  for (let index = 0; index < devices.length; index++) {
    const input = devices[index];
    if (callbacks.signal?.aborted) {
      const alreadyCancelled = results.some(r => r.kind === 'cancelled');
      for (let rest = index; rest < devices.length; rest++) {
        const skipped = devices[rest];
        const result: BatchNodeResult = {
          browserId: skipped.browserId, browserLabel: skipped.browserLabel,
          // The in-flight node already recorded 'cancelled'; remaining nodes
          // are honestly 'skipped'. Only when abort happens between nodes
          // (no cancelled yet) is the next node the cancelled one.
          kind: rest === index && !alreadyCancelled ? 'cancelled' : 'skipped',
          nodeId: null, ownerNodeId: null, recordsReceived: 0, watermark: null,
          message: 'Sammel-Sync abgebrochen. Bereits abgeschlossene Näpfe bleiben gültig.',
          foreignOrganizationName: null, unclaimed: false, durationMs: 0,
        };
        results.push(result);
        callbacks.onNodeDone?.(rest, devices.length, result);
      }
      break;
    }
    callbacks.onNodeStart?.(index, devices.length, input);
    const nodeStarted = startedAt();
    const perNode = new AbortController();
    const forwardAbort = () => perNode.abort();
    callbacks.signal?.addEventListener('abort', forwardAbort, { once: true });
    let timeout: ReturnType<typeof setTimeout> | undefined;
    if (Number.isFinite(perNodeTimeoutMs) && perNodeTimeoutMs > 0) {
      timeout = setTimeout(() => perNode.abort(), perNodeTimeoutMs);
    }
    let transport: NodeTransport | undefined;
    try {
      try {
        transport = callbacks.createTransport
          ? await callbacks.createTransport(input.device)
          : await defaultTransportFor(input.device);
      } catch (error) {
        const classified = classifyThrown(error, perNode.signal.aborted || callbacks.signal?.aborted === true);
        const result: BatchNodeResult = {
          browserId: input.browserId, browserLabel: input.browserLabel,
          kind: classified.kind === 'cancelled' ? 'cancelled' : 'unreachable',
          nodeId: null, ownerNodeId: null, recordsReceived: 0, watermark: null,
          message: classified.message || 'Napf nicht erreichbar. Bitte Napf einschalten und erneut versuchen.',
          foreignOrganizationName: null, unclaimed: false, durationMs: startedAt() - nodeStarted,
        };
        results.push(result);
        callbacks.onNodeDone?.(index, devices.length, result);
        continue;
      }
      const runner: BatchSingleNodeRunner = callbacks.runSingleNode ?? (async (t, signal, onUpdate) =>
        runFieldSync({
          createTransport: () => t,
          signal,
          onUpdate,
          trustedNowS: callbacks.trustedNowS,
        }));
      let view: CollectorViewState = { ...initialCollectorView };
      try {
        view = await runner(transport, perNode.signal, next =>
          callbacks.onNodeUpdate?.(index, devices.length, input, next));
      } catch (error) {
        const aborted = perNode.signal.aborted || callbacks.signal?.aborted === true;
        const timedOut = perNode.signal.aborted && !callbacks.signal?.aborted;
        const classified = classifyThrown(error, aborted);
        const kind: BatchNodeKind = timedOut && classified.kind === 'cancelled' ? 'unreachable' : classified.kind;
        const result: BatchNodeResult = {
          browserId: input.browserId, browserLabel: input.browserLabel,
          kind, nodeId: null, ownerNodeId: null, recordsReceived: 0, watermark: null,
          message: timedOut
            ? `Zeitüberschreitung bei diesem Napf (${Math.round(perNodeTimeoutMs / 1000)} s). Übrige Näpfe wurden weiter bearbeitet; bitte erneut versuchen.`
            : classified.message,
          foreignOrganizationName: null, unclaimed: false, durationMs: startedAt() - nodeStarted,
        };
        results.push(result);
        callbacks.onNodeDone?.(index, devices.length, result);
        continue;
      }
      const aborted = perNode.signal.aborted || callbacks.signal?.aborted === true;
      const timedOut = perNode.signal.aborted && !callbacks.signal?.aborted;
      if (timedOut && view.nodeState !== 'complete') {
        const result: BatchNodeResult = {
          browserId: input.browserId, browserLabel: input.browserLabel,
          kind: 'unreachable', nodeId: view.owner?.nodeId ?? null, ownerNodeId: view.owner?.nodeId ?? null,
          recordsReceived: view.recordsReceived ?? 0, watermark: view.watermark,
          message: `Zeitüberschreitung bei diesem Napf (${Math.round(perNodeTimeoutMs / 1000)} s). Übrige Näpfe wurden weiter bearbeitet; bitte erneut versuchen.`,
          foreignOrganizationName: view.foreign?.organizationName ?? null,
          unclaimed: view.unclaimed, durationMs: startedAt() - nodeStarted,
        };
        results.push(result);
        callbacks.onNodeDone?.(index, devices.length, result);
        continue;
      }
      const classified = classifyBatchView(view, aborted);
      const result: BatchNodeResult = {
        browserId: input.browserId, browserLabel: input.browserLabel,
        kind: classified.kind, nodeId: classified.nodeId, ownerNodeId: view.owner?.nodeId ?? null,
        recordsReceived: view.recordsReceived ?? 0, watermark: view.watermark,
        message: classified.message || viewMessage(view),
        foreignOrganizationName: view.foreign?.organizationName ?? null,
        unclaimed: view.unclaimed, durationMs: startedAt() - nodeStarted,
      };
      results.push(result);
      callbacks.onNodeDone?.(index, devices.length, result);
    } finally {
      if (timeout !== undefined) clearTimeout(timeout);
      callbacks.signal?.removeEventListener('abort', forwardAbort);
      await disconnectQuietly(transport);
    }
  }
  return results;
}

async function defaultTransportFor(device: unknown): Promise<NodeTransport> {
  const { WebBluetoothTransport } = await import('../platform/web-bluetooth');
  return new WebBluetoothTransport(device as never);
}

// Selects only retryable outcomes. Successful, cancelled and skipped nodes
// are never repeated by a retry.
export function retryableBatchInputs(
  inputs: BatchNodeInput[],
  results: BatchNodeResult[],
): BatchNodeInput[] {
  const retryIds = new Set(results
    .filter(r => r.kind === 'unreachable' || r.kind === 'failed' || r.kind === 'unauthorized')
    .map(r => r.browserId));
  return inputs.filter(i => retryIds.has(i.browserId));
}
