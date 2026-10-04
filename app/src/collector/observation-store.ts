// Durable local observation/outbox repository (issue #8).
//
// Uses the shared CollectorDatabase (Dexie/IndexedDB, v2). Stores at minimum:
// RawObservations keyed by (node_id, incarnation, sequence), upload/outbox
// state, node metadata for retry, sync state to recover safely after restart,
// plus the credential/device references already defined by #17 (separate DB).
//
// Critical ordering lives here: SyncEngine persists via putObservations() in
// one IndexedDB transaction and only then computes the contiguous watermark
// from durable state and sends ACK. If the transaction fails (quota, crash),
// no ACK is sent for those observations.

import type { BleRecord } from './ble-codec';
import {
  type CollectorDatabase, type NodeMeta, type ObservationUploadState, type StoredObservation,
} from '../platform/offline-store';

export type { StoredObservation, NodeMeta, ObservationUploadState };

export class ObservationStoreError extends Error {
  constructor(
    message: string,
    readonly kind: 'quota' | 'conflict' | 'unavailable',
    readonly cause?: unknown,
  ) {
    super(message);
    this.name = 'ObservationStoreError';
  }
}

export function describeStoreError(error: unknown): string {
  if (error instanceof ObservationStoreError) return error.message;
  if (error instanceof Error) {
    if (error.name === 'QuotaExceededError') {
      return 'Lokaler Speicher voll (Quota erreicht). Bitte Speicher freigeben und erneut versuchen.';
    }
    return error.message;
  }
  return 'Lokaler Speicher nicht verfügbar.';
}

function toStored(record: BleRecord, organizationId: string | null): StoredObservation {
  return {
    nodeId: record.nodeId,
    incarnation: record.incarnation,
    sequence: record.sequence.toString(),
    chipId: record.chipId,
    clockStatus: record.clockStatus,
    epochMs: record.epochMs?.toString() ?? null,
    monotonicMs: record.monotonicMs.toString(),
    bootCounter: record.bootCounter,
    receivedAt: Date.now(),
    organizationId,
    uploadState: 'pending',
    uploadAttempts: 0,
    lastUploadError: null,
    uploadedAt: null,
  };
}

// Interface consumed by SyncEngine: durable persist + watermark inputs.
// Implemented by both the legacy SyncStore (tests) and this production store.
export interface DurableObservationStore {
  putObservations(records: BleRecord[]): Promise<void>;
  sequences(nodeId: string, incarnation: string): Promise<bigint[]>;
}

export class CollectorObservationStore implements DurableObservationStore {
  constructor(private readonly db: CollectorDatabase) {}

  async open(): Promise<void> {
    await this.db.open();
  }

  close(): void {
    this.db.close();
  }

  // Idempotent insert keyed by (nodeId, incarnation, sequence). Retransmitted
  // bytes must match exactly; conflicting content is rejected without
  // overwriting, so a lost ACK can safely resend.
  async putObservations(records: BleRecord[], organizationId: string | null = null): Promise<void> {
    try {
      await this.db.transaction('rw', this.db.observations, async () => {
        for (const r of records) {
          const key: [string, string, string] = [r.nodeId, r.incarnation, r.sequence.toString()];
          const existing = await this.db.observations.get(key);
          if (existing) {
            if (existing.chipId !== r.chipId || existing.clockStatus !== r.clockStatus ||
                existing.epochMs !== (r.epochMs?.toString() ?? null) ||
                existing.monotonicMs !== r.monotonicMs.toString() ||
                existing.bootCounter !== r.bootCounter) {
              throw new ObservationStoreError(
                `Conflicting observation identity: ${key.join('/')}`, 'conflict');
            }
            continue;
          }
          await this.db.observations.add(toStored(r, organizationId ?? null));
        }
      });
    } catch (error) {
      if (error instanceof ObservationStoreError) throw error;
      throw asStoreError(error);
    }
  }

  async sequences(nodeId: string, incarnation: string): Promise<bigint[]> {
    const rows = await this.db.observations.where('nodeId').equals(nodeId).toArray();
    return rows
      .filter(r => r.incarnation === incarnation)
      .map(r => BigInt(r.sequence))
      .sort((a, b) => (a < b ? -1 : a > b ? 1 : 0));
  }

  async count(nodeId: string, incarnation: string): Promise<number> {
    return (await this.sequences(nodeId, incarnation)).length;
  }

  async pendingUploads(organizationId: string, limit = Number.MAX_SAFE_INTEGER): Promise<StoredObservation[]> {
    const all = await this.db.observations.where('uploadState').anyOf('pending', 'failed', 'uploading').toArray();
    // Organization queues stay partitioned even if older rows lack the field
    // (they default to the requesting org only when explicitly unset is wrong;
    // rows with a different org never leak into this queue).
    return all.filter(r => r.organizationId === organizationId).slice(0, limit);
  }

  async markPending(sequences: Array<{ nodeId: string; incarnation: string; sequence: string }>): Promise<void> {
    await this.db.transaction('rw', this.db.observations, async () => {
      for (const s of sequences) {
        await this.db.observations.update([s.nodeId, s.incarnation, s.sequence], { uploadState: 'pending' });
      }
    });
  }

  async pendingForNode(nodeId: string): Promise<StoredObservation[]> {
    return this.db.observations.where('nodeId').equals(nodeId).toArray();
  }

  async markUploading(sequences: Array<{ nodeId: string; incarnation: string; sequence: string }>): Promise<void> {
    await this.db.transaction('rw', this.db.observations, async () => {
      for (const s of sequences) {
        await this.db.observations.update([s.nodeId, s.incarnation, s.sequence], { uploadState: 'uploading' });
      }
    });
  }

  async markUploaded(
    sequences: Array<{ nodeId: string; incarnation: string; sequence: string }>,
    uploadedAt = Date.now(),
  ): Promise<void> {
    await this.db.transaction('rw', this.db.observations, async () => {
      for (const s of sequences) {
        await this.db.observations.update([s.nodeId, s.incarnation, s.sequence], {
          uploadState: 'uploaded', uploadedAt, lastUploadError: null,
        });
      }
    });
  }

  async markUploadResult(
    sequence: { nodeId: string; incarnation: string; sequence: string },
    ok: boolean,
    error: string | null,
  ): Promise<void> {
    const key: [string, string, string] = [sequence.nodeId, sequence.incarnation, sequence.sequence];
    await this.db.transaction('rw', this.db.observations, async () => {
      const existing = await this.db.observations.get(key);
      await this.db.observations.update(key, {
        uploadState: ok ? 'uploaded' : 'failed',
        uploadedAt: ok ? Date.now() : existing?.uploadedAt ?? null,
        lastUploadError: error,
        uploadAttempts: (existing?.uploadAttempts ?? 0) + 1,
      });
    });
  }

  async requeueFailed(nodeId: string): Promise<number> {
    const rows = await this.db.observations.where('nodeId').equals(nodeId).toArray();
    let count = 0;
    await this.db.transaction('rw', this.db.observations, async () => {
      for (const row of rows) {
        if (row.uploadState === 'failed' || row.uploadState === 'uploading') {
          await this.db.observations.update([row.nodeId, row.incarnation, row.sequence], {
            uploadState: 'pending', lastUploadError: row.uploadState === 'failed' ? row.lastUploadError : 'Abgebrochener Upload wird erneut versucht.',
          });
          count++;
        }
      }
    });
    return count;
  }

  async uploadStats(nodeId: string): Promise<{ total: number; uploaded: number; pending: number; failed: number }> {
    const rows = await this.db.observations.where('nodeId').equals(nodeId).toArray();
    return {
      total: rows.length,
      uploaded: rows.filter(r => r.uploadState === 'uploaded').length,
      pending: rows.filter(r => r.uploadState === 'pending' || r.uploadState === 'uploading').length,
      failed: rows.filter(r => r.uploadState === 'failed').length,
    };
  }

  async session(nodeId: string) {
    return this.db.syncSessions.get(nodeId);
  }

  async saveSession(session: { nodeId: string; incarnation: string; watermark: string; phase: string; updatedAt: number }): Promise<void> {
    await this.db.syncSessions.put(session);
  }

  async nodeMeta(nodeId: string): Promise<NodeMeta | undefined> {
    return this.db.nodeMeta.get(nodeId);
  }

  async saveNodeMeta(meta: NodeMeta): Promise<void> {
    await this.db.nodeMeta.put(meta);
  }
}

function asStoreError(error: unknown): ObservationStoreError {
  if (error instanceof Error) {
    const name = error.name;
    const message = error.message ?? '';
    if (name === 'QuotaExceededError' || /quota/i.test(message)) {
      return new ObservationStoreError(
        'Lokaler Speicher voll (Quota erreicht). Bitte Speicher freigeben und erneut versuchen.', 'quota', error);
    }
    if (/conflict/i.test(message)) return new ObservationStoreError(message, 'conflict', error);
  }
  return new ObservationStoreError(
    error instanceof Error ? `Lokaler Speicherfehler: ${error.message}` : 'Lokaler Speicher nicht verfügbar.',
    'unavailable', error);
}

// Computes the contiguous high-watermark over durably stored sequences.
// base = last acked watermark; stored = sequences > base for one lifetime.
// Never skips gaps: {100,101,103} with base 100 → 101.
export function contiguousWatermark(base: bigint, stored: bigint[]): bigint {
  const set = new Set(stored);
  let w = base;
  while (set.has(w + 1n)) {
    if (w + 1n === BigInt('0xffffffffffffffff')) break;
    w += 1n;
  }
  return w;
}
