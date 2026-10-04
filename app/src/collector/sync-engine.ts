// BLE sync v1 durable client-side sync engine (issue #6).
//
// PWA-side mirror of the firmware SyncServer. Owns the connection lifecycle,
// durable Dexie persistence BEFORE ACK, contiguous high-watermark computation,
// idempotent retransmission and retry after arbitrary disconnects.
//
// Layering: this is the domain state machine. Transport stays behind
// SyncChannel (Web Bluetooth); codec stays in ble-codec.ts.

import Dexie, { type Table } from 'dexie';
import {
  type BleRecord, type BatchResponse, type OwnerInfo, type StatusResponse,
  decodeBatchResponse, decodeFrame, decodeStatusResponse,
  encodeAckRequest, encodeAuthRequest, encodeBatchRequest,
  encodeChallengeRequest, encodeCompactRequest, encodeHelloRequest,
  encodeNodeProofRequest, encodeOwnerRequest, encodeStatusRequest,
  encodeTimeRequest, Opcode, PROTOCOL_VERSION, SyncError,
} from './ble-codec';
import type { NodeTransport } from '../platform/node-transport';
import type { AppDeviceKeys } from '../platform/device-keys';

export type SyncPhase =
  | 'idle'
  | 'connecting'
  | 'public-info'
  | 'challenge'
  | 'authorizing'
  | 'authorized'
  | 'syncing'
  | 'persisting'
  | 'acknowledging'
  | 'compacting'
  | 'complete'
  | 'failed';

export interface SyncResult {
  ok: boolean;
  error?: string;
  recordsReceived: number;
  watermark: bigint;
}

interface StoredObservation {
  nodeId: string;
  incarnation: string;
  sequence: string;
  chipId: string;
  clockStatus: number;
  epochMs: string | null;
  monotonicMs: string;
  bootCounter: number;
  receivedAt: number;
}

interface SyncSession {
  nodeId: string;
  incarnation: string;
  watermark: string;
  phase: SyncPhase;
  updatedAt: number;
}

export class SyncDatabase extends Dexie {
  observations!: Table<StoredObservation, [string, string, string]>;
  sessions!: Table<SyncSession, string>;
  constructor(name = 'miezmerker-sync') {
    super(name);
    this.version(1).stores({
      observations: '[nodeId+incarnation+sequence], nodeId',
      sessions: 'nodeId',
    });
  }
}

export interface SyncEngineOptions {
  maxBatchRecords?: number;
  maxRetries?: number;
  retryDelayMs?: number;
}

// Durable local store for BLE sync. Persists observations keyed by full event
// identity; retransmitted bytes must match exactly.
export class SyncStore {
  constructor(private db: SyncDatabase) {}

  async putObservations(records: BleRecord[]): Promise<void> {
    await this.db.transaction('rw', this.db.observations, async () => {
      for (const r of records) {
        const key: [string, string, string] = [r.nodeId, r.incarnation, r.sequence.toString()];
        const existing = await this.db.observations.get(key);
        if (existing) {
          if (existing.chipId !== r.chipId || existing.clockStatus !== r.clockStatus ||
              existing.epochMs !== (r.epochMs?.toString() ?? null) ||
              existing.monotonicMs !== r.monotonicMs.toString() ||
              existing.bootCounter !== r.bootCounter) {
            throw new Error(`Conflicting observation identity: ${key.join('/')}`);
          }
          continue;
        }
        await this.db.observations.add({
          nodeId: r.nodeId,
          incarnation: r.incarnation,
          sequence: r.sequence.toString(),
          chipId: r.chipId,
          clockStatus: r.clockStatus,
          epochMs: r.epochMs?.toString() ?? null,
          monotonicMs: r.monotonicMs.toString(),
          bootCounter: r.bootCounter,
          receivedAt: Date.now(),
        });
      }
    });
  }

  async sequences(nodeId: string, incarnation: string): Promise<bigint[]> {
    const rows = await this.db.observations.where('nodeId').equals(nodeId).toArray();
    return rows
      .filter(r => r.incarnation === incarnation)
      .map(r => BigInt(r.sequence))
      .sort((a, b) => (a < b ? -1 : a > b ? 1 : 0));
  }

  async session(nodeId: string): Promise<SyncSession | undefined> {
    return this.db.sessions.get(nodeId);
  }

  async saveSession(session: SyncSession): Promise<void> {
    await this.db.sessions.put(session);
  }

  async clearNode(nodeId: string): Promise<void> {
    await this.db.transaction('rw', this.db.observations, this.db.sessions, async () => {
      await this.db.observations.where('nodeId').equals(nodeId).delete();
      await this.db.sessions.where('nodeId').equals(nodeId).delete();
    });
  }
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

// Full sync engine: connect → auth → batch → persist → ack → compact.
// Retries after disconnect with fresh challenge; lost ACKs are idempotent.
export class SyncEngine {
  private phase: SyncPhase = 'idle';
  private session: SyncSession | null = null;
  private owner: OwnerInfo | null = null;
  private status: StatusResponse | null = null;

  constructor(
    private transport: NodeTransport,
    private store: SyncStore,
    private keys: AppDeviceKeys,
    private options: SyncEngineOptions = {},
  ) {}

  get currentPhase(): SyncPhase { return this.phase; }
  get currentOwner(): OwnerInfo | null { return this.owner; }

  async sync(credential: string, trustedNowS: () => number): Promise<SyncResult> {
    const maxRetries = this.options.maxRetries ?? 3;
    const retryDelay = this.options.retryDelayMs ?? 500;
    let lastError = '';
    for (let attempt = 0; attempt <= maxRetries; attempt++) {
      try {
        return await this.syncOnce(credential, trustedNowS);
      } catch (e) {
        lastError = e instanceof Error ? e.message : String(e);
        this.phase = 'failed';
        if (attempt < maxRetries) {
          await new Promise(r => setTimeout(r, retryDelay * (attempt + 1)));
        }
      }
    }
    return { ok: false, error: lastError, recordsReceived: 0, watermark: 0n };
  }

  private async syncOnce(credential: string, trustedNowS: () => number): Promise<SyncResult> {
    this.phase = 'connecting';
    await this.transport.connect();
    try {
      // Public hello.
      this.phase = 'public-info';
      const helloResp = await this.sendFrame(encodeFrame({
        version: PROTOCOL_VERSION,
        opcode: Opcode.HelloRequest,
        payload: encodeHelloRequest({ clientVer: PROTOCOL_VERSION, caps: 0 }),
      }));
      const helloFrame = decodeFrame(helloResp);
      if (!helloFrame || helloFrame.opcode !== Opcode.HelloPublic) {
        throw new Error('hello failed');
      }
      // Owner (public).
      const ownerResp = await this.sendFrame(encodeFrame({
        version: PROTOCOL_VERSION,
        opcode: Opcode.OwnerRequest,
        payload: new Uint8Array(),
      }));
      const ownerFrame = decodeFrame(ownerResp);
      if (ownerFrame && ownerFrame.opcode === Opcode.OwnerResponse) {
        // OwnerInfo decode is in ble-codec; we re-decode here.
        const { decodeOwnerResponse } = await import('./ble-codec');
        this.owner = decodeOwnerResponse(ownerFrame.payload);
      }

      // Challenge + auth.
      this.phase = 'challenge';
      const chResp = await this.sendFrame(encodeFrame({
        version: PROTOCOL_VERSION,
        opcode: Opcode.ChallengeRequest,
        payload: new Uint8Array(),
      }));
      const chFrame = decodeFrame(chResp);
      if (!chFrame || chFrame.opcode !== Opcode.ChallengeResponse) {
        throw new Error('challenge failed');
      }
      const nonce = chFrame.payload;
      const proof = await this.keys.signChallenge(nonce);

      this.phase = 'authorizing';
      const authResp = await this.sendFrame(encodeFrame({
        version: PROTOCOL_VERSION,
        opcode: Opcode.AuthRequest,
        payload: encodeAuthRequest({ credential, proof }),
      }));
      const authFrame = decodeFrame(authResp);
      if (!authFrame || authFrame.opcode !== Opcode.AuthResponse) {
        throw new Error('auth failed');
      }
      const { decodeAuthResponse } = await import('./ble-codec');
      const auth = decodeAuthResponse(authFrame.payload);
      if (!auth.ok) {
        throw new Error(`auth denied: ${SyncError[auth.error]}`);
      }
      this.phase = 'authorized';

      // Load or create session.
      const nodeId = this.owner?.nodeId ?? 'unknown';
      const incarnation = this.owner?.nodeId ?? 'unknown';
      const existing = await this.store.session(nodeId);
      this.session = {
        nodeId,
        incarnation,
        watermark: existing?.watermark ?? '0',
        phase: 'authorized',
        updatedAt: Date.now(),
      };

      // Status.
      this.phase = 'syncing';
      const stResp = await this.sendFrame(encodeFrame({
        version: PROTOCOL_VERSION,
        opcode: Opcode.StatusRequest,
        payload: new Uint8Array(),
      }));
      const stFrame = decodeFrame(stResp);
      if (!stFrame || stFrame.opcode !== Opcode.StatusResponse) {
        throw new Error('status failed');
      }
      this.status = decodeStatusResponse(stFrame.payload);
      if (!this.status) throw new Error('bad status');

      let totalReceived = 0;
      let cursor = this.status.ackWatermark + 1n;
      const maxBatch = this.options.maxBatchRecords ?? 16;

      // Batch loop: receive pages, persist durably, advance cursor.
      for (;;) {
        const batchResp = await this.sendFrame(encodeFrame({
          version: PROTOCOL_VERSION,
          opcode: Opcode.BatchRequest,
          payload: encodeBatchRequest({ fromSequence: cursor, maxRecords: maxBatch }),
        }));
        const batchFrame = decodeFrame(batchResp);
        if (!batchFrame || batchFrame.opcode !== Opcode.BatchResponse) {
          throw new Error('batch failed');
        }
        const batch = decodeBatchResponse(batchFrame.payload);
        if (!batch) throw new Error('bad batch');

        if (batch.records.length > 0) {
          // Durable persist BEFORE ACK.
          this.phase = 'persisting';
          await this.store.putObservations(batch.records);
          totalReceived += batch.records.length;
        }

        if (!batch.more) break;
        cursor = batch.nextCursor;
      }

      // Compute contiguous watermark from durable store.
      const stored = await this.store.sequences(nodeId, incarnation);
      const base = BigInt(this.session.watermark);
      const watermark = contiguousWatermark(base, stored);

      // ACK only after durable persist.
      if (watermark > base) {
        this.phase = 'acknowledging';
        const ackResp = await this.sendFrame(encodeFrame({
          version: PROTOCOL_VERSION,
          opcode: Opcode.AckRequest,
          payload: encodeAckRequest(watermark),
        }));
        const ackFrame = decodeFrame(ackResp);
        if (!ackFrame || ackFrame.opcode !== Opcode.AckResponse) {
          throw new Error('ack failed');
        }
        const { decodeAckResponse } = await import('./ble-codec');
        const ack = decodeAckResponse(ackFrame.payload);
        if (ack.error !== SyncError.Ok) {
          throw new Error(`ack rejected: ${SyncError[ack.error]}`);
        }
        this.session.watermark = watermark.toString();
        await this.store.saveSession(this.session);
      }

      // Compact (explicit, after ACK).
      this.phase = 'compacting';
      const compactResp = await this.sendFrame(encodeFrame({
        version: PROTOCOL_VERSION,
        opcode: Opcode.CompactRequest,
        payload: new Uint8Array(),
      }));
      const compactFrame = decodeFrame(compactResp);
      if (compactFrame && compactFrame.opcode === Opcode.CompactResponse) {
        // Compaction confirmed; watermark is durable.
      }

      this.phase = 'complete';
      return { ok: true, recordsReceived: totalReceived, watermark };
    } finally {
      await this.transport.disconnect();
    }
  }

  private async sendFrame(frame: Uint8Array): Promise<Uint8Array> {
    await this.transport.write(frame);
    return this.transport.read();
  }
}
