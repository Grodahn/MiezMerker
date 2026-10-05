// Collector synchronization orchestrator (issue #8).
//
// Keeps at least these concerns separated:
// - NodeTransport / Web Bluetooth adapter (platform/web-bluetooth.ts)
// - BLE protocol codec/client from #6 (ble-codec.ts, sync-engine.ts)
// - authorization/session handshake (authorization.ts)
// - collector synchronization state machine (SyncEngine + this orchestrator)
// - durable local observation/outbox repository (observation-store.ts)
// - backend upload state machine (backend-upload.ts)
// - UI/view state (CollectorShell.tsx)
//
// Node→PWA and PWA→Backend remain independent state machines. A successful
// Node sync never depends on backend/network availability.

import { getAuthState } from '../platform/auth';
import { OfflineIdentity, OfflineIdentityDatabase } from '../platform/offline-identity';
import { WebCryptoDeviceKeys } from '../platform/device-keys';
import { loadTrustedNodeIdentity, NodeIdentityError } from '../platform/node-identity';
import type { NodeTransport } from '../platform/node-transport';
import { SyncEngine, SyncFailedError } from './sync-engine';
import type { OwnerInfo } from './ble-codec';
import { CollectorObservationStore } from './observation-store';
import { CollectorDatabase, requestPersistentStorage } from '../platform/offline-store';
import type { BackendSyncState } from './backend-upload';
import { resolveCredential, CredentialError } from './authorization';
import { PublicProbeTransport, type PublicNodeInfo } from './public-probe';

export type NodeSyncState =
  | 'idle'
  | 'connecting'
  | 'receiving'
  | 'persisting'
  | 'acknowledging'
  | 'complete'
  | 'failed';

export type ForeignNodeInfo = {
  organizationName: string;
  organizationSlug: string;
  publicContact: string;
};

export interface CollectorViewState {
  nodeState: NodeSyncState;
  backendState: BackendSyncState;
  nodeMessage: string;
  backendMessage: string;
  owner: OwnerInfo | null;
  foreign: ForeignNodeInfo | null;
  unclaimed: boolean;
  recordsReceived: number;
  watermark: string | null;
  pendingUploads: number;
  uploadedCount: number;
  bluetoothSupported: boolean;
  credentialState: string;
  fertig: boolean;
}

export const initialCollectorView: CollectorViewState = {
  nodeState: 'idle',
  backendState: 'idle',
  nodeMessage: '',
  backendMessage: '',
  owner: null,
  foreign: null,
  unclaimed: false,
  recordsReceived: 0,
  watermark: null,
  pendingUploads: 0,
  uploadedCount: 0,
  bluetoothSupported: true,
  credentialState: '',
  fertig: false,
};

export interface SyncCallbacks {
  onUpdate: (view: CollectorViewState) => void;
  createTransport: () => NodeTransport | Promise<NodeTransport>;
  trustedNowS?: () => number;
}

function toNodeState(phase: string): NodeSyncState {
  if (phase === 'complete') return 'complete';
  if (phase === 'failed') return 'failed';
  if (phase === 'persisting') return 'persisting';
  if (phase === 'acknowledging' || phase === 'compacting') return 'acknowledging';
  if (phase === 'connecting' || phase === 'public-info' || phase === 'challenge' ||
      phase === 'authorizing' || phase === 'authorized') return 'connecting';
  return 'receiving';
}

export function foreignInfo(owner: OwnerInfo | null): ForeignNodeInfo | null {
  if (!owner || owner.claimState !== 1 || !owner.organizationName) return null;
  return {
    organizationName: owner.organizationName,
    organizationSlug: owner.organizationSlug,
    publicContact: owner.publicContact,
  };
}

// Runs one full field visit: credential → transport → SyncEngine (persist
// before ACK) → durable stats → separate backend upload attempt. Backend
// failure never turns a successful Node sync into a failed field visit.
export async function runFieldSync(callbacks: SyncCallbacks): Promise<CollectorViewState> {
  const { onUpdate, createTransport } = callbacks;
  const trustedNowS = callbacks.trustedNowS ?? (() => Math.floor(Date.now() / 1000));
  let view: CollectorViewState = { ...initialCollectorView };
  const emit = (patch: Partial<CollectorViewState>): void => {
    view = { ...view, ...patch };
    onUpdate(view);
  };

  const auth = getAuthState();
  const organizationId = auth.activeOrganizationId;
  if (!auth.user || !organizationId) {
    emit({ nodeState: 'failed', nodeMessage: 'Bitte anmelden und Organisation wählen.' });
    return view;
  }

  const collectorDb = new CollectorDatabase();
  const observations = new CollectorObservationStore(collectorDb);
  try {
    let transport: NodeTransport;
    try {
      transport = await createTransport();
    } catch (e) {
      emit({ nodeState: 'failed', nodeMessage: e instanceof Error ? e.message : 'Kein Node ausgewählt.' });
      return view;
    }
    // Public discovery needs no credential or device key. In particular an
    // ADMIN can discover an unclaimed node before initial credential issuance.
    emit({ nodeState: 'connecting', nodeMessage: 'Verbinde mit Node …' });
    const hello = await new PublicProbeTransport(transport).readPublicHello();
    emit({ owner: hello.owner });
    if (hello.claimState !== 1) {
      emit({ nodeState: 'failed', unclaimed: true,
        nodeMessage: 'Node ist UNCLAIMED. Nur ADMIN kann ihn im physischen Claim-Modus claimen.' });
      return view;
    }
    if (hello.owner && hello.owner.organizationId.toLowerCase() !== organizationId.toLowerCase()) {
      emit({ nodeState: 'failed', foreign: foreignInfo(hello.owner),
        nodeMessage: `Dieser MiezMerker gehört ${hello.owner.organizationName}.` +
          (hello.owner.publicContact ? ` Kontakt: ${hello.owner.publicContact}` : '') });
      return view;
    }
    await observations.open();
    await requestPersistentStorage();

    // Credential state for the /sync header (login/org/offline-credential).
    const identityDb = new OfflineIdentityDatabase();
    const identity = new OfflineIdentity(identityDb);
    let credential: string;
    try {
      const resolved = await resolveCredential(identity, organizationId);
      credential = resolved.credential;
      emit({ credentialState: resolved.renewed ? 'Offline-Credential erneuert.' : 'Offline-Credential gültig.' });
    } catch (e) {
      const message = e instanceof CredentialError ? e.message
        : e instanceof Error ? e.message : 'Credential-Fehler.';
      emit({ nodeState: 'failed', nodeMessage: message, credentialState: message });
      return view;
    } finally {
      identityDb.close();
    }

    // Trusted node identity comes from the backend-pinned record, never from
    // the BLE peer. Offline we fall back to the locally stored node key
    // captured during a previous sync/claim; without it the node proof cannot
    // run and sync stops with a clear message.
    emit({ nodeState: 'connecting', nodeMessage: 'Verbinde mit Node …' });
    const deviceKeys = await deviceKeysForUser(auth.user.userId);
    const engineResult = await runEngineWithDiscovery(
      transport, observations, deviceKeys, credential, organizationId, trustedNowS,
      (phase, received) => emit({
        nodeState: toNodeState(phase),
        nodeMessage: phaseMessage(phase, received),
        recordsReceived: received,
      }),
      (owner) => emit({ owner }),
      hello,
    );

    if (engineResult.unclaimed) {
      emit({ nodeState: 'failed', unclaimed: true, owner: engineResult.owner,
        nodeMessage: 'Node ist UNCLAIMED. Nur ADMIN kann ihn im physischen Claim-Modus claimen.' });
      return view;
    }
    if (engineResult.foreign) {
      emit({ nodeState: 'failed', foreign: foreignInfo(engineResult.owner), owner: engineResult.owner,
        nodeMessage: engineResult.owner
          ? `Dieser MiezMerker gehört ${engineResult.owner.organizationName}.` +
            (engineResult.owner.publicContact ? ` Kontakt: ${engineResult.owner.publicContact}` : '')
          : 'Node gehört einer anderen Organisation.' });
      return view;
    }
    if (!engineResult.ok) {
      emit({ nodeState: 'failed', owner: engineResult.owner, nodeMessage: engineResult.message });
      return view;
    }

    const stats = await observations.uploadStats(engineResult.nodeId)
      .catch(() => ({ pending: engineResult.received, failed: 0, uploaded: 0, total: engineResult.received }));
    try {
      await observations.saveNodeMeta({
        nodeId: engineResult.nodeId,
        incarnation: engineResult.incarnation,
        claimState: 1,
        organizationId,
        organizationSlug: engineResult.owner?.organizationSlug ?? null,
        organizationName: engineResult.owner?.organizationName ?? null,
        publicContact: engineResult.owner?.publicContact ?? null,
        firmwareVersion: null,
        lastWatermark: engineResult.watermark,
        lastSyncAt: Date.now(),
        pendingCount: stats.pending,
        publicKeyX: engineResult.publicKeyX ?? null,
        publicKeyY: engineResult.publicKeyY ?? null,
      });
    } catch {
      // Metadata is best-effort; durable observations + ACK already hold.
    }

    emit({
      nodeState: 'complete',
      owner: engineResult.owner,
      recordsReceived: engineResult.received,
      watermark: engineResult.watermark,
      pendingUploads: stats.pending + stats.failed,
      uploadedCount: stats.uploaded,
      fertig: true,
      nodeMessage: `Fertig: ${engineResult.received} Beobachtungen sicher übernommen und quittiert (Stand ${engineResult.watermark}).` +
        (engineResult.maintenanceWarning ? ` ${engineResult.maintenanceWarning}` : ''),
    });

    // Return as soon as the field copy completes. The UI independently drains
    // the outbox on open/online/after a visit; HTTP cannot hold the BLE visit open.
    emit({ backendState: navigator.onLine ? 'idle' : 'waiting-for-network',
      backendMessage: 'Beobachtungen lokal gespeichert. Backend-Upload steht separat aus.' });
    return view;
  } finally {
    collectorDb.close();
  }
}

async function deviceKeysForUser(userId: string): Promise<WebCryptoDeviceKeys> {
  const db = new OfflineIdentityDatabase();
  try {
    return await new OfflineIdentity(db).keys(userId);
  } finally {
    db.close();
  }
}

function phaseMessage(phase: string, received: number): string {
  switch (phase) {
    case 'connecting': case 'public-info': return 'Verbinde mit Node …';
    case 'challenge': case 'authorizing': return 'Autorisiere …';
    case 'authorized': return 'Autorisiert. Starte Sync …';
    case 'syncing': return received > 0 ? `Empfange … (${received})` : 'Empfange Beobachtungen …';
    case 'persisting': return `Speichere lokal … (${received})`;
    case 'acknowledging': case 'compacting': return 'Quittiere gegenüber Node …';
    case 'complete': return 'Fertig.';
    default: return 'Synchronisiere …';
  }
}

interface EngineOutcome {
  ok: boolean;
  message: string;
  owner: OwnerInfo | null;
  unclaimed: boolean;
  foreign: boolean;
  nodeId: string;
  incarnation: string;
  received: number;
  watermark: string;
  publicKeyX?: string;
  publicKeyY?: string;
  maintenanceWarning?: string;
}

// Discovers the node id via a public-only probe, resolves the backend-pinned
// node key (offline: locally stored key), then runs the full authenticated
// engine. Protected data is never requested before authorization.
export async function runEngineWithDiscovery(
  transport: NodeTransport,
  observations: CollectorObservationStore,
  deviceKeys: WebCryptoDeviceKeys,
  credential: string,
  organizationId: string,
  trustedNowS: () => number,
  onProgress: (phase: string, received: number) => void,
  onOwner: (owner: OwnerInfo | null) => void,
  discoveredHello?: PublicNodeInfo,
): Promise<EngineOutcome> {
  const probe = new PublicProbeTransport(transport);
  const hello = discoveredHello ?? await probe.readPublicHello();
  onOwner(hello.owner);
  if (hello.claimState !== 1) {
    return { ok: false, message: 'unclaimed', owner: hello.owner, unclaimed: true, foreign: false,
      nodeId: hello.nodeId, incarnation: hello.incarnation, received: 0, watermark: '0' };
  }
  if (hello.owner && hello.owner.organizationId.toLowerCase() !== organizationId.toLowerCase()) {
    return { ok: false, message: 'foreign', owner: hello.owner, unclaimed: false, foreign: true,
      nodeId: hello.nodeId, incarnation: hello.incarnation, received: 0, watermark: '0' };
  }
  let pinned: { nodeId: string; publicKeyX: string; publicKeyY: string };
  try {
    if (typeof navigator !== 'undefined' && !navigator.onLine) throw new Error('Offline');
    pinned = await loadTrustedNodeIdentity(hello.nodeId);
  } catch (e) {
    if (e instanceof NodeIdentityError && e.kind === 'forbidden') {
      // Foreign claimed node: backend tenant gate refuses the key lookup.
      // Show only the public owner hint; never probe protected data.
      return { ok: false, message: 'foreign', owner: hello.owner, unclaimed: false, foreign: true,
        nodeId: hello.nodeId, incarnation: hello.incarnation, received: 0, watermark: '0' };
    }
    // Offline / backend unreachable: fall back to the locally stored
    // backend-pinned key captured during a previous sync or claim (#18).
    const meta = await observations.nodeMeta(hello.nodeId);
    if (meta?.organizationId === organizationId && meta?.publicKeyX && meta?.publicKeyY) {
      pinned = { nodeId: hello.nodeId, publicKeyX: meta.publicKeyX, publicKeyY: meta.publicKeyY };
    } else {
      return { ok: false, message: 'Node-Identität offline unbekannt. Bitte einmal online synchronisieren.', owner: hello.owner,
        unclaimed: false, foreign: false, nodeId: hello.nodeId, incarnation: hello.incarnation, received: 0, watermark: '0' };
    }
  }
  // Cache the trusted key before receiving/ACKing records. Interrupted first
  // visits can then resume offline without requiring a successful online sync.
  const cachedMeta = await observations.nodeMeta(hello.nodeId);
  await observations.saveNodeMeta({
    ...cachedMeta,
    nodeId: hello.nodeId, incarnation: hello.incarnation, claimState: 1, organizationId,
    organizationSlug: hello.owner?.organizationSlug ?? null,
    organizationName: hello.owner?.organizationName ?? null,
    publicContact: hello.owner?.publicContact ?? null, firmwareVersion: null,
    lastWatermark: cachedMeta?.lastWatermark ?? null, lastSyncAt: cachedMeta?.lastSyncAt ?? null,
    pendingCount: cachedMeta?.pendingCount ?? null,
    publicKeyX: pinned.publicKeyX, publicKeyY: pinned.publicKeyY,
  });
  // One maximum-size record fits a GATT attribute and the documented 185-byte
  // transport. Do not assume the board clamps oversized requested pages.
  const engine = new SyncEngine(transport, observations, deviceKeys, pinned, { maxBatchRecords: 1 });
  const timerHost: { setInterval: typeof setInterval; clearInterval: typeof clearInterval } =
    (typeof window !== 'undefined' ? window : globalThis) as never;
  const progressTimer = timerHost.setInterval(() => {
    try {
      onProgress(engine.currentPhase, engine.currentReceived);
      if (engine.currentOwner) onOwner(engine.currentOwner);
    } catch {
      // Polling must not break the sync.
    }
  }, 250);
  try {
    const result = await engine.sync(credential, trustedNowS, organizationId);
    const owner = engine.currentOwner ?? hello.owner;
    if (owner) onOwner(owner);
    if (result.ok) {
      onProgress('complete', result.recordsReceived);
      return { ok: true, message: 'Fertig.', owner, unclaimed: false, foreign: false,
        nodeId: hello.nodeId, incarnation: hello.incarnation,
        received: result.recordsReceived, watermark: result.watermark.toString(),
        maintenanceWarning: result.maintenanceWarning,
        publicKeyX: pinned.publicKeyX, publicKeyY: pinned.publicKeyY };
    }
    const activeOrg = getAuthState().activeOrganizationId;
    const errorText = result.error ?? 'Sync fehlgeschlagen.';
    const kind = errorText.split(':')[0];
    if (kind === 'unclaimed') {
      return { ok: false, message: errorText, owner, unclaimed: true, foreign: false,
        nodeId: hello.nodeId, incarnation: hello.incarnation, received: 0, watermark: '0' };
    }
    if (kind === 'foreign' || (owner && activeOrg && owner.organizationId.toLowerCase() !== activeOrg.toLowerCase())) {
      return { ok: false, message: errorText, owner, unclaimed: false, foreign: true,
        nodeId: hello.nodeId, incarnation: hello.incarnation, received: 0, watermark: '0' };
    }
    return { ok: false, message: humanSyncError(errorText), owner, unclaimed: false, foreign: false,
      nodeId: hello.nodeId, incarnation: hello.incarnation, received: 0, watermark: '0' };
  } catch (e) {
    if (e instanceof SyncFailedError && (e.kind === 'unclaimed')) {
      return { ok: false, message: e.message, owner: e.owner ?? hello.owner, unclaimed: true, foreign: false,
        nodeId: hello.nodeId, incarnation: hello.incarnation, received: 0, watermark: '0' };
    }
    if (e instanceof SyncFailedError && e.kind === 'foreign') {
      return { ok: false, message: e.message, owner: e.owner ?? hello.owner, unclaimed: false, foreign: true,
        nodeId: hello.nodeId, incarnation: hello.incarnation, received: 0, watermark: '0' };
    }
    throw e;
  } finally {
    timerHost.clearInterval(progressTimer);
  }
}

export function humanSyncError(error: string): string {
  const kind = error.split(':')[0];
  const detail = error.includes(':') ? error.slice(error.indexOf(':') + 1).trim() : error;
  switch (kind) {
    case 'incompatible': return `Protokoll inkompatibel. ${detail}`;
    case 'unauthorized': return `Autorisierung abgelehnt. ${detail}`;
    case 'storage': return detail;
    case 'connection': return `Verbindung abgebrochen. ${detail} Bitte erneut versuchen — bereits gespeicherte Records werden idempotent wiederholt.`;
    case 'protocol': return `Protokollfehler. ${detail}`;
    case 'incomplete': return `${detail} Bereits gespeicherte Records bleiben lokal erhalten.`;
    default: return detail || error;
  }
}
