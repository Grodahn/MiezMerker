import { describe, expect, test } from 'vitest';
import {
  decodeAdvertisement, decodeAuthResponse, decodeBatchRequest, decodeBatchResponse,
  decodeChallengeResponse, decodeFrame, decodeHelloPublic, decodeOwnerResponse,
  decodeRecord, decodeStatusResponse, decodeTimeResponse,
  encodeAckRequest, encodeAdvertisement, encodeAuthRequest, encodeAuthResponse,
  encodeBatchRequest, encodeBatchResponse, encodeChallengeResponse, encodeFrame,
  encodeHelloPublic, encodeOwnerResponse, encodeRecord, encodeStatusResponse,
  encodeTimeResponse, Opcode, PROTOCOL_VERSION, SyncError,
} from './ble-codec';

function unhex(s: string): Uint8Array {
  const out = new Uint8Array(s.length / 2);
  for (let i = 0; i < out.length; i++) out[i] = parseInt(s.slice(i * 2, i * 2 + 2), 16);
  return out;
}
function hex(b: Uint8Array): string {
  return Array.from(b).map(x => x.toString(16).padStart(2, '0')).join('');
}

const NODE_ID = '44444444-4444-4444-8444-444444444444';
const INCARNATION = '55555555-5555-4555-9555-555555555555';

describe('ble-codec golden vectors', () => {
  test('hello public frame matches fixture', () => {
    const payload = encodeHelloPublic({
      serverVer: 1, caps: 0x001f,
      nodeId: NODE_ID, incarnation: INCARNATION,
      firmwareVersion: 'miezmerker-esp32c3-1.0.0',
      claimState: 1, clockStatus: 2,
    });
    const frame = encodeFrame({ version: 1, opcode: Opcode.HelloPublic, payload });
    expect(hex(frame)).toBe(
      '01023e00011f004444444444444444844444444444444455555555555545559555555555555555186d69657a6d65726b65722d657370333263332d312e302e300102',
    );
  });

  test('record 101 matches fixture', () => {
    const rec = {
      nodeId: NODE_ID, incarnation: INCARNATION,
      sequence: 101n, chipId: '276098106000001',
      clockStatus: 2, epochMs: 1790899200000n,
      monotonicMs: 1000n, bootCounter: 7,
    };
    expect(hex(encodeRecord(rec))).toBe(
      '444444444444444484444444444444445555555555554555955555555555555565000000000000000f00323736303938313036303030303031020020e9f9a0010000e80300000000000007000000',
    );
  });

  test('batch request matches fixture', () => {
    const frame = encodeFrame({
      version: 1, opcode: Opcode.BatchRequest,
      payload: encodeBatchRequest({ fromSequence: 101n, maxRecords: 16 }),
    });
    expect(hex(frame)).toBe('010b0a0065000000000000001000');
  });

  test('ack request matches fixture', () => {
    const frame = encodeFrame({
      version: 1, opcode: Opcode.AckRequest,
      payload: encodeAckRequest(103n),
    });
    expect(hex(frame)).toBe('010d08006700000000000000');
  });

  test('status response matches fixture', () => {
    const frame = encodeFrame({
      version: 1, opcode: Opcode.StatusResponse,
      payload: encodeStatusResponse({
        pending: 3, ackWatermark: 100n, storeStatus: 0, clockStatus: 2,
        epochMs: 1790899200000n, nextSequence: 104n,
      }),
    });
    expect(hex(frame)).toBe('01121e0003000000640000000000000000020020e9f9a00100006800000000000000');
  });

  test('advertisement has no identity/chip/pending/org', () => {
    const adv = encodeAdvertisement({ protocolVersion: 1, claimed: true, claimMode: false });
    expect(hex(adv)).toBe('01010000');
    const decoded = decodeAdvertisement(adv);
    expect(decoded?.claimed).toBe(true);
  });

  test('unknown version frame decodes but is rejected by router', () => {
    const f = decodeFrame(unhex('02ff010000'));
    expect(f?.version).toBe(2);
  });

  test('truncated frame fails', () => {
    expect(decodeFrame(unhex('010d080067000000000000'))).toBeNull();
    expect(decodeFrame(unhex('010d0800670000000000000000ff'))).toBeNull();
  });

  test('invalid record rejected', () => {
    const bad = {
      nodeId: NODE_ID, incarnation: INCARNATION,
      sequence: 0n, chipId: 'x',
      clockStatus: 2, epochMs: 1n,
      monotonicMs: 1n, bootCounter: 1,
    };
    expect(() => encodeRecord(bad)).toThrow();
  });

  test('decode round-trip: owner response', () => {
    const payload = encodeOwnerResponse({
      nodeId: NODE_ID, claimState: 1,
      organizationId: '22222222-2222-4222-8222-222222222222',
      organizationSlug: 'vector-org',
      organizationName: 'Vector Org',
      publicContact: 'help@vector.example',
    });
    const decoded = decodeOwnerResponse(payload);
    expect(decoded?.organizationId).toBe('22222222-2222-4222-8222-222222222222');
    expect(decoded?.organizationName).toBe('Vector Org');
  });

  test('decode round-trip: auth response', () => {
    const payload = encodeAuthResponse({ ok: true, expiresS: 1917129600n, error: SyncError.Ok });
    const decoded = decodeAuthResponse(payload);
    expect(decoded?.ok).toBe(true);
    expect(decoded?.expiresS).toBe(1917129600n);
  });

  test('decode round-trip: time response', () => {
    const payload = encodeTimeResponse({ ok: true, error: SyncError.Ok, appliedEpochMs: 1790900000000n });
    const decoded = decodeTimeResponse(payload);
    expect(decoded?.ok).toBe(true);
    expect(decoded?.appliedEpochMs).toBe(1790900000000n);
  });

  test('decode round-trip: challenge response', () => {
    const nonce = new Uint8Array(32).map((_, i) => i);
    const decoded = decodeChallengeResponse(encodeChallengeResponse(nonce));
    expect(decoded?.[0]).toBe(0);
    expect(decoded?.[31]).toBe(31);
  });

  test('decode round-trip: batch response with records', () => {
    const r101 = {
      nodeId: NODE_ID, incarnation: INCARNATION,
      sequence: 101n, chipId: '276098106000001',
      clockStatus: 2, epochMs: 1790899200000n,
      monotonicMs: 1000n, bootCounter: 7,
    };
    const r102 = {
      nodeId: NODE_ID, incarnation: INCARNATION,
      sequence: 102n, chipId: '276098106000002',
      clockStatus: 2, epochMs: 1790899201000n,
      monotonicMs: 4000n, bootCounter: 7,
    };
    const payload = encodeBatchResponse({
      fromSequence: 101n, more: false, nextCursor: 103n,
      records: [r101, r102],
    });
    const decoded = decodeBatchResponse(payload);
    expect(decoded?.records).toHaveLength(2);
    expect(decoded?.records[0].chipId).toBe('276098106000001');
    expect(decoded?.records[1].sequence).toBe(102n);
    expect(decoded?.more).toBe(false);
    expect(decoded?.nextCursor).toBe(103n);
  });

  test('decode round-trip: record with unknown clock', () => {
    const rec = {
      nodeId: NODE_ID, incarnation: INCARNATION,
      sequence: 103n, chipId: '276098106000003',
      clockStatus: 0, epochMs: null,
      monotonicMs: 7000n, bootCounter: 7,
    };
    const encoded = encodeRecord(rec);
    const decoded = decodeRecord(encoded);
    expect(decoded?.record.clockStatus).toBe(0);
    expect(decoded?.record.epochMs).toBeNull();
    expect(decoded?.consumed).toBe(encoded.length);
  });
});
