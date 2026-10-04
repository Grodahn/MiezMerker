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
  type BleRecord, type OwnerInfo, type StatusResponse,
  decodeAckResponse, decodeAuthResponse, decodeBatchResponse, decodeCompactResponse,
  decodeFrame, decodeHelloPublic, decodeNodeProofResponse, decodeOwnerResponse,
  decodeStatusResponse, encodeFrame, encodeAckRequest, encodeAuthRequest,
  encodeBatchRequest, encodeHelloRequest, encodeNodeProofRequest,
  CAP_ALL_V1, Opcode, PROTOCOL_VERSION, SyncError,
} from './ble-codec';
import type { NodeTransport } from '../platform/node-transport';
import type { AppDeviceKeys } from '../platform/device-keys';
import { beginNodeAuthentication, type TrustedNodeIdentity } from '../platform/node-identity';

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

// Typed sync failures so the /sync UI (issue #8) can distinguish UNCLAIMED,
// foreign organization, incompatible protocol, authorization, storage/quota,
// connection loss and incomplete (gap) syncs. The owner hint is attached when
// public info was already read, so foreign/UNCLAIMED branches never probe
// protected data.
export type SyncFailureKind =
  | 'unclaimed'
  | 'foreign'
  | 'incompatible'
  | 'unauthorized'
  | 'storage'
  | 'connection'
  | 'protocol'
  | 'incomplete';

export class SyncFailedError extends Error {
  constructor(
    readonly kind: SyncFailureKind,
    message: string,
    readonly owner: OwnerInfo | null = null,
    readonly cause?: unknown,
  ) {
    super(message);
    this.name = 'SyncFailedError';
  }
}

// Minimal durable persistence consumed by the engine. Both SyncStore (tests)
// and CollectorObservationStore (production) implement it; the second
// organizationId argument is optional so legacy stores stay compatible.
export interface SyncPersistence {
  putObservations(records: BleRecord[], organizationId?: string | null): Promise<void>;
  sequences(nodeId: string, incarnation: string): Promise<bigint[]>;
  session?(nodeId: string): Promise<{ nodeId: string; incarnation: string; watermark: string; phase: string; updatedAt: number } | undefined | null>;
  saveSession?(session: { nodeId: string; incarnation: string; watermark: string; phase: string; updatedAt: number }): Promise<void>;
}

// Full sync engine: connect → auth → batch → persist → ack → compact.
// Retries after disconnect with fresh challenge; lost ACKs are idempotent.
export class SyncEngine {
  private phase: SyncPhase = 'idle';
  private session: SyncSession | null = null;
  private owner: OwnerInfo | null = null;
  private status: StatusResponse | null = null;
  private received = 0;

  constructor(
    private transport: NodeTransport,
    private store: SyncPersistence,
    private keys: AppDeviceKeys,
    private identity: TrustedNodeIdentity,
    private options: SyncEngineOptions = {},
  ) {}

  get currentPhase(): SyncPhase { return this.phase; }
  get currentOwner(): OwnerInfo | null { return this.owner; }
  get currentReceived(): number { return this.received; }

  async sync(credential: string, trustedNowS: () => number, organizationId?: string | null): Promise<SyncResult> {
    const maxRetries = this.options.maxRetries ?? 3;
    const retryDelay = this.options.retryDelayMs ?? 500;
    let lastError = '';
    let lastKind: SyncFailureKind | null = null;
    for (let attempt = 0; attempt <= maxRetries; attempt++) {
      try {
        return await this.syncOnce(credential, trustedNowS, organizationId);
      } catch (e) {
        lastError = e instanceof Error ? e.message : String(e);
        lastKind = e instanceof SyncFailedError ? e.kind : null;
        this.phase = 'failed';
        // Never retry states that cannot succeed without user action:
        // UNCLAIMED/foreign/incompatible/auth/storage. Connection/protocol
        // errors (incl. lost ACK, mid-batch abort, reboot) retry with a fresh
        // challenge as usual.
        if (lastKind === 'unclaimed' || lastKind === 'foreign' || lastKind === 'incompatible' ||
            lastKind === 'unauthorized' || lastKind === 'storage' || lastKind === 'incomplete') {
          break;
        }
        if (attempt < maxRetries) {
          await new Promise(r => setTimeout(r, retryDelay * (attempt + 1)));
        }
      }
    }
    const error = lastKind ? `${lastKind}: ${lastError}` : lastError;
    return { ok: false, error, recordsReceived: 0, watermark: 0n };
  }

  private async syncOnce(credential: string, trustedNowS: () => number, organizationId?: string | null): Promise<SyncResult> {
    this.owner = null;
    this.status = null;
    this.session = null;
    this.phase = 'connecting';
    try {
      await this.transport.connect();
    } catch (e) {
      throw new SyncFailedError('connection', e instanceof Error ? e.message : 'BLE-Verbindung fehlgeschlagen.', null, e);
    }
    try {
      // Public hello.
      this.phase = 'public-info';
      const helloResp = await this.sendFrame(encodeFrame({
        version: PROTOCOL_VERSION,
        opcode: Opcode.HelloRequest,
        payload: encodeHelloRequest({ clientVer: PROTOCOL_VERSION, caps: CAP_ALL_V1 }),
      }));
      const helloFrame = decodeFrame(helloResp);
      if (!helloFrame || helloFrame.opcode !== Opcode.HelloPublic) {
        throw new SyncFailedError('protocol', 'hello failed', null);
      }
      const hello = decodeHelloPublic(helloFrame.payload);
      if (!hello) {
        throw new SyncFailedError('protocol', 'hello failed', null);
      }
      if (hello.claimState !== 1) {
        throw new SyncFailedError('unclaimed', 'Node ist UNCLAIMED. Nur ADMIN kann ihn im physischen Claim-Modus claimen.', null);
      }
      if (hello.serverVer !== PROTOCOL_VERSION || (hello.caps & CAP_ALL_V1) !== CAP_ALL_V1) {
        throw new SyncFailedError('incompatible',
          `Inkompatible Protokoll-Version (Node: v${hello.serverVer}, caps 0x${hello.caps.toString(16)}).`, null);
      }
      if (hello.nodeId !== this.identity.nodeId.toLowerCase()) {
        throw new SyncFailedError('protocol', 'unsupported or unexpected node identity', null);
      }
      // Owner (public).
      const ownerResp = await this.sendFrame(encodeFrame({
        version: PROTOCOL_VERSION,
        opcode: Opcode.OwnerRequest,
        payload: new Uint8Array(),
      }));
      const ownerFrame = decodeFrame(ownerResp);
      if (!ownerFrame || ownerFrame.opcode !== Opcode.OwnerResponse) {
        throw new SyncFailedError('protocol', 'owner failed', null);
      }
      this.owner = decodeOwnerResponse(ownerFrame.payload);
      if (!this.owner || this.owner.nodeId !== hello.nodeId || this.owner.claimState !== 1) {
        throw new SyncFailedError('protocol', 'bad owner identity', this.owner);
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
      if (nonce.length !== 32) throw new Error('bad challenge');
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
      const auth = decodeAuthResponse(authFrame.payload);
      if (!auth || !auth.ok || auth.error !== SyncError.Ok ||
          (auth.expiresS !== 0n && BigInt(trustedNowS()) >= auth.expiresS)) {
        const code = auth?.error;
        // FORBIDDEN_FOREIGN stays unauthorized here; the orchestrator compares
        // the public owner hint against the active organization and shows only
        // the allowed public metadata without probing protected data.
        const kind: SyncFailureKind = code === SyncError.ForbiddenForeign ? 'foreign' : 'unauthorized';
        const message = code === SyncError.ForbiddenForeign
          ? 'Node gehört einer anderen Organisation. Nur öffentliche Owner-Metadaten werden angezeigt.'
          : 'Autorisierung abgelehnt oder Credential abgelaufen.';
        throw new SyncFailedError(kind, message, this.owner);
      }
      // Trust comes from the cached backend identity, never the BLE peer's key.
      const nodeAuth = await beginNodeAuthentication(this.identity);
      const proofFrame = decodeFrame(await this.sendFrame(encodeFrame({
        version: PROTOCOL_VERSION, opcode: Opcode.NodeProofRequest,
        payload: encodeNodeProofRequest(nodeAuth.challenge),
      })));
      const signature = proofFrame?.opcode === Opcode.NodeProofResponse
        ? decodeNodeProofResponse(proofFrame.payload) : null;
      if (!signature || !await nodeAuth.verify(signature)) {
        throw new SyncFailedError('unauthorized', 'Node-Authentizität konnte nicht bestätigt werden (Fake-Node möglich).', this.owner);
      }
      this.phase = 'authorized';

      // Load or create session.
      const { nodeId, incarnation } = hello;
      this.session = {
        nodeId,
        incarnation,
        watermark: '0',
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
      if (!this.status || this.status.ackWatermark >= this.status.nextSequence) {
        throw new Error('bad status');
      }
      // The authenticated node's durable ACK is the shared baseline. Another
      // collector may have acknowledged records absent from this local store.
      const base = this.status.ackWatermark;
      this.session.watermark = base.toString();

      let totalReceived = 0;
      let cursor = this.status.ackWatermark + 1n;
      const maxBatch = this.options.maxBatchRecords ?? 16;
      if (!Number.isInteger(maxBatch) || maxBatch < 1 || maxBatch > 64) {
        throw new Error('invalid batch size');
      }

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
        if (!batch || batch.fromSequence !== cursor || batch.records.length > maxBatch) {
          throw new Error('bad batch');
        }
        let previous = cursor - 1n;
        for (const record of batch.records) {
          if (record.nodeId !== nodeId || record.incarnation !== incarnation ||
              record.sequence <= previous) throw new Error('unexpected batch record');
          previous = record.sequence;
        }
        const expectedCursor = batch.records.length ? previous + 1n : cursor;
        if (batch.nextCursor !== expectedCursor ||
            (batch.more && (batch.records.length === 0 || batch.nextCursor <= cursor))) {
          throw new Error('non-progressing or invalid batch cursor');
        }

        if (batch.records.length > 0) {
          // Durable persist BEFORE ACK.
          this.phase = 'persisting';
          try {
            await this.store.putObservations(batch.records, organizationId);
          } catch (e) {
            throw new SyncFailedError('storage',
              e instanceof Error ? e.message : 'Lokale Speicherung fehlgeschlagen.', this.owner, e);
          }
          totalReceived += batch.records.length;
          this.received = totalReceived;
        }

        if (!batch.more) break;
        cursor = batch.nextCursor;
      }

      // Compute contiguous watermark from durable store.
      const stored = await this.store.sequences(nodeId, incarnation);
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
        const ack = decodeAckResponse(ackFrame.payload);
        if (!ack || ack.error !== SyncError.Ok || ack.newWatermark !== watermark) {
          throw new Error('ack rejected or mismatched');
        }
      }
      this.session.watermark = watermark.toString();
      await this.store.saveSession?.(this.session);

      // Compact (explicit, after ACK).
      this.phase = 'compacting';
      const compactResp = await this.sendFrame(encodeFrame({
        version: PROTOCOL_VERSION,
        opcode: Opcode.CompactRequest,
        payload: new Uint8Array(),
      }));
      const compactFrame = decodeFrame(compactResp);
      const compact = compactFrame?.opcode === Opcode.CompactResponse
        ? decodeCompactResponse(compactFrame.payload) : null;
      if (!compact || compact.error !== SyncError.Ok || compact.ackWatermark !== watermark) {
        throw new Error('compaction failed');
      }
      if (stored.some(sequence => sequence > watermark)) {
        throw new SyncFailedError('incomplete',
          'sequence gap remains: Sequenzlücke blockiert ACK (spätere Records bleiben unquittiert auf dem Node).', this.owner);
      }

      this.phase = 'complete';
      return { ok: true, recordsReceived: totalReceived, watermark };
    } finally {
      await this.transport.disconnect();
    }
  }

  private async sendFrame(frame: Uint8Array): Promise<Uint8Array> {
    let response: Uint8Array;
    try {
      await this.transport.write(frame);
      response = await this.transport.read();
    } catch (e) {
      throw new SyncFailedError('connection',
        e instanceof Error ? e.message : 'BLE-Verbindung abgebrochen.', this.owner, e);
    }
    const decoded = decodeFrame(response);
    if (!decoded) throw new SyncFailedError('protocol', 'Ungültige Node-Antwort.', this.owner);
    if (decoded.version !== PROTOCOL_VERSION) {
      throw new SyncFailedError('incompatible',
        `Inkompatible Protokoll-Version in Node-Antwort (v${decoded.version}).`, this.owner);
    }
    if (decoded.opcode === Opcode.Error) {
      throw new SyncFailedError('protocol', 'Node meldet Protokollfehler.', this.owner);
    }
    return response;
  }
}
