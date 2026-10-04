// @vitest-environment node
import 'fake-indexeddb/auto';
import { beforeAll, beforeEach, describe, expect, test } from 'vitest';
import {
  contiguousWatermark, SyncDatabase, SyncEngine, SyncStore,
} from './sync-engine';
import {
  decodeBatchRequest, decodeAckRequest, decodeFrame,
  encodeFrame, encodeHelloPublic, encodeOwnerResponse, encodeChallengeResponse,
  encodeAuthResponse, encodeStatusResponse, encodeBatchResponse, encodeAckResponse,
  encodeCompactResponse, encodeRecord, Opcode, PROTOCOL_VERSION, SyncError,
  type BleRecord,
} from './ble-codec';
import type { NodeTransport } from '../platform/node-transport';
import type { AppDeviceKeys } from '../platform/device-keys';
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

function makeRecord(seq: number, chip: string): BleRecord {
  return {
    nodeId: NODE_ID, incarnation: INCARNATION,
    sequence: BigInt(seq), chipId: chip,
    clockStatus: 2, epochMs: 1790899200000n + BigInt(seq * 1000),
    monotonicMs: BigInt(seq * 1000), bootCounter: 7,
  };
}

// Mock transport that simulates a node: responds to frames with canned data.
class MockNodeTransport implements NodeTransport {
  connected = false;
  private handler: (frame: Uint8Array) => Uint8Array | Promise<Uint8Array>;
  constructor(handler: (frame: Uint8Array) => Uint8Array | Promise<Uint8Array>) { this.handler = handler; }
  async connect() { this.connected = true; }
  async disconnect() { this.connected = false; }
  async read(): Promise<Uint8Array> { return this.lastResponse; }
  async write(message: Uint8Array): Promise<void> {
    const response = await this.handler(message);
    this.lastResponse = response;
  }
  lastResponse: Uint8Array = new Uint8Array();
}

// Helper: build a mock node that serves 3 records then completes.
function makeMockNode(records: BleRecord[], opts: {
  failAuth?: boolean; failAck?: boolean; fakeNode?: boolean; initialAck?: bigint;
  loseAckOnce?: boolean; failCompact?: boolean;
} = {}) {
  let acked = opts.initialAck ?? 0n;
  let lostAck = false;
  let batchCalls = 0;
  const opcodes: Opcode[] = [];
  const handler = async (frame: Uint8Array): Promise<Uint8Array> => {
    const f = decodeFrame(frame);
    if (!f) return encodeFrame({ version: 1, opcode: 0xff, payload: new Uint8Array() });
    opcodes.push(f.opcode);
    switch (f.opcode) {
      case Opcode.HelloRequest:
        return encodeFrame({ version: 1, opcode: Opcode.HelloPublic, payload: encodeHelloPublic({
          serverVer: 1, caps: 0x1f, nodeId: NODE_ID, incarnation: INCARNATION,
          firmwareVersion: 'test-1.0.0', claimState: 1, clockStatus: 2,
        })});
      case Opcode.OwnerRequest:
        return encodeFrame({ version: 1, opcode: Opcode.OwnerResponse, payload: encodeOwnerResponse({
          nodeId: NODE_ID, claimState: 1,
          organizationId: '22222222-2222-4222-8222-222222222222',
          organizationSlug: 'test-org', organizationName: 'Test Org', publicContact: '',
        })});
      case Opcode.ChallengeRequest:
        return encodeFrame({ version: 1, opcode: Opcode.ChallengeResponse, payload: new Uint8Array(32) });
      case Opcode.AuthRequest:
        if (opts.failAuth) {
          return encodeFrame({ version: 1, opcode: Opcode.AuthResponse, payload: encodeAuthResponse({
            ok: false, expiresS: 0n, error: SyncError.InvalidCredential,
          })});
        }
        return encodeFrame({ version: 1, opcode: Opcode.AuthResponse, payload: encodeAuthResponse({
          ok: true, expiresS: 1917129600n, error: SyncError.Ok,
        })});
      case Opcode.StatusRequest:
        return encodeFrame({ version: 1, opcode: Opcode.StatusResponse, payload: encodeStatusResponse({
          pending: records.length, ackWatermark: acked, storeStatus: 0, clockStatus: 2,
          epochMs: 1790899200000n, nextSequence: records.length ? records[records.length - 1].sequence + 1n : acked + 1n,
        })});
      case Opcode.NodeProofRequest: {
        const message = new TextEncoder().encode(`MM-NODE-SESSION-v1\n${NODE_ID}\n${toBase64Url(f.payload)}`);
        const signature = opts.fakeNode ? new Uint8Array(64) : new Uint8Array(
          await crypto.subtle.sign({ name: 'ECDSA', hash: 'SHA-256' }, nodeKeys.privateKey, message));
        return encodeFrame({ version: 1, opcode: Opcode.NodeProofResponse, payload: signature });
      }
      case Opcode.BatchRequest: {
        batchCalls++;
        const req = decodeBatchRequest(f.payload);
        if (!req) return encodeFrame({ version: 1, opcode: 0xff, payload: new Uint8Array() });
        const page = records.filter(r => r.sequence >= req.fromSequence).slice(0, req.maxRecords);
        const more = page.length > 0 && records.some(r => r.sequence > page[page.length - 1].sequence);
        return encodeFrame({ version: 1, opcode: Opcode.BatchResponse, payload: encodeBatchResponse({
          fromSequence: req.fromSequence, more, nextCursor: page.length > 0
            ? page[page.length - 1].sequence + 1n : req.fromSequence,
          records: page,
        })});
      }
      case Opcode.AckRequest: {
        const w = decodeAckRequest(f.payload);
        if (w === null) return encodeFrame({ version: 1, opcode: 0xff, payload: new Uint8Array() });
        if (opts.failAck) {
          return encodeFrame({ version: 1, opcode: Opcode.AckResponse, payload: encodeAckResponse({
            newWatermark: acked, error: SyncError.Internal,
          })});
        }
        acked = w;
        if (opts.loseAckOnce && !lostAck) {
          lostAck = true;
          throw new Error('link lost after durable ACK');
        }
        return encodeFrame({ version: 1, opcode: Opcode.AckResponse, payload: encodeAckResponse({
          newWatermark: acked, error: SyncError.Ok,
        })});
      }
      case Opcode.CompactRequest:
        return encodeFrame({ version: 1, opcode: Opcode.CompactResponse, payload: encodeCompactResponse({
          freed: Number(acked), remaining: 0, ackWatermark: acked,
          error: opts.failCompact ? SyncError.Internal : SyncError.Ok,
        })});
      default:
        return encodeFrame({ version: 1, opcode: 0xff, payload: new Uint8Array() });
    }
  };
  return { handler, opcodes, getAcked: () => acked, getBatchCalls: () => batchCalls };
}

const mockKeys: AppDeviceKeys = {
  async publicKey() { return { kty: 'EC', crv: 'P-256', x: '', y: '' }; },
  async signChallenge(challenge: Uint8Array) {
    return new Uint8Array(64).fill(0xaa);
  },
};

describe('contiguousWatermark', () => {
  test('contiguous prefix', () => {
    expect(contiguousWatermark(100n, [101n, 102n, 103n])).toBe(103n);
  });
  test('gap blocks watermark', () => {
    expect(contiguousWatermark(100n, [100n, 101n, 103n])).toBe(101n);
  });
  test('empty stored', () => {
    expect(contiguousWatermark(100n, [])).toBe(100n);
  });
  test('duplicates harmless', () => {
    expect(contiguousWatermark(0n, [1n, 1n, 2n])).toBe(2n);
  });
  test('unsorted input', () => {
    expect(contiguousWatermark(5n, [7n, 6n])).toBe(7n);
  });
});

describe('SyncEngine', () => {
  let db: SyncDatabase;
  let store: SyncStore;

  beforeEach(async () => {
    db = new SyncDatabase(`test-${Math.random().toString(36).slice(2)}`);
    store = new SyncStore(db);
  });

  test('full sync: persist before ack, compact after', async () => {
    const records = [makeRecord(1, 'chip-a'), makeRecord(2, 'chip-b'), makeRecord(3, 'chip-c')];
    const node = makeMockNode(records);
    const transport = new MockNodeTransport(node.handler);
    const engine = new SyncEngine(transport, store, mockKeys, identity);
    const result = await engine.sync('valid-credential', () => 1790899200);
    expect(result.ok).toBe(true);
    expect(result.recordsReceived).toBe(3);
    expect(result.watermark).toBe(3n);
    // Durable store has all 3.
    const stored = await store.sequences(NODE_ID, INCARNATION);
    expect(stored).toHaveLength(3);
    // Session watermark persisted.
    const session = await store.session(NODE_ID);
    expect(session?.watermark).toBe('3');
  });

  test('idempotent retransmission: same records do not duplicate', async () => {
    const records = [makeRecord(1, 'chip-a'), makeRecord(2, 'chip-b')];
    const node = makeMockNode(records);
    const transport = new MockNodeTransport(node.handler);
    const engine = new SyncEngine(transport, store, mockKeys, identity);
    await engine.sync('valid-credential', () => 1790899200);
    // Second sync: same records re-served, no duplicates.
    const engine2 = new SyncEngine(transport, store, mockKeys, identity);
    const result2 = await engine2.sync('valid-credential', () => 1790899200);
    expect(result2.ok).toBe(true);
    const stored = await store.sequences(NODE_ID, INCARNATION);
    expect(stored).toHaveLength(2);
  });

  test('gap blocks ack: missing sequence prevents watermark advance', async () => {
    // Simulate: records 1 and 3 stored, 2 missing.
    await store.putObservations([makeRecord(1, 'chip-a'), makeRecord(3, 'chip-c')]);
    const stored = await store.sequences(NODE_ID, INCARNATION);
    const wm = contiguousWatermark(0n, stored);
    expect(wm).toBe(1n); // Cannot skip 2.
  });

  test('auth failure: no data persisted', async () => {
    const node = makeMockNode([], { failAuth: true });
    const transport = new MockNodeTransport(node.handler);
    const engine = new SyncEngine(transport, store, mockKeys, identity, { maxRetries: 0 });
    const result = await engine.sync('bad-credential', () => 1790899200);
    expect(result.ok).toBe(false);
    const stored = await store.sequences(NODE_ID, INCARNATION);
    expect(stored).toHaveLength(0);
  });

  test('retry after disconnect: fresh challenge, resume at watermark', async () => {
    const records = [makeRecord(1, 'chip-a'), makeRecord(2, 'chip-b')];
    const node = makeMockNode(records, { loseAckOnce: true });
    let connectCount = 0;
    const transport = new MockNodeTransport(node.handler);
    const origConnect = transport.connect.bind(transport);
    transport.connect = async () => { connectCount++; await origConnect(); };
    const engine = new SyncEngine(transport, store, mockKeys, identity, { maxRetries: 1, retryDelayMs: 0 });
    const result = await engine.sync('valid-credential', () => 1790899200);
    expect(result.ok).toBe(true);
    expect(connectCount).toBe(2);
    expect(node.opcodes.filter(op => op === Opcode.ChallengeRequest)).toHaveLength(2);
    expect((await store.session(NODE_ID))?.incarnation).toBe(INCARNATION);
    expect((await store.session(NODE_ID))?.watermark).toBe('2');
  });

  test('ack failure: watermark not advanced, retry re-sends', async () => {
    const records = [makeRecord(1, 'chip-a')];
    const node = makeMockNode(records, { failAck: true });
    const transport = new MockNodeTransport(node.handler);
    const engine = new SyncEngine(transport, store, mockKeys, identity, { maxRetries: 0 });
    const result = await engine.sync('valid-credential', () => 1790899200);
    expect(result.ok).toBe(false);
    // Records are durably persisted even though ACK failed.
    const stored = await store.sequences(NODE_ID, INCARNATION);
    expect(stored).toHaveLength(1);
  });

  test('conflicting retransmission rejected', async () => {
    await store.putObservations([makeRecord(1, 'chip-a')]);
    // Re-put same sequence with different chip → conflict.
    await expect(store.putObservations([makeRecord(1, 'chip-different')])).rejects.toThrow('Conflicting');
  });

  test('fake node cannot read status, persist records or ACK', async () => {
    const node = makeMockNode([makeRecord(1, 'chip-a')], { fakeNode: true });
    const engine = new SyncEngine(new MockNodeTransport(node.handler), store, mockKeys, identity, { maxRetries: 0 });
    expect((await engine.sync('credential', () => 1790899200)).ok).toBe(false);
    expect(node.opcodes).not.toContain(Opcode.StatusRequest);
    expect(node.opcodes).not.toContain(Opcode.AckRequest);
    expect(await store.sequences(NODE_ID, INCARNATION)).toEqual([]);
  });

  test('another collector ACK supplies the baseline for a fresh local database', async () => {
    const node = makeMockNode([makeRecord(101, 'chip-a')], { initialAck: 100n });
    const engine = new SyncEngine(new MockNodeTransport(node.handler), store, mockKeys, identity, { maxRetries: 0 });
    expect((await engine.sync('credential', () => 1790899200)).watermark).toBe(101n);
    expect(node.getAcked()).toBe(101n);
  });

  test('gap leaves later data unacknowledged and reports incomplete sync', async () => {
    const node = makeMockNode([makeRecord(1, 'chip-a'), makeRecord(3, 'chip-c')]);
    const engine = new SyncEngine(new MockNodeTransport(node.handler), store, mockKeys, identity, { maxRetries: 0 });
    const result = await engine.sync('credential', () => 1790899200);
    expect(result.ok).toBe(false);
    expect(result.error).toContain('sequence gap');
    expect(node.getAcked()).toBe(1n);
    expect(await store.sequences(NODE_ID, INCARNATION)).toEqual([1n, 3n]);
  });

  test('failed durable persistence sends no ACK or compact', async () => {
    const node = makeMockNode([makeRecord(1, 'changed-chip')]);
    await store.putObservations([makeRecord(1, 'original-chip')]);
    const engine = new SyncEngine(new MockNodeTransport(node.handler), store, mockKeys, identity, { maxRetries: 0 });
    expect((await engine.sync('credential', () => 1790899200)).ok).toBe(false);
    expect(node.opcodes).not.toContain(Opcode.AckRequest);
    expect(node.opcodes).not.toContain(Opcode.CompactRequest);
  });

  test.each(['version', 'cursor', 'identity', 'ack', 'compact'])('rejects invalid %s response', async kind => {
    const node = makeMockNode([makeRecord(1, 'chip-a')], { failCompact: kind === 'compact' });
    const transport = new MockNodeTransport(async request => {
      const response = await node.handler(request);
      const frame = decodeFrame(response)!;
      if (kind === 'version') frame.version = 2;
      if (kind === 'cursor' && frame.opcode === Opcode.BatchResponse) {
        frame.payload = encodeBatchResponse({ fromSequence: 1n, more: true, nextCursor: 1n, records: [] });
      }
      if (kind === 'identity' && frame.opcode === Opcode.BatchResponse) {
        frame.payload = encodeBatchResponse({ fromSequence: 1n, more: false, nextCursor: 2n,
          records: [{ ...makeRecord(1, 'chip-a'), incarnation: NODE_ID }] });
      }
      if (kind === 'ack' && frame.opcode === Opcode.AckResponse) {
        frame.payload = encodeAckResponse({ newWatermark: 0n, error: SyncError.Ok });
      }
      return encodeFrame(frame);
    });
    const engine = new SyncEngine(transport, store, mockKeys, identity, { maxRetries: 0 });
    expect((await engine.sync('credential', () => 1790899200)).ok).toBe(false);
    expect(transport.connected).toBe(false);
  });
});
