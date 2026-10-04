// BLE sync v1 transport-independent codec (issue #6).
//
// Mirror of firmware-core/ble_codec.* with identical byte layout.
// All integers little-endian. Strings: [len u16 LE][UTF-8].
// No Web-Bluetooth/NimBLE imports: pure Uint8Array encode/decode.

export const PROTOCOL_VERSION = 1;

export const CAP_BATCH = 0x0001;
export const CAP_ACK = 0x0002;
export const CAP_TIME_CORRECT = 0x0004;
export const CAP_NODE_PROOF = 0x0008;
export const CAP_COMPACT = 0x0010;
export const CAP_ALL_V1 = CAP_BATCH | CAP_ACK | CAP_TIME_CORRECT | CAP_NODE_PROOF | CAP_COMPACT;

export enum Opcode {
  HelloRequest = 0x01,
  HelloPublic = 0x02,
  OwnerRequest = 0x03,
  OwnerResponse = 0x04,
  ChallengeRequest = 0x05,
  ChallengeResponse = 0x06,
  AuthRequest = 0x07,
  AuthResponse = 0x08,
  NodeProofRequest = 0x09,
  NodeProofResponse = 0x0a,
  BatchRequest = 0x0b,
  BatchResponse = 0x0c,
  AckRequest = 0x0d,
  AckResponse = 0x0e,
  TimeRequest = 0x0f,
  TimeResponse = 0x10,
  StatusRequest = 0x11,
  StatusResponse = 0x12,
  CompactRequest = 0x13,
  CompactResponse = 0x14,
  Error = 0xff,
}

export enum SyncError {
  Ok = 0,
  UnknownVersion = 1,
  Unauthorized = 2,
  ForbiddenForeign = 3,
  InvalidFrame = 4,
  InvalidSequence = 5,
  InvalidRecord = 6,
  NotFound = 7,
  StoreFull = 8,
  ClockUnavailable = 9,
  AuthExpired = 10,
  Replay = 11,
  InvalidCredential = 12,
  InvalidProof = 13,
  InvalidState = 14,
  Internal = 15,
}

export interface Frame {
  version: number;
  opcode: Opcode;
  payload: Uint8Array;
}

export function encodeFrame(frame: Frame): Uint8Array {
  const out = new Uint8Array(4 + frame.payload.length);
  out[0] = frame.version;
  out[1] = frame.opcode;
  out[2] = frame.payload.length & 0xff;
  out[3] = (frame.payload.length >> 8) & 0xff;
  out.set(frame.payload, 4);
  return out;
}

export function decodeFrame(bytes: Uint8Array): Frame | null {
  if (bytes.length < 4) return null;
  const len = bytes[2] | (bytes[3] << 8);
  if (bytes.length - 4 !== len) return null;
  return { version: bytes[0], opcode: bytes[1] as Opcode, payload: bytes.slice(4) };
}

class Writer {
  private buf: number[] = [];
  u8(v: number) { this.buf.push(v & 0xff); }
  u16(v: number) { this.buf.push(v & 0xff, (v >> 8) & 0xff); }
  u32(v: number) { for (let i = 0; i < 4; i++) this.buf.push((v >>> (8 * i)) & 0xff); }
  u64(v: bigint) { for (let i = 0; i < 8; i++) this.buf.push(Number((v >> BigInt(8 * i)) & 0xffn)); }
  bytes(b: Uint8Array) { for (const x of b) this.buf.push(x); }
  str16(s: string) {
    const enc = new TextEncoder().encode(s);
    this.u16(enc.length);
    this.bytes(enc);
  }
  str8(s: string) {
    const enc = new TextEncoder().encode(s);
    if (enc.length > 255) throw new Error('str8 too long');
    this.u8(enc.length);
    this.bytes(enc);
  }
  toBytes(): Uint8Array { return new Uint8Array(this.buf); }
}

class Reader {
  pos = 0;
  constructor(private buf: Uint8Array) {}
  u8(): number | null {
    if (this.pos + 1 > this.buf.length) return null;
    return this.buf[this.pos++];
  }
  u16(): number | null {
    if (this.pos + 2 > this.buf.length) return null;
    const v = this.buf[this.pos] | (this.buf[this.pos + 1] << 8);
    this.pos += 2;
    return v;
  }
  u32(): number | null {
    if (this.pos + 4 > this.buf.length) return null;
    let v = 0;
    for (let i = 0; i < 4; i++) v |= this.buf[this.pos + i] << (8 * i);
    this.pos += 4;
    return v >>> 0;
  }
  u64(): bigint | null {
    if (this.pos + 8 > this.buf.length) return null;
    let v = 0n;
    for (let i = 0; i < 8; i++) v |= BigInt(this.buf[this.pos + i]) << BigInt(8 * i);
    this.pos += 8;
    return v;
  }
  bytes(n: number): Uint8Array | null {
    if (this.pos + n > this.buf.length) return null;
    const out = this.buf.slice(this.pos, this.pos + n);
    this.pos += n;
    return out;
  }
  str16(max = 2048): string | null {
    const len = this.u16();
    if (len === null || len > max) return null;
    const b = this.bytes(len);
    if (b === null) return null;
    return new TextDecoder().decode(b);
  }
  get remaining(): number { return this.buf.length - this.pos; }
}

export interface HelloRequest { clientVer: number; caps: number }
export function encodeHelloRequest(m: HelloRequest): Uint8Array {
  const w = new Writer();
  w.u8(m.clientVer);
  w.u16(m.caps);
  return w.toBytes();
}
export function decodeHelloRequest(p: Uint8Array): HelloRequest | null {
  const r = new Reader(p);
  const clientVer = r.u8();
  const caps = r.u16();
  if (clientVer === null || caps === null || r.remaining !== 0) return null;
  return { clientVer, caps };
}

export interface HelloPublic {
  serverVer: number; caps: number;
  nodeId: string; incarnation: string;
  firmwareVersion: string;
  claimState: number; clockStatus: number;
}
export function encodeHelloPublic(m: HelloPublic): Uint8Array {
  const w = new Writer();
  w.u8(m.serverVer);
  w.u16(m.caps);
  w.bytes(uuidToBytes(m.nodeId));
  w.bytes(uuidToBytes(m.incarnation));
  w.str8(m.firmwareVersion);
  w.u8(m.claimState);
  w.u8(m.clockStatus);
  return w.toBytes();
}
export function decodeHelloPublic(p: Uint8Array): HelloPublic | null {
  const r = new Reader(p);
  const serverVer = r.u8();
  const caps = r.u16();
  const nodeId = r.bytes(16);
  const incarnation = r.bytes(16);
  const fwLen = r.u8();
  if (serverVer === null || caps === null || fwLen === null || fwLen > 64 ||
      nodeId === null || incarnation === null ||
      !nodeId.some(b => b !== 0) || !incarnation.some(b => b !== 0)) return null;
  const fw = r.bytes(fwLen);
  const claimState = r.u8();
  const clockStatus = r.u8();
  if (fw === null || claimState === null || clockStatus === null || r.remaining !== 0) return null;
  if (claimState > 1 || clockStatus > 2) return null;
  return {
    serverVer, caps,
    nodeId: bytesToUuid(nodeId), incarnation: bytesToUuid(incarnation),
    firmwareVersion: new TextDecoder().decode(fw),
    claimState, clockStatus,
  };
}

export interface OwnerInfo {
  nodeId: string; claimState: number;
  organizationId: string; organizationSlug: string;
  organizationName: string; publicContact: string;
}
export function encodeOwnerResponse(m: OwnerInfo): Uint8Array {
  const w = new Writer();
  w.bytes(uuidToBytes(m.nodeId));
  w.u8(m.claimState);
  w.str16(m.organizationId);
  w.str16(m.organizationSlug);
  w.str16(m.organizationName);
  w.str16(m.publicContact);
  return w.toBytes();
}
export function decodeOwnerResponse(p: Uint8Array): OwnerInfo | null {
  const r = new Reader(p);
  const nodeId = r.bytes(16);
  const claimState = r.u8();
  const organizationId = r.str16(128);
  const organizationSlug = r.str16(128);
  const organizationName = r.str16(128);
  const publicContact = r.str16(256);
  if (nodeId === null || claimState === null || organizationId === null ||
      organizationSlug === null || organizationName === null || publicContact === null ||
      r.remaining !== 0) return null;
  if (claimState > 1) return null;
  return { nodeId: bytesToUuid(nodeId), claimState, organizationId, organizationSlug, organizationName, publicContact };
}

export function encodeChallengeResponse(nonce: Uint8Array): Uint8Array {
  if (nonce.length !== 32) throw new Error('challenge must be 32 bytes');
  return new Uint8Array(nonce);
}
export function decodeChallengeResponse(p: Uint8Array): Uint8Array | null {
  if (p.length !== 32) return null;
  return new Uint8Array(p);
}

export interface AuthRequest { credential: string; proof: Uint8Array }
export function encodeAuthRequest(m: AuthRequest): Uint8Array {
  if (m.credential.length > 2048) throw new Error('credential too long');
  if (m.proof.length !== 64) throw new Error('proof must be 64 bytes');
  const w = new Writer();
  w.str16(m.credential);
  w.bytes(m.proof);
  return w.toBytes();
}
export function decodeAuthRequest(p: Uint8Array): AuthRequest | null {
  const r = new Reader(p);
  const credential = r.str16(2048);
  if (credential === null || credential.length === 0) return null;
  const proof = r.bytes(64);
  if (proof === null || r.remaining !== 0) return null;
  return { credential, proof };
}

export interface AuthResponse { ok: boolean; expiresS: bigint; error: SyncError }
export function encodeAuthResponse(m: AuthResponse): Uint8Array {
  const w = new Writer();
  w.u8(m.ok ? 1 : 0);
  w.u64(m.expiresS);
  w.u8(m.error);
  return w.toBytes();
}
export function decodeAuthResponse(p: Uint8Array): AuthResponse | null {
  const r = new Reader(p);
  const ok = r.u8();
  const expiresS = r.u64();
  const error = r.u8();
  if (ok === null || expiresS === null || error === null || r.remaining !== 0) return null;
  if (ok > 1 || error > 15) return null;
  return { ok: ok === 1, expiresS, error: error as SyncError };
}

export function encodeNodeProofRequest(nonce: Uint8Array): Uint8Array {
  if (nonce.length !== 32) throw new Error('nonce must be 32 bytes');
  return new Uint8Array(nonce);
}
export function decodeNodeProofRequest(p: Uint8Array): Uint8Array | null {
  if (p.length !== 32) return null;
  return new Uint8Array(p);
}
export function encodeNodeProofResponse(sig: Uint8Array): Uint8Array {
  if (sig.length !== 64) throw new Error('sig must be 64 bytes');
  return new Uint8Array(sig);
}
export function decodeNodeProofResponse(p: Uint8Array): Uint8Array | null {
  if (p.length !== 64) return null;
  return new Uint8Array(p);
}

export interface BleRecord {
  nodeId: string; incarnation: string;
  sequence: bigint; chipId: string;
  clockStatus: number; epochMs: bigint | null;
  monotonicMs: bigint; bootCounter: number;
}
export function encodeRecord(r: BleRecord): Uint8Array {
  const u64Max = 0xffffffffffffffffn;
  if (r.sequence < 1n || r.sequence > u64Max || r.monotonicMs < 0n || r.monotonicMs > u64Max ||
      !Number.isInteger(r.bootCounter) || r.bootCounter < 0 || r.bootCounter > 0xffffffff ||
      !Number.isInteger(r.clockStatus) || r.clockStatus < 0 || r.clockStatus > 2 ||
      (r.clockStatus === 0 ? r.epochMs !== null : r.epochMs === null || r.epochMs <= 0n) ||
      (r.epochMs !== null && r.epochMs > u64Max)) throw new Error('invalid record');
  const w = new Writer();
  w.bytes(uuidToBytes(r.nodeId));
  w.bytes(uuidToBytes(r.incarnation));
  w.u64(r.sequence);
  const chip = new TextEncoder().encode(r.chipId);
  if (chip.length === 0 || chip.length > 64) throw new Error('invalid chip_id');
  w.u16(chip.length);
  w.bytes(chip);
  w.u8(r.clockStatus);
  w.u64(r.epochMs ?? 0n);
  w.u64(r.monotonicMs);
  w.u32(r.bootCounter);
  return w.toBytes();
}
export function decodeRecord(bytes: Uint8Array, offset = 0): { record: BleRecord; consumed: number } | null {
  if (!Number.isInteger(offset) || offset < 0 || offset > bytes.length) return null;
  const r = new Reader(bytes.slice(offset));
  const nodeId = r.bytes(16);
  const incarnation = r.bytes(16);
  const sequence = r.u64();
  const chipLen = r.u16();
  if (nodeId === null || incarnation === null || sequence === null || chipLen === null) return null;
  if (sequence === 0n || !nodeId.some(b => b !== 0) || !incarnation.some(b => b !== 0)) return null;
  if (chipLen === 0 || chipLen > 64) return null;
  const chip = r.bytes(chipLen);
  const clockStatus = r.u8();
  const epoch = r.u64();
  const monotonicMs = r.u64();
  const bootCounter = r.u32();
  if (chip === null || clockStatus === null || epoch === null || monotonicMs === null || bootCounter === null) return null;
  if (clockStatus > 2) return null;
  if (clockStatus === 0 && epoch !== 0n) return null;
  if (clockStatus !== 0 && epoch === 0n) return null;
  let chipId: string;
  try {
    // Preserve a leading BOM as part of the opaque identifier; reject invalid
    // bytes instead of replacing them before persistence and ACK.
    chipId = new TextDecoder('utf-8', { fatal: true, ignoreBOM: true }).decode(chip);
  } catch {
    return null;
  }
  return {
    record: {
      nodeId: bytesToUuid(nodeId), incarnation: bytesToUuid(incarnation),
      sequence, chipId,
      clockStatus, epochMs: clockStatus === 0 ? null : epoch,
      monotonicMs, bootCounter,
    },
    consumed: offset + (bytes.length - offset - r.remaining),
  };
}

export interface BatchRequest { fromSequence: bigint; maxRecords: number }
export function encodeBatchRequest(m: BatchRequest): Uint8Array {
  const w = new Writer();
  w.u64(m.fromSequence);
  w.u16(m.maxRecords);
  return w.toBytes();
}
export function decodeBatchRequest(p: Uint8Array): BatchRequest | null {
  const r = new Reader(p);
  const fromSequence = r.u64();
  const maxRecords = r.u16();
  if (fromSequence === null || maxRecords === null || r.remaining !== 0) return null;
  if (fromSequence === 0n || maxRecords === 0 || maxRecords > 64) return null;
  return { fromSequence, maxRecords };
}

export interface BatchResponse {
  fromSequence: bigint; more: boolean; nextCursor: bigint;
  records: BleRecord[];
}
export function encodeBatchResponse(m: BatchResponse): Uint8Array {
  const w = new Writer();
  w.u64(m.fromSequence);
  w.u16(m.records.length);
  w.u8(m.more ? 1 : 0);
  w.u64(m.nextCursor);
  for (const r of m.records) w.bytes(encodeRecord(r));
  return w.toBytes();
}
export function decodeBatchResponse(p: Uint8Array): BatchResponse | null {
  const r = new Reader(p);
  const fromSequence = r.u64();
  const count = r.u16();
  const more = r.u8();
  const nextCursor = r.u64();
  if (fromSequence === null || count === null || more === null || nextCursor === null) return null;
  if (more > 1 || count > 64) return null;
  const records: BleRecord[] = [];
  for (let i = 0; i < count; i++) {
    const res = decodeRecord(p, r.pos);
    if (res === null) return null;
    records.push(res.record);
    r.pos += res.consumed - r.pos;
  }
  if (r.remaining !== 0) return null;
  return { fromSequence, more: more === 1, nextCursor, records };
}

export function encodeAckRequest(watermark: bigint): Uint8Array {
  const w = new Writer();
  w.u64(watermark);
  return w.toBytes();
}
export function decodeAckRequest(p: Uint8Array): bigint | null {
  if (p.length !== 8) return null;
  const r = new Reader(p);
  const w = r.u64();
  if (w === null || r.remaining !== 0) return null;
  return w;
}

export interface AckResponse { newWatermark: bigint; error: SyncError }
export function encodeAckResponse(m: AckResponse): Uint8Array {
  const w = new Writer();
  w.u64(m.newWatermark);
  w.u8(m.error);
  return w.toBytes();
}
export function decodeAckResponse(p: Uint8Array): AckResponse | null {
  const r = new Reader(p);
  const newWatermark = r.u64();
  const error = r.u8();
  if (newWatermark === null || error === null || r.remaining !== 0) return null;
  if (error > 15) return null;
  return { newWatermark, error: error as SyncError };
}

export function encodeTimeRequest(epochMs: bigint): Uint8Array {
  const w = new Writer();
  w.u64(epochMs);
  return w.toBytes();
}
export function decodeTimeRequest(p: Uint8Array): bigint | null {
  if (p.length !== 8) return null;
  const r = new Reader(p);
  const v = r.u64();
  if (v === null || r.remaining !== 0) return null;
  return v;
}

export interface TimeResponse { ok: boolean; error: SyncError; appliedEpochMs: bigint }
export function encodeTimeResponse(m: TimeResponse): Uint8Array {
  const w = new Writer();
  w.u8(m.ok ? 1 : 0);
  w.u8(m.error);
  w.u64(m.appliedEpochMs);
  return w.toBytes();
}
export function decodeTimeResponse(p: Uint8Array): TimeResponse | null {
  const r = new Reader(p);
  const ok = r.u8();
  const error = r.u8();
  const appliedEpochMs = r.u64();
  if (ok === null || error === null || appliedEpochMs === null || r.remaining !== 0) return null;
  if (ok > 1 || error > 15) return null;
  return { ok: ok === 1, error: error as SyncError, appliedEpochMs };
}

export interface StatusResponse {
  pending: number; ackWatermark: bigint;
  storeStatus: number; clockStatus: number;
  epochMs: bigint; nextSequence: bigint;
}
export function encodeStatusResponse(m: StatusResponse): Uint8Array {
  const w = new Writer();
  w.u32(m.pending);
  w.u64(m.ackWatermark);
  w.u8(m.storeStatus);
  w.u8(m.clockStatus);
  w.u64(m.epochMs);
  w.u64(m.nextSequence);
  return w.toBytes();
}
export function decodeStatusResponse(p: Uint8Array): StatusResponse | null {
  const r = new Reader(p);
  const pending = r.u32();
  const ackWatermark = r.u64();
  const storeStatus = r.u8();
  const clockStatus = r.u8();
  const epochMs = r.u64();
  const nextSequence = r.u64();
  if (pending === null || ackWatermark === null || storeStatus === null ||
      clockStatus === null || epochMs === null || nextSequence === null || r.remaining !== 0) return null;
  if (storeStatus > 2 || clockStatus > 2) return null;
  if (clockStatus === 0 && epochMs !== 0n) return null;
  if (clockStatus !== 0 && epochMs === 0n) return null;
  if (nextSequence === 0n) return null;
  return { pending, ackWatermark, storeStatus, clockStatus, epochMs, nextSequence };
}

export interface CompactResponse {
  freed: number; remaining: number;
  ackWatermark: bigint; error: SyncError;
}
export function encodeCompactResponse(m: CompactResponse): Uint8Array {
  const w = new Writer();
  w.u32(m.freed);
  w.u32(m.remaining);
  w.u64(m.ackWatermark);
  w.u8(m.error);
  return w.toBytes();
}
export function decodeCompactResponse(p: Uint8Array): CompactResponse | null {
  const r = new Reader(p);
  const freed = r.u32();
  const remaining = r.u32();
  const ackWatermark = r.u64();
  const error = r.u8();
  if (freed === null || remaining === null || ackWatermark === null || error === null || r.remaining !== 0) return null;
  if (error > 15) return null;
  return { freed, remaining, ackWatermark, error: error as SyncError };
}

export interface ErrorMsg { code: SyncError; message: string }
export function encodeErrorPayload(m: ErrorMsg): Uint8Array {
  const w = new Writer();
  w.u8(m.code);
  w.str16(m.message);
  return w.toBytes();
}
export function decodeErrorPayload(p: Uint8Array): ErrorMsg | null {
  const r = new Reader(p);
  const code = r.u8();
  const message = r.str16(256);
  if (code === null || message === null || r.remaining !== 0) return null;
  if (code > 15) return null;
  return { code: code as SyncError, message };
}

export interface Advertisement {
  protocolVersion: number; claimed: boolean; claimMode: boolean;
}
export function encodeAdvertisement(a: Advertisement): Uint8Array {
  const w = new Writer();
  w.u8(a.protocolVersion);
  w.u8((a.claimed ? 0x01 : 0) | (a.claimMode ? 0x02 : 0));
  w.u16(0);
  return w.toBytes();
}
export function decodeAdvertisement(b: Uint8Array): Advertisement | null {
  if (b.length !== 4) return null;
  if ((b[1] & 0xfc) !== 0) return null;
  if (b[2] !== 0 || b[3] !== 0) return null;
  return { protocolVersion: b[0], claimed: (b[1] & 0x01) !== 0, claimMode: (b[1] & 0x02) !== 0 };
}

function uuidToBytes(uuid: string): Uint8Array {
  if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(uuid) ||
      uuid === '00000000-0000-0000-0000-000000000000') throw new Error('invalid uuid');
  const hex = uuid.replace(/-/g, '');
  if (hex.length !== 32) throw new Error('invalid uuid');
  const out = new Uint8Array(16);
  for (let i = 0; i < 16; i++) out[i] = parseInt(hex.slice(i * 2, i * 2 + 2), 16);
  return out;
}
function bytesToUuid(b: Uint8Array): string {
  const hex = Array.from(b).map(x => x.toString(16).padStart(2, '0')).join('');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}
