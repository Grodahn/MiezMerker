import type { NodeTransport } from '../platform/node-transport';
import type { CollectorDatabase } from '../platform/offline-store';
import { decodeFrame, encodeFrame, Opcode } from './ble-codec';

export interface StoredTimeEvidence {
  ownerUserId?: string;
  id: string;
  organizationId: string;
  nodeId: string;
  incarnation: string;
  bootCounter: string;
  kind: 0 | 1;
  // Exact UTF-8 envelope, including unsigned integer spellings and signed bytes.
  payload: string;
  uploaded: boolean;
  nodeAcknowledged?: boolean;
}

// Tokenize strings before integers, so digits in JWS/base64/string values are
// untouched. No JSON integer ever passes through JavaScript Number.
export function parseEvidenceJson(text: string): Record<string, any> {
  return JSON.parse(text.replace(/"(?:[^"\\]|\\.)*"|-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?/g, token => {
    if (token.startsWith('"')) return token;
    if (!/^-?\d+$/.test(token)) throw new Error('Non-integer evidence value');
    return JSON.stringify(token);
  }));
}

const integerFields = new Set(['formatVersion', 'version', 'bootCounter', 'resetReason', 'startedUptimeMs',
  'firstPossibleSequence', 'challengeUptimeMs', 'acceptedUptimeMs', 'uptimeMs', 'utcMs', 'source',
  'uncertaintyMinusMs', 'uncertaintyPlusMs', 'driftPpmBound']);
export function evidenceRequestJson(row: StoredTimeEvidence): string {
  const { metadata, ...payload } = parseEvidenceJson(row.payload);
  if (row.kind === 1 && !payload.challenge) throw new Error('Historischer Zeitbeleg ohne signierte Challenge; auf Node und PWA erhalten.');
  const nodeMetadata = metadata ? serializeIntegers(metadata) : undefined;
  return serializeIntegers({ ...payload, organizationId: row.organizationId, ...(nodeMetadata ? { nodeMetadata } : {}) });
}
function serializeIntegers(value: unknown): string {
  const json = JSON.stringify(value);
  // Only documented integer fields become JSON integers; opaque strings stay exact.
  return json.replace(/"([^"]+)":"(\d+)"/g, (match, key: string, value: string) =>
    integerFields.has(key) ? `"${key}":${value}` : match);
}

function uint(value: unknown, bits: number, nonzero = false): string {
  if (typeof value !== 'string' || !/^(0|[1-9]\d*)$/.test(value) ||
      BigInt(value) >= (1n << BigInt(bits)) || (nonzero && value === '0')) throw new Error('Invalid evidence integer');
  return value;
}

export function evidenceRow(payload: string, kind: 0 | 1, nodeId: string, incarnation: string,
  organizationId: string): StoredTimeEvidence {
  const p = parseEvidenceJson(payload), b = p.boot;
  const fields = kind === 0 ? ['boot'] : ['boot', 'proof', 'challenge', 'acceptance', 'challengeBytes', 'acceptanceBytes', 'metadata'];
  if (Object.keys(p).length !== fields.length || fields.some(field => !Object.hasOwn(p, field))) throw new Error('Unexpected evidence fields');
  if (!b || b.nodeId !== nodeId || b.incarnation !== incarnation || b.formatVersion !== '1') throw new Error('Wrong evidence lifetime');
  const boot = uint(b.bootCounter, 32, true);
  uint(b.resetReason, 32); uint(b.startedUptimeMs, 64); uint(b.firstPossibleSequence, 64, true);
  let suffix = 'boot';
  if (kind === 1) {
    const a = p.acceptance, c = p.challenge, m = p.metadata;
    if (!a || !m || a.nodeId !== nodeId || a.incarnation !== incarnation || a.bootCounter !== boot ||
        a.version !== '1' || !/^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(a.anchorId) ||
        m.anchorId !== a.anchorId || m.uptimeMs !== a.acceptedUptimeMs || typeof p.proof !== 'string') throw new Error('Invalid anchor binding');
    uint(a.acceptedUptimeMs, 64); uint(m.uncertaintyMinusMs, 64); uint(m.uncertaintyPlusMs, 64); uint(m.driftPpmBound, 32);
    if (c && (c.nodeId !== nodeId || c.incarnation !== incarnation || c.bootCounter !== boot || c.version !== '1' ||
        BigInt(uint(c.challengeUptimeMs, 64)) < BigInt(b.startedUptimeMs) ||
        BigInt(a.acceptedUptimeMs) < BigInt(c.challengeUptimeMs) ||
        BigInt(a.acceptedUptimeMs) - BigInt(c.challengeUptimeMs) > 30_000n ||
        !p.challengeBytes || !p.acceptanceBytes)) throw new Error('Invalid challenge binding');
    suffix = a.anchorId;
  }
  return { id: `${nodeId}/${incarnation}/${boot}/${suffix}`, organizationId, nodeId, incarnation,
    bootCounter: boot, kind, payload, uploaded: false };
}

export async function persistEvidence(db: CollectorDatabase, row: StoredTimeEvidence, assertActive?: () => void): Promise<void> {
  await db.transaction('rw', db.timeEvidence, async () => {
    const old = await db.timeEvidence.get(row.id);
    assertActive?.();
    if (old) {
      if (row.ownerUserId && old.ownerUserId !== row.ownerUserId) throw new Error('Time evidence belongs to another offline account');
      if (old.organizationId !== row.organizationId || old.payload !== row.payload || old.kind !== row.kind) throw new Error('Conflicting time evidence identity');
      return; // Never reset a confirmed upload on retransmission.
    }
    await db.timeEvidence.add(row);
  });
}

export async function acknowledgeUploadedEvidence(transport: NodeTransport, rows: StoredTimeEvidence[],
  confirm: (id: string) => Promise<void>): Promise<void> {
  // Remove anchors before their old boot markers. A retained current boot is
  // retried on a later visit after the Node reboots; it is never prematurely removed.
  for (const row of [...rows].sort((a, b) => b.kind - a.kind)) {
    if (!row.uploaded || row.nodeAcknowledged) throw new Error('Evidence receipt before backend confirmation');
    const p = new Uint8Array(53), view = new DataView(p.buffer); p[0] = row.kind;
    const uuid = (value: string) => new Uint8Array(value.replaceAll('-', '').match(/../g)!.map(b => parseInt(b, 16)));
    p.set(uuid(row.nodeId), 1); p.set(uuid(row.incarnation), 17); view.setUint32(33, Number(row.bootCounter), true);
    if (row.kind === 1) p.set(uuid(parseEvidenceJson(row.payload).acceptance.anchorId), 37);
    await transport.write(encodeFrame({ version: 1, opcode: Opcode.TimeEvidenceReceiptRequest, payload: p }));
    const frame = decodeFrame(await transport.read());
    if (!frame || frame.version !== 1 || frame.opcode !== Opcode.TimeEvidenceReceiptResponse ||
        frame.payload.length !== 54 || !p.every((b, i) => frame.payload[i] === b) || frame.payload[53] > 1) throw new Error('Evidence receipt not confirmed');
    if (frame.payload[53] === 0) await confirm(row.id);
  }
}

// Independent replayable evidence stream. Node retention is deliberately not
// changed by raw ACK/Compact, so interruption or client eviction can be retried.
export async function receiveTimeEvidence(transport: NodeTransport, nodeId: string, incarnation: string,
  organizationId: string, persist: (row: StoredTimeEvidence) => Promise<void>): Promise<number> {
  let count = 0;
  for (const kind of [0, 1] as const) {
    for (let index = 0; index < (kind === 0 ? 129 : 17); index++) {
      let offset = 0, total: number | undefined;
      let bytes = new Uint8Array();
      for (;;) {
        const request = new Uint8Array(7), view = new DataView(request.buffer);
        request[0] = kind; view.setUint32(1, index, true); view.setUint16(5, offset, true);
        await transport.write(encodeFrame({ version: 1, opcode: Opcode.TimeEvidenceRequest, payload: request }));
        const response = decodeFrame(await transport.read());
        if (!response || response.version !== 1 || response.opcode !== Opcode.TimeEvidenceResponse || response.payload.length < 9) throw new Error('Evidence transfer rejected');
        const p = response.payload;
        if (!request.every((b, i) => p[i] === b)) throw new Error('Mismatched evidence cursor');
        const size = new DataView(p.buffer, p.byteOffset, p.byteLength).getUint16(7, true);
        if (size > 8192 || (total !== undefined && size !== total)) throw new Error('Invalid evidence length');
        if (size === 0) {
          if (offset || p.length !== 9) throw new Error('Interrupted evidence');
          break;
        }
        total = size;
        const chunk = p.slice(9);
        if (!chunk.length || chunk.length > 480 || offset + chunk.length > total) throw new Error('Invalid evidence fragment');
        if (!offset) bytes = new Uint8Array(total);
        bytes.set(chunk, offset); offset += chunk.length;
        if (offset === total) {
          await persist(evidenceRow(new TextDecoder('utf-8', { fatal: true }).decode(bytes), kind, nodeId, incarnation, organizationId));
          count++; break;
        }
      }
      if (!total) break;
      if (index === (kind === 0 ? 128 : 16)) throw new Error('Evidence stream exceeds bounds');
    }
  }
  return count;
}
