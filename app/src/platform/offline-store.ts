import Dexie, { type Table } from 'dexie';

// Local transport envelope; this is not an HTTP DTO. Payload is opaque BLE data.
export interface PendingUpload {
  // Stable, globally unique batch identity; its envelope must not change on retry.
  id: string;
  organizationId: string;
  appDeviceId: string;
  nodeId: string;
  protocolVersion: number;
  payload: Uint8Array;
  createdAt: number;
}
export interface Outbox {
  put(upload: PendingUpload): Promise<void>;
  pending(organizationId: string): Promise<PendingUpload[]>;
}
export class CollectorDatabase extends Dexie {
  uploads!: Table<PendingUpload, string>;
  observations!: Table<StoredObservation, [string, string, string]>;
  syncSessions!: Table<SyncSessionState, string>;
  nodeMeta!: Table<NodeMeta, string>;
  constructor(name = 'miezmerker-collector') {
    super(name);
    this.version(1).stores({ uploads: 'id, organizationId, [organizationId+nodeId]' });
    // v2 adds the durable Node→PWA observation store for issue #8. The v1
    // upload envelope stays untouched so existing outbox data survives.
    this.version(2).stores({
      uploads: 'id, organizationId, [organizationId+nodeId]',
      observations: '[nodeId+incarnation+sequence], nodeId, [nodeId+uploadState], uploadState',
      syncSessions: 'nodeId',
      nodeMeta: 'nodeId',
    });
  }
}

// Durable BLE observation keyed by full event identity
// (node_id, incarnation, sequence). The triple is the primary key: a factory
// reset starts a new incarnation lifetime at sequence 1, so (node_id,
// sequence) alone is unique only within one incarnation.
export type ObservationUploadState = 'pending' | 'uploading' | 'uploaded' | 'failed';

export interface StoredObservation {
  nodeId: string;
  incarnation: string;
  sequence: string;
  chipId: string;
  clockStatus: number;
  epochMs: string | null;
  monotonicMs: string;
  bootCounter: number;
  receivedAt: number;
  organizationId: string | null;
  uploadState: ObservationUploadState;
  uploadAttempts: number;
  lastUploadError: string | null;
  uploadedAt: number | null;
}

export interface SyncSessionState {
  nodeId: string;
  incarnation: string;
  watermark: string;
  phase: string;
  updatedAt: number;
}

export interface NodeMeta {
  nodeId: string;
  incarnation: string | null;
  claimState: number | null;
  organizationId: string | null;
  organizationSlug: string | null;
  organizationName: string | null;
  publicContact: string | null;
  firmwareVersion: string | null;
  lastWatermark: string | null;
  lastSyncAt: number | null;
  pendingCount: number | null;
  // Backend-pinned node public key (#18). Captured at claim/sync time and
  // reused offline so Node proof verification works without Internet.
  publicKeyX: string | null;
  publicKeyY: string | null;
}

// Best-effort persistent browser storage. Correctness never depends on it:
// unacknowledged records stay on the Node until durably persisted + ACKed.
export async function requestPersistentStorage(): Promise<boolean> {
  try {
    const storage = (navigator as unknown as { storage?: {
      persist?: () => Promise<boolean>; persisted?: () => Promise<boolean>;
    } }).storage;
    if (!storage) return false;
    if (typeof storage.persisted === 'function' && await storage.persisted()) return true;
    if (typeof storage.persist === 'function') return await storage.persist();
    return false;
  } catch {
    return false;
  }
}
export class DexieOutbox implements Outbox {
  constructor(private readonly database: CollectorDatabase) {}
  async put(upload: PendingUpload): Promise<void> {
    await this.database.transaction('rw', this.database.uploads, async () => {
      const existing = await this.database.uploads.get(upload.id);
      if (existing) {
        const fields = ['organizationId', 'appDeviceId', 'nodeId', 'protocolVersion', 'createdAt'] as const;
        const identical = fields.every(field => existing[field] === upload[field])
          && existing.payload.length === upload.payload.length
          && existing.payload.every((byte, index) => byte === upload.payload[index]);
        if (!identical) throw new Error(`Conflicting upload identity: ${upload.id}`);
        return;
      }
      await this.database.uploads.add(upload);
    });
  }
  pending(organizationId: string) {
    return this.database.uploads.where('organizationId').equals(organizationId).toArray();
  }
}
