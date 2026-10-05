// @vitest-environment node
import 'fake-indexeddb/auto';
import { beforeAll, beforeEach, describe, expect, test } from 'vitest';
import { SyncDatabase, SyncEngine, SyncStore } from './sync-engine';
import { CollectorDatabase } from '../platform/offline-store';
import { CollectorObservationStore } from './observation-store';
import {
  decodeBatchRequest, decodeFrame, encodeFrame, encodeHelloPublic, encodeOwnerResponse,
  encodeAuthResponse, encodeStatusResponse, encodeBatchResponse, encodeAckResponse,
  encodeCompactResponse, Opcode, SyncError, type BleRecord,
} from './ble-codec';
import type { NodeTransport } from '../platform/node-transport';
import { toBase64Url } from '../platform/device-keys';
import type { TrustedNodeIdentity } from '../platform/node-identity';

const NODE_ID = '44444444-4444-4444-8444-444444444444';
const INCARNATION = '55555555-5555-4555-9555-555555555555';
let nodeKeys: CryptoKeyPair;
let identity: TrustedNodeIdentity;
beforeAll(async () => {
  nodeKeys = await crypto.subtle.generateKey({ name: 'ECDSA', namedCurve: 'P-256' }, true, ['sign', 'verify']);
  const jwk = await crypto.subtle.exportKey('jwk', nodeKeys.publicKey);
  identity = { nodeId: NODE_ID, publicKeyX: jwk.x!, publicKeyY: jwk.y! };
});

function makeRecord(seq: number): BleRecord {
  return {
    nodeId: NODE_ID, incarnation: INCARNATION, sequence: BigInt(seq),
    chipId: `chip-${seq}`, clockStatus: 2, epochMs: 1790899200000n + BigInt(seq),
    monotonicMs: BigInt(seq), bootCounter: 3,
  };
}

class MockTransport implements NodeTransport {
  connected = false;
  lastResponse: Uint8Array = new Uint8Array();
  constructor(private handler: (frame: Uint8Array) => Promise<Uint8Array>) {}
  async connect(): Promise<void> { this.connected = true; }
  async disconnect(): Promise<void> { this.connected = false; }
  async read(): Promise<Uint8Array> { return this.lastResponse; }
  async write(message: Uint8Array): Promise<void> {
    this.lastResponse = await this.handler(message);
  }
}

function nodeHandler(records: BleRecord[], opts: {
  serverVer?: number; claimState?: number; authError?: SyncError; failBatchOnce?: boolean;
  dropConnectionMidBatch?: boolean;
} = {}) {
  let acked = 0n;
  let batchCalls = 0;
  let failedOnce = false;
  const opcodes: Opcode[] = [];
  const handler = async (frame: Uint8Array): Promise<Uint8Array> => {
    const f = decodeFrame(frame);
    if (!f) throw new Error('bad frame');
    opcodes.push(f.opcode);
    switch (f.opcode) {
      case Opcode.HelloRequest:
        return encodeFrame({ version: 1, opcode: Opcode.HelloPublic, payload: encodeHelloPublic({
          serverVer: opts.serverVer ?? 1, caps: 0x1f, nodeId: NODE_ID, incarnation: INCARNATION,
          firmwareVersion: 'test', claimState: opts.claimState ?? 1, clockStatus: 2,
        }) });
      case Opcode.OwnerRequest:
        return encodeFrame({ version: 1, opcode: Opcode.OwnerResponse, payload: encodeOwnerResponse({
          nodeId: NODE_ID, claimState: opts.claimState ?? 1, organizationId: 'org-a',
          organizationSlug: 'org', organizationName: 'Org', publicContact: '',
        }) });
      case Opcode.ChallengeRequest:
        return encodeFrame({ version: 1, opcode: Opcode.ChallengeResponse, payload: new Uint8Array(32) });
      case Opcode.AuthRequest:
        if (opts.authError !== undefined) {
          return encodeFrame({ version: 1, opcode: Opcode.AuthResponse, payload: encodeAuthResponse({
            ok: false, expiresS: 0n, error: opts.authError,
          }) });
        }
        return encodeFrame({ version: 1, opcode: Opcode.AuthResponse, payload: encodeAuthResponse({
          ok: true, expiresS: 1917129600n, error: SyncError.Ok,
        }) });
      case Opcode.NodeProofRequest: {
        const message = new TextEncoder().encode(`MM-NODE-SESSION-v1\n${NODE_ID}\n${toBase64Url(f.payload)}`);
        const sig = new Uint8Array(await crypto.subtle.sign({ name: 'ECDSA', hash: 'SHA-256' }, nodeKeys.privateKey, message));
        return encodeFrame({ version: 1, opcode: Opcode.NodeProofResponse, payload: sig });
      }
      case Opcode.StatusRequest:
        return encodeFrame({ version: 1, opcode: Opcode.StatusResponse, payload: encodeStatusResponse({
          pending: records.length, ackWatermark: acked, storeStatus: 0, clockStatus: 2,
          epochMs: 1790899200000n, nextSequence: records.length ? records[records.length - 1].sequence + 1n : acked + 1n,
        }) });
      case Opcode.BatchRequest: {
        batchCalls++;
        if ((opts.failBatchOnce || opts.dropConnectionMidBatch) && !failedOnce && batchCalls === 2) {
          failedOnce = true;
          throw new Error('BLE-Verbindung abgebrochen');
        }
        const req = decodeBatchRequest(f.payload)!;
        const page = records.filter(r => r.sequence >= req.fromSequence).slice(0, req.maxRecords);
        const more = page.length > 0 && records.some(r => r.sequence > page[page.length - 1].sequence);
        return encodeFrame({ version: 1, opcode: Opcode.BatchResponse, payload: encodeBatchResponse({
          fromSequence: req.fromSequence, more,
          nextCursor: page.length > 0 ? page[page.length - 1].sequence + 1n : req.fromSequence,
          records: page,
        }) });
      }
      case Opcode.AckRequest:
        acked = BigInt(new DataView(f.payload.buffer, f.payload.byteOffset).getBigUint64(0, true));
        return encodeFrame({ version: 1, opcode: Opcode.AckResponse, payload: encodeAckResponse({
          newWatermark: acked, error: SyncError.Ok,
        }) });
      case Opcode.CompactRequest:
        return encodeFrame({ version: 1, opcode: Opcode.CompactResponse, payload: encodeCompactResponse({
          freed: 0, remaining: 0, ackWatermark: acked, error: SyncError.Ok,
        }) });
      default:
        throw new Error('unexpected opcode');
    }
  };
  return { handler, opcodes, getAcked: () => acked, batchCalls: () => batchCalls };
}

const mockKeys = {
  async publicKey() { return { kty: 'EC', crv: 'P-256', x: '', y: '' }; },
  async signChallenge() { return new Uint8Array(64).fill(1); },
};

describe('SyncEngine extended field scenarios (issue #8)', () => {
  let db: SyncDatabase;
  let store: SyncStore;
  beforeEach(() => {
    db = new SyncDatabase(`ext-${Math.random().toString(36).slice(2)}`);
    store = new SyncStore(db);
  });

  test('thousands of observations across multiple batches complete', async () => {
    const records = Array.from({ length: 2000 }, (_, i) => makeRecord(i + 1));
    const node = nodeHandler(records);
    const engine = new SyncEngine(new MockTransport(node.handler), store, mockKeys, identity,
      { maxBatchRecords: 16, maxRetries: 0, retryDelayMs: 0 });
    const result = await engine.sync('cred', () => 1790899200);
    expect(result.ok).toBe(true);
    expect(result.recordsReceived).toBe(2000);
    expect(result.watermark).toBe(2000n);
    expect(await store.sequences(NODE_ID, INCARNATION)).toHaveLength(2000);
  }, 30000);

  test('disconnect mid-batch resumes with fresh challenge and no duplicates', async () => {
    const records = [makeRecord(1), makeRecord(2), makeRecord(3), makeRecord(4)];
    const node = nodeHandler(records, { dropConnectionMidBatch: true });
    const transport = new MockTransport(node.handler);
    const engine = new SyncEngine(transport, store, mockKeys, identity,
      { maxBatchRecords: 1, maxRetries: 2, retryDelayMs: 0 });
    const result = await engine.sync('cred', () => 1790899200);
    expect(result.ok).toBe(true);
    expect(result.watermark).toBe(4n);
    expect(await store.sequences(NODE_ID, INCARNATION)).toHaveLength(4);
    expect(node.opcodes.filter(o => o === Opcode.ChallengeRequest).length).toBeGreaterThanOrEqual(2);
  });

  test('node reboot during sync: watermark durable, resume at ack+1', async () => {
    const records = [makeRecord(1), makeRecord(2)];
    const node = nodeHandler(records, { failBatchOnce: true });
    const engine = new SyncEngine(new MockTransport(node.handler), store, mockKeys, identity,
      { maxBatchRecords: 16, maxRetries: 1, retryDelayMs: 0 });
    const result = await engine.sync('cred', () => 1790899200);
    expect(result.ok).toBe(true);
    expect(node.getAcked()).toBe(2n);
  });

  test('IndexedDB quota failure before ACK sends no ACK', async () => {
    const records = [makeRecord(1)];
    const node = nodeHandler(records);
    const failingStore = {
      putObservations: async () => {
        const e = new Error('QuotaExceededError: quota');
        e.name = 'QuotaExceededError';
        throw e;
      },
      sequences: async () => [] as bigint[],
    };
    const engine = new SyncEngine(new MockTransport(node.handler), failingStore, mockKeys, identity, { maxRetries: 0 });
    const result = await engine.sync('cred', () => 1790899200);
    expect(result.ok).toBe(false);
    expect(result.error ?? '').toContain('storage');
    expect(node.opcodes).not.toContain(Opcode.AckRequest);
  });

  test('foreign organization: no protected observations requested', async () => {
    const node = nodeHandler([makeRecord(1)], { authError: SyncError.ForbiddenForeign });
    const engine = new SyncEngine(new MockTransport(node.handler), store, mockKeys, identity, { maxRetries: 0 });
    const result = await engine.sync('cred', () => 1790899200);
    expect(result.ok).toBe(false);
    expect((result.error ?? '').split(':')[0]).toBe('foreign');
    expect(node.opcodes).not.toContain(Opcode.StatusRequest);
    expect(node.opcodes).not.toContain(Opcode.BatchRequest);
    expect(engine.currentOwner?.organizationName).toBe('Org');
  });

  test('incompatible protocol version fails closed with clear message', async () => {
    const node = nodeHandler([], { serverVer: 2 });
    const engine = new SyncEngine(new MockTransport(node.handler), store, mockKeys, identity, { maxRetries: 0 });
    const result = await engine.sync('cred', () => 1790899200);
    expect(result.ok).toBe(false);
    expect((result.error ?? '').split(':')[0]).toBe('incompatible');
  });

  test('UNCLAIMED node never reaches authorization', async () => {
    const node = nodeHandler([], { claimState: 0 });
    const engine = new SyncEngine(new MockTransport(node.handler), store, mockKeys, identity, { maxRetries: 0 });
    const result = await engine.sync('cred', () => 1790899200);
    expect(result.ok).toBe(false);
    expect((result.error ?? '').split(':')[0]).toBe('unclaimed');
    expect(node.opcodes).not.toContain(Opcode.AuthRequest);
  });

  test('empty terminal batch cannot hide pending records and report Fertig', async () => {
    const node = nodeHandler([makeRecord(1)]);
    const transport = new MockTransport(async frame => {
      const f = decodeFrame(frame)!;
      if (f.opcode === Opcode.BatchRequest) {
        return encodeFrame({ version: 1, opcode: Opcode.BatchResponse, payload: encodeBatchResponse({
          fromSequence: 1n, nextCursor: 1n, more: false, records: [],
        }) });
      }
      return node.handler(frame);
    });
    const result = await new SyncEngine(transport, store, mockKeys, identity, { maxRetries: 0 }).sync('cred', () => 1790899200);
    expect(result.ok).toBe(false);
    expect(result.error).toContain('incomplete');
    expect(node.getAcked()).toBe(0n);
  });

  test('node identity changing after discovery never receives the credential', async () => {
    const node = nodeHandler([makeRecord(1)]);
    const result = await new SyncEngine(new MockTransport(node.handler), store, mockKeys,
      { ...identity, nodeId: '66666666-6666-4666-8666-666666666666' }, { maxRetries: 0 }).sync('cred', () => 1790899200);
    expect(result.ok).toBe(false);
    expect(node.opcodes).not.toContain(Opcode.AuthRequest);
  });

  test('session metadata failure after durable ACK does not fail the field visit', async () => {
    const node = nodeHandler([makeRecord(1)]);
    store.saveSession = async () => { throw new Error('metadata quota'); };
    const result = await new SyncEngine(new MockTransport(node.handler), store, mockKeys, identity,
      { maxRetries: 0 }).sync('cred', () => 1790899200);
    expect(result.ok).toBe(true);
    expect(node.getAcked()).toBe(1n);
  });

  test.each([null, 'org-b'])('cached row bound to %s cannot bridge an authenticated ACK gap', async cachedOrganization => {
    const collectorDb = new CollectorDatabase(`ack-gap-${Math.random()}`);
    const collectorStore = new CollectorObservationStore(collectorDb);
    try {
      await collectorStore.putObservations([makeRecord(2)], cachedOrganization);
      const node = nodeHandler([makeRecord(1), makeRecord(3)]);
      const result = await new SyncEngine(new MockTransport(node.handler), collectorStore, mockKeys, identity,
        { maxRetries: 0 }).sync('cred', () => 1790899200, 'org-a');
      expect(result.ok).toBe(false);
      expect(result.error).toContain('incomplete');
      expect(node.getAcked()).toBe(1n);
      expect(await collectorStore.pendingUploads('org-a')).toHaveLength(2);
    } finally { collectorDb.close(); await collectorDb.delete(); }
  });

  test('lost compaction response after durable ACK leaves the visit complete with a warning', async () => {
    const node = nodeHandler([makeRecord(1)]);
    const transport = new MockTransport(async frame => {
      if (decodeFrame(frame)?.opcode === Opcode.CompactRequest) throw new Error('BLE disconnected after ACK');
      return node.handler(frame);
    });
    const result = await new SyncEngine(transport, store, mockKeys, identity,
      { maxRetries: 3, retryDelayMs: 0 }).sync('cred', () => 1790899200);
    expect(result.ok).toBe(true);
    expect(result.watermark).toBe(1n);
    expect(result.maintenanceWarning).toContain('Speicherbereinigung');
    expect(node.getAcked()).toBe(1n);
    expect(node.opcodes.filter(opcode => opcode === Opcode.ChallengeRequest)).toHaveLength(1);
    expect(await store.sequences(NODE_ID, INCARNATION)).toEqual([1n]);
    expect(transport.connected).toBe(false);
  });
});
