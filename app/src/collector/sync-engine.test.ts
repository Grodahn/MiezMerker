// @vitest-environment node
import 'fake-indexeddb/auto';
import { beforeAll, beforeEach, describe, expect, test, vi } from 'vitest';
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
import { TimeBootstrapUnavailableError } from './registration-time';

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
  metadata?: boolean; metadataRejected?: boolean;
  failAuth?: boolean; failAck?: boolean; fakeNode?: boolean; initialAck?: bigint;
  loseAckOnce?: boolean; failCompact?: boolean; timeEvidence?: boolean; timeUsable?: boolean;
  alphaClock?: boolean; phoneTime?: boolean; snapshotReboot?: boolean; snapshotInterrupted?: boolean;
} = {}) {
  let acked = opts.initialAck ?? 0n;
  let lostAck = false;
  let log = [...records];
  let batchCalls = 0;
  let snapshots = 0;
  const opcodes: Opcode[] = [];
  const handler = async (frame: Uint8Array): Promise<Uint8Array> => {
    const f = decodeFrame(frame);
    if (!f) return encodeFrame({ version: 1, opcode: 0xff, payload: new Uint8Array() });
    opcodes.push(f.opcode);
    switch (f.opcode) {
      case Opcode.HelloRequest:
        return encodeFrame({ version: 1, opcode: Opcode.HelloPublic, payload: encodeHelloPublic({
          serverVer: 1, caps: (opts.timeEvidence ? 0x3f : 0x1f) | (opts.phoneTime ? 0x40 : 0) | (opts.alphaClock ? 0x80 : 0) | (opts.metadata ? 0x100 : 0), nodeId: NODE_ID, incarnation: INCARNATION,
          firmwareVersion: 'test-1.0.0', claimState: 1, clockStatus: 2,
        })});
      case Opcode.MetadataRequest:
        return encodeFrame({ version: 1, opcode: opts.metadataRejected ? Opcode.Error : Opcode.MetadataResponse,
          payload: new Uint8Array([0]) });
      case Opcode.AlphaPhoneClockRequest:
        return encodeFrame({ version: 1, opcode: Opcode.AlphaPhoneClockResponse, payload: new Uint8Array([0]) });
      case Opcode.ClockSnapshotRequest: {
        snapshots++;
        if (opts.snapshotInterrupted && snapshots === 2) throw new Error('snapshot disconnected');
        const p = new Uint8Array(45); p[0] = 1;
        p.set(NODE_ID.replaceAll('-', '').match(/../g)!.map(h => parseInt(h, 16)), 1);
        p.set(INCARNATION.replaceAll('-', '').match(/../g)!.map(h => parseInt(h, 16)), 17);
        const v = new DataView(p.buffer); v.setUint32(33, opts.snapshotReboot && snapshots === 2 ? 8 : 7, true); v.setBigUint64(37, BigInt(1000 + snapshots), true);
        return encodeFrame({ version: 1, opcode: Opcode.ClockSnapshotResponse, payload: p });
      }
      case Opcode.SecurityTimeStatusRequest:
        return encodeFrame({ version: 1, opcode: Opcode.SecurityTimeStatusResponse, payload: new Uint8Array([opts.timeUsable ? 1 : 0]) });
      case Opcode.TimeEvidenceRequest: {
        const p = f.payload, v = new DataView(p.buffer, p.byteOffset, p.byteLength);
        const json = p[0] === 0 && v.getUint32(1, true) === 0 ? JSON.stringify({ boot: {
          formatVersion: 1, nodeId: NODE_ID, incarnation: INCARNATION, bootCounter: 7,
          resetReason: 0, startedUptimeMs: 0, firstPossibleSequence: 1,
        } }) : '';
        const bytes = new TextEncoder().encode(json), response = new Uint8Array(9 + bytes.length);
        response.set(p); new DataView(response.buffer).setUint16(7, bytes.length, true); response.set(bytes, 9);
        return encodeFrame({ version: 1, opcode: Opcode.TimeEvidenceResponse, payload: response });
      }
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
          pending: log.filter(r => r.sequence > acked).length, ackWatermark: acked, storeStatus: 0, clockStatus: 2,
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
        const page = log.filter(r => r.sequence >= req.fromSequence).slice(0, req.maxRecords);
        const more = page.length > 0 && log.some(r => r.sequence > page[page.length - 1].sequence);
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
      case Opcode.CompactRequest: {
        const before = log.length;
        if (!opts.failCompact) log = log.filter(r => r.sequence > acked);
        return encodeFrame({ version: 1, opcode: Opcode.CompactResponse, payload: encodeCompactResponse({
          freed: before - log.length, remaining: log.length, ackWatermark: acked,
          error: opts.failCompact ? SyncError.Internal : SyncError.Ok,
        })});
      }
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

  test.each([
    { capable: true, metadataOrg: '22222222-2222-4222-8222-222222222222', sent: true },
    { capable: false, metadataOrg: '22222222-2222-4222-8222-222222222222', sent: false },
    { capable: true, metadataOrg: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', sent: false },
    { capable: true, metadataOrg: undefined, sent: false },
  ])('metadata is authenticated, bound, capability gated and optional offline: %j', async ({ capable, metadataOrg, sent }) => {
    const node = makeMockNode([makeRecord(1, 'chip')], { metadata: capable });
    const pinned = { ...identity, ...(metadataOrg ? { metadata: {
      organizationId: metadataOrg, bowlNumber: 4711, displayName: 'der gelbe',
    } } : {}) };
    const engine = new SyncEngine(new MockNodeTransport(node.handler), store, mockKeys, pinned);
    const result = await engine.sync('valid-credential', () => 1790899200, '22222222-2222-4222-8222-222222222222');
    expect(result.ok).toBe(true);
    expect(node.opcodes.includes(Opcode.MetadataRequest)).toBe(sent);
    if (sent) {
      expect(node.opcodes.indexOf(Opcode.MetadataRequest)).toBeGreaterThan(node.opcodes.indexOf(Opcode.NodeProofRequest));
      expect(node.opcodes.indexOf(Opcode.MetadataRequest)).toBeLessThan(node.opcodes.indexOf(Opcode.BatchRequest));
    }
    expect(node.getAcked()).toBe(1n);
  });

  test('metadata persistence rejection does not block durable observation sync', async () => {
    const node = makeMockNode([makeRecord(1, 'chip')], { metadata: true, metadataRejected: true });
    const engine = new SyncEngine(new MockNodeTransport(node.handler), store, mockKeys, { ...identity,
      metadata: { organizationId: '22222222-2222-4222-8222-222222222222', bowlNumber: 4711, displayName: '' },
    });
    const result = await engine.sync('valid-credential', () => 1790899200, '22222222-2222-4222-8222-222222222222');
    expect(result.ok).toBe(true);
    expect(result.maintenanceWarning).toContain('Napfname');
    expect(node.getAcked()).toBe(1n);
  });

  test('metadata and compaction warnings are both preserved', async () => {
    const node = makeMockNode([makeRecord(1, 'chip')], {
      metadata: true, metadataRejected: true, failCompact: true,
    });
    const engine = new SyncEngine(new MockNodeTransport(node.handler), store, mockKeys, { ...identity,
      metadata: { organizationId: '22222222-2222-4222-8222-222222222222', bowlNumber: 4711, displayName: '' },
    });
    const result = await engine.sync('valid-credential', () => 1790899200, '22222222-2222-4222-8222-222222222222');
    expect(result.ok).toBe(true);
    expect(result.maintenanceWarning).toContain('Napfname');
    expect(result.maintenanceWarning).toContain('Speicherbereinigung');
    expect(node.getAcked()).toBe(1n);
  });

  test('successful metadata retry clears the earlier warning', async () => {
    const node = makeMockNode([makeRecord(1, 'chip')], { metadata: true, loseAckOnce: true });
    let metadataAttempts = 0;
    const link = new MockNodeTransport(async frame => {
      if (decodeFrame(frame)?.opcode === Opcode.MetadataRequest && ++metadataAttempts === 1) {
        return encodeFrame({ version: 1, opcode: Opcode.Error, payload: new Uint8Array([0]) });
      }
      return node.handler(frame);
    });
    const engine = new SyncEngine(link, store, mockKeys, { ...identity,
      metadata: { organizationId: '22222222-2222-4222-8222-222222222222', bowlNumber: 4711, displayName: '' },
    }, { maxRetries: 1, retryDelayMs: 0 });
    const result = await engine.sync('valid-credential', () => 1790899200, '22222222-2222-4222-8222-222222222222');
    expect(result.ok).toBe(true);
    expect(metadataAttempts).toBe(2);
    expect(result.maintenanceWarning).toBeUndefined();
    expect(node.getAcked()).toBe(1n);
  });

  test('offline reboot alpha path persists before ACK, repeats after interruption, and never calls UTC backend', async () => {
    vi.stubEnv('VITE_MM_SECURITY_CLOCK_POLICY', undefined);
    const online = Object.getOwnPropertyDescriptor(globalThis, 'navigator');
    Object.defineProperty(globalThis, 'navigator', { value: { onLine: false }, configurable: true });
    try {
      const node = makeMockNode([makeRecord(1, 'chip')], { alphaClock: true, loseAckOnce: true });
      const bootstrapTime = vi.fn();
      const result = await new SyncEngine(new MockNodeTransport(node.handler), store, mockKeys, identity,
        { bootstrapTime, maxRetries: 1, retryDelayMs: 0 }).sync('credential', () => 1790899200, '22222222-2222-4222-8222-222222222222');
      expect(result.ok, result.error).toBe(true); expect(result.watermark).toBe(1n);
      expect((await db.observations.toArray())).toHaveLength(1);
      expect(result.timeMessage).toContain('FIELD_ALPHA_PHONE_CLOCK');
      expect(bootstrapTime).not.toHaveBeenCalled();
      expect(node.opcodes.filter(op => op === Opcode.AlphaPhoneClockRequest)).toHaveLength(2);
      expect(node.opcodes.indexOf(Opcode.AlphaPhoneClockRequest)).toBeLessThan(node.opcodes.indexOf(Opcode.ChallengeRequest));
      expect(node.opcodes.indexOf(Opcode.NodeProofRequest)).toBeLessThan(node.opcodes.indexOf(Opcode.BatchRequest));
      expect(node.opcodes).not.toContain(Opcode.RegistrationTimeChallengeRequest);
    } finally {
      vi.unstubAllEnvs();
      if (online) Object.defineProperty(globalThis, 'navigator', online); else Reflect.deleteProperty(globalThis, 'navigator');
    }
  });
  test('production PWA rejects alpha capability before sending a time sample or protected requests', async () => {
    const node = makeMockNode([], { alphaClock: true });
    const result = await new SyncEngine(new MockNodeTransport(node.handler), store, mockKeys, identity,
      { maxRetries: 0 }).sync('credential', () => 1790899200);
    expect(result.ok).toBe(false); expect(result.error).toContain('Policy');
    expect(node.opcodes).not.toContain(Opcode.AlphaPhoneClockRequest);
    expect(node.opcodes).not.toContain(Opcode.BatchRequest);
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
  test('every authenticated sync persists a fresh phone anchor before raw ACK, including reconnect after lost ACK', async () => {
    const org='22222222-2222-4222-8222-222222222222';
    const node=makeMockNode([makeRecord(1, 'chip')], { phoneTime: true, loseAckOnce: true });
    const rows: import('./time-evidence').StoredTimeEvidence[]=[];
    const persistence=Object.assign(store, { putTimeEvidence: async (row: import('./time-evidence').StoredTimeEvidence) => { rows.push(row); } });
    const transport=new MockNodeTransport(async request => {
      if (decodeFrame(request)?.opcode === Opcode.AckRequest) expect(rows.length).toBeGreaterThan(0);
      return node.handler(request);
    });
    const result=await new SyncEngine(transport,persistence,mockKeys,identity,{ maxRetries: 1,retryDelayMs: 0 }).sync('credential',()=>1790899200,org);
    expect(result.ok).toBe(true); expect(rows).toHaveLength(2); expect(new Set(rows.map(r=>r.id)).size).toBe(2);
    expect(rows.every(r=>r.organizationId===org && r.bootCounter==='7' && r.kind===2)).toBe(true);
    expect(node.opcodes.indexOf(Opcode.NodeProofRequest)).toBeLessThan(node.opcodes.indexOf(Opcode.ClockSnapshotRequest));
    expect(node.getAcked()).toBe(1n); expect(await store.sequences(NODE_ID,INCARNATION)).toEqual([1n]);
  });
  test.each(['reboot','interruption','storage','auth'])('phone snapshot %s failure cannot ACK or compact raw data', async failure => {
    const org='22222222-2222-4222-8222-222222222222';
    const node=makeMockNode([makeRecord(1,'chip')], { phoneTime: true, snapshotReboot: failure==='reboot',snapshotInterrupted: failure==='interruption',failAuth: failure==='auth' });
    const save=vi.fn(async () => { if (failure==='storage') throw new Error('quota'); });
    const result=await new SyncEngine(new MockNodeTransport(node.handler),Object.assign(store,{ putTimeEvidence: save }),mockKeys,identity,{ maxRetries: 0 }).sync('credential',()=>1790899200,org);
    expect(result.ok).toBe(false); expect(node.opcodes).not.toContain(Opcode.AckRequest); expect(node.opcodes).not.toContain(Opcode.CompactRequest);
    if (failure!=='storage') expect(save).not.toHaveBeenCalled();
    if (failure==='auth') expect(node.opcodes).not.toContain(Opcode.ClockSnapshotRequest);
  });

  test('failed reconnect preserves the last authenticated watermark and a new visit resets it', async () => {
    const node = makeMockNode([makeRecord(6, 'chip')], { initialAck: 5n });
    const transport = new MockNodeTransport(async frame => {
      if (decodeFrame(frame)?.opcode === Opcode.BatchRequest) throw new Error('connection lost');
      return node.handler(frame);
    });
    let connections = 0;
    transport.connect = async () => {
      if (++connections > 1) throw new Error('reconnect failed');
    };
    const engine = new SyncEngine(transport, store, mockKeys, identity, { maxRetries: 1, retryDelayMs: 0 });
    const result = await engine.sync('cred', () => 1790899200);
    expect(result.ok).toBe(false);
    expect(result.watermark).toBe(5n);
    expect(result.recordsReceived).toBe(0);
    expect((await engine.sync('cred', () => 1790899200)).watermark).toBe(0n);
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
    expect(result.recordsReceived).toBe(2);
    expect(engine.currentReceived).toBe(2);
    expect(node.opcodes.filter(op => op === Opcode.ChallengeRequest)).toHaveLength(2);
    expect((await store.session(NODE_ID))?.incarnation).toBe(INCARNATION);
    expect((await store.session(NODE_ID))?.watermark).toBe('2');
    expect((await engine.sync('valid-credential', () => 1790899200)).recordsReceived).toBe(0);
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
    expect(result.recordsReceived).toBe(2);
    expect(result.watermark).toBe(1n);
    expect(node.getAcked()).toBe(1n);
    expect(await store.sequences(NODE_ID, INCARNATION)).toEqual([1n, 3n]);
    expect(result.nodeStorage).toEqual({ before: 2, freed: 1, remaining: 1 });
    const retry = await engine.sync('credential', () => 1790899200);
    expect(retry.ok).toBe(false);
    expect(retry.nodeStorage).toEqual({ before: 1, freed: 0, remaining: 1 });
    expect(retry.watermark).toBe(1n);
  });

  test.each([false, true])('next visit with zero new records retries interrupted compaction (committed=%s)', async committed => {
    const node = makeMockNode([makeRecord(1, 'chip')]);
    let interrupt = true;
    const transport = new MockNodeTransport(async frame => {
      if (decodeFrame(frame)?.opcode === Opcode.CompactRequest && interrupt) {
        interrupt = false;
        if (committed) await node.handler(frame);
        throw new Error('BLE response lost');
      }
      return node.handler(frame);
    });
    const first = await new SyncEngine(transport, store, mockKeys, identity).sync('cred', () => 1790899200);
    expect(first.ok).toBe(true);
    expect(first.maintenanceWarning).toContain('Speicherbereinigung');
    const retry = await new SyncEngine(transport, store, mockKeys, identity).sync('cred', () => 1790899200);
    expect(retry.ok).toBe(true);
    expect(retry.recordsReceived).toBe(0);
    expect(retry.nodeStorage).toEqual({ before: committed ? 0 : 1, freed: committed ? 0 : 1, remaining: 0 });
    expect(retry.maintenanceWarning).toBeUndefined();
    expect(node.opcodes.filter(op => op === Opcode.AckRequest)).toHaveLength(1);
    expect(await store.sequences(NODE_ID, INCARNATION)).toEqual([1n]);
  });

  test.each(['full', 'status-lost'])('maintenance warning preserves copy and measured slots after %s', async failure => {
    const node = makeMockNode([makeRecord(1, 'chip')]);
    let compacted = false;
    const transport = new MockNodeTransport(async frame => {
      const opcode = decodeFrame(frame)?.opcode;
      if (opcode === Opcode.CompactRequest) compacted = true;
      const response = await node.handler(frame);
      if (opcode === Opcode.StatusRequest && compacted) {
        if (failure === 'status-lost') throw new Error('status lost');
        const f = decodeFrame(response)!;
        const p = f.payload.slice(); p[12] = 2; // store_status after pending/u64 ACK
        return encodeFrame({ ...f, payload: p });
      }
      return response;
    });
    const result = await new SyncEngine(transport, store, mockKeys, identity).sync('cred', () => 1790899200);
    expect(result.ok).toBe(true);
    expect(result.nodeStorage).toEqual({ before: 1, freed: 1, remaining: 0 });
    expect(result.maintenanceWarning).toContain(failure === 'full' ? 'voll' : 'nicht vollständig bestätigt');
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
    const result = await engine.sync('credential', () => 1790899200);
    expect(result.ok).toBe(kind === 'compact');
    if (kind === 'compact') expect(result.maintenanceWarning).toContain('Speicherbereinigung');
    expect(transport.connected).toBe(false);
  });
});


describe('ADR 0017 normal synchronization', () => {
  const org = '22222222-2222-4222-8222-222222222222';
  beforeEach(() => Object.defineProperty(globalThis, 'navigator', { configurable: true, value: { onLine: true } }));
  test.each([false, true])('backend outage uses only previously valid firmware time (%s)', async usable => {
    const node = makeMockNode([], { timeEvidence: true, timeUsable: usable }), db = new SyncDatabase(`outage-${crypto.randomUUID()}`);
    const store = Object.assign(new SyncStore(db), { putTimeEvidence: vi.fn(async () => {}) });
    const result = await new SyncEngine(new MockNodeTransport(node.handler), store, mockKeys, identity, {
      maxRetries: 0, bootstrapTime: async () => { throw new TimeBootstrapUnavailableError('HTTP unavailable'); },
    }).sync('credential', () => 1790899200, org);
    expect(result.ok).toBe(usable); expect(node.opcodes.includes(Opcode.AuthRequest)).toBe(usable);
    expect(result.timeMessage).not.toContain('akzeptiert'); db.close(); await db.delete();
  });
  test.each([false, true])('fresh/invalidated or renewable time (%s) bootstraps before PoP and persists evidence before raw ACK', async usable => {
    const node = makeMockNode([makeRecord(1, 'UNKNOWN')], { timeEvidence: true, timeUsable: usable, loseAckOnce: true });
    const db = new SyncDatabase(`time-sync-${crypto.randomUUID()}`), store = new SyncStore(db);
    const saved: string[] = [];
    const persistence = Object.assign(store, { putTimeEvidence: async (row: { id: string }) => { saved.push(row.id); } });
    const bootstrapTime = vi.fn(async () => {
      expect(node.opcodes.at(-1)).toBe(Opcode.SecurityTimeStatusRequest); return 0;
    });
    const transport = new MockNodeTransport(async bytes => {
      if (decodeFrame(bytes)?.opcode === Opcode.AckRequest) expect(saved.length).toBeGreaterThan(0);
      return node.handler(bytes);
    });
    const result = await new SyncEngine(transport, persistence, mockKeys, identity,
      { bootstrapTime, retryDelayMs: 0, maxRetries: 1 }).sync('credential', () => 1790899200, org);
    expect(result.ok).toBe(true); expect(result.evidenceStored).toBe(1); expect(result.timeMessage).toContain('akzeptiert');
    expect(bootstrapTime).toHaveBeenCalledTimes(usable ? 1 : 2); expect(node.getAcked()).toBe(1n);
    expect(node.opcodes.indexOf(Opcode.TimeEvidenceRequest)).toBeLessThan(node.opcodes.indexOf(Opcode.AckRequest));
    db.close(); await db.delete();
  });
  test('missing backend offline fails closed without PoP, raw reads, ACK or compaction', async () => {
    Object.defineProperty(globalThis, 'navigator', { configurable: true, value: { onLine: false } });
    const node = makeMockNode([], { timeEvidence: true }), db = new SyncDatabase(`offline-${crypto.randomUUID()}`);
    const result = await new SyncEngine(new MockNodeTransport(node.handler), new SyncStore(db), mockKeys, identity,
      { maxRetries: 0 }).sync('credential', () => 1790899200, org);
    expect(result.ok).toBe(false); expect(result.error).toContain('trusted-time');
    expect(node.opcodes).not.toContain(Opcode.AuthRequest); expect(node.opcodes).not.toContain(Opcode.AckRequest);
    db.close(); await db.delete();
  });
  test('offline existing trusted time keeps authorization and independent evidence transfer', async () => {
    Object.defineProperty(globalThis, 'navigator', { configurable: true, value: { onLine: false } });
    const node = makeMockNode([], { timeEvidence: true, timeUsable: true }), db = new SyncDatabase(`hold-${crypto.randomUUID()}`);
    const store = Object.assign(new SyncStore(db), { putTimeEvidence: vi.fn(async () => {}) });
    const result = await new SyncEngine(new MockNodeTransport(node.handler), store, mockKeys, identity,
      { maxRetries: 0 }).sync('credential', () => 1790899200, org);
    expect(result.ok).toBe(true); expect(result.timeMessage).toContain('vorhandene'.replace('v', 'V'));
    expect(store.putTimeEvidence).toHaveBeenCalledOnce(); db.close(); await db.delete();
  });
  test.each(['storage', 'disconnect'])('interrupted evidence %s blocks raw ACK and preserves retryability', async reason => {
    const node = makeMockNode([makeRecord(1, 'chip')], { timeEvidence: true }), db = new SyncDatabase(`fail-${crypto.randomUUID()}`);
    const store = Object.assign(new SyncStore(db), { putTimeEvidence: async () => { if (reason === 'storage') throw new Error('quota'); } });
    const transport = new MockNodeTransport(async bytes => {
      if (reason === 'disconnect' && decodeFrame(bytes)?.opcode === Opcode.TimeEvidenceRequest) throw new Error('BLE lost');
      return node.handler(bytes);
    });
    const result = await new SyncEngine(transport, store, mockKeys, identity, { bootstrapTime: async () => 0, maxRetries: 0 })
      .sync('credential', () => 1790899200, org);
    expect(result.ok).toBe(false); expect(node.opcodes).not.toContain(Opcode.AckRequest); expect(node.opcodes).not.toContain(Opcode.CompactRequest);
    db.close(); await db.delete();
  });
});
