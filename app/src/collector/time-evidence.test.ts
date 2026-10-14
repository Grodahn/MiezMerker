// @vitest-environment node
import 'fake-indexeddb/auto';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import vector from '../../../protocol/fixtures/node-time-v1.json';
import wire from '../../../protocol/fixtures/time-evidence-transport-v1.json';
import { fromBase64Url, toBase64Url } from '../platform/device-keys';
import { CollectorDatabase } from '../platform/offline-store';
import { CollectorObservationStore } from './observation-store';
import { BackendUploader } from './backend-upload';
import { decodeFrame, encodeFrame, Opcode } from './ble-codec';
import { evidenceReceiptWriteChunks, characteristicForRequest, BLE_CHAR_UUIDS } from '../platform/web-bluetooth';
import { acknowledgeUploadedEvidence, evidenceRequestJson, evidenceRow, parseEvidenceJson, receiveTimeEvidence } from './time-evidence';

const { post, auth, changed } = vi.hoisted(() => ({ post: vi.fn(), auth: vi.fn(), changed: { callback: () => {} } }));
vi.mock('../api/client', () => ({ api: { POST: post } }));
vi.mock('../platform/auth', () => ({ getAuthState: auth, fetchCsrfToken: async () => 'csrf',
  subscribeAuth: (callback: () => void) => { changed.callback = callback; return () => {}; }, markSessionExpired: vi.fn() }));
const NODE = vector.challenge.nodeId, INC = vector.challenge.incarnation, ORG = 'org';
const boot = { formatVersion: 1, nodeId: NODE, incarnation: INC, bootCounter: 42,
  resetReason: 1, startedUptimeMs: 0, firstPossibleSequence: 1 };
function signedBytes(hex: string, signature: string) {
  const message = new Uint8Array(hex.match(/../g)!.map(b => parseInt(b, 16))), sig = fromBase64Url(signature);
  const bytes = new Uint8Array(message.length + sig.length); bytes.set(message); bytes.set(sig, message.length); return toBase64Url(bytes);
}
function anchorPayload() {
  return JSON.stringify({ boot, challenge: vector.challenge, proof: vector.proof, acceptance: vector.acceptance,
    challengeBytes: signedBytes(vector.challengeSigningHex, vector.challenge.deviceChallengeSignature),
    acceptanceBytes: signedBytes(vector.acceptanceSigningHex, vector.acceptance.signature),
    metadata: { anchorId: vector.acceptance.anchorId, uptimeMs: vector.acceptance.acceptedUptimeMs, utcMs: vector.expectedUtcMs,
      source: 0, formatVersion: 1, verifiedSecurityTime: true, proofIdOrDigest: "77777777-7777-4777-8777-777777777777", uncertaintyMinusMs: 1001, uncertaintyPlusMs: 1301, driftPpmBound: 1000 } });
}
function peer(payloads: string[], failAt = -1) {
  let calls = 0, response = new Uint8Array();
  return { connect: vi.fn(), disconnect: vi.fn(), read: vi.fn(async () => response),
    write: vi.fn(async (bytes: Uint8Array) => {
      if (calls++ === failAt) throw new Error('disconnected');
      const f = decodeFrame(bytes)!, p = f.payload, v = new DataView(p.buffer, p.byteOffset, p.byteLength);
      const index = v.getUint32(1, true), offset = v.getUint16(5, true);
      const value = payloads[p[0] === 0 ? (index === 0 ? 0 : -1) : (index === 0 ? 1 : -1)];
      const data = new TextEncoder().encode(value ?? ''), chunk = data.slice(offset, offset + 480);
      const out = new Uint8Array(9 + chunk.length); out.set(p); new DataView(out.buffer).setUint16(7, data.length, true); out.set(chunk, 9);
      response = new Uint8Array(encodeFrame({ version: 1, opcode: Opcode.TimeEvidenceResponse, payload: out }));
    }) };
}
let db: CollectorDatabase, store: CollectorObservationStore;
beforeEach(async () => {
  db = new CollectorDatabase(`evidence-${crypto.randomUUID()}`); store = new CollectorObservationStore(db); await store.open();
  post.mockReset(); auth.mockReturnValue({ user: { userId: 'user', memberships: [{ organizationId: ORG, status: 'ACTIVE', role: 'MEMBER' }] }, activeOrganizationId: ORG });
  Object.defineProperty(globalThis, 'navigator', { configurable: true, value: { onLine: true } });
});
afterEach(async () => { db.close(); await db.delete(); });

test('golden signed evidence matches existing backend request and excludes local metadata', () => {
  const row = evidenceRow(anchorPayload(), 1, NODE, INC, ORG), body = JSON.parse(evidenceRequestJson(row));
  expect(body.challenge).toEqual(vector.challenge); expect(body.acceptance).toEqual(vector.acceptance);
  expect(body.proof).toBe(vector.proof); expect(body.organizationId).toBe(ORG); expect(body.metadata).toBeUndefined(); expect(JSON.parse(body.nodeMetadata).utcMs).toBe(vector.expectedUtcMs);
});
test('uint64 maximum survives BLE, IndexedDB reopen and raw HTTP serialization', async () => {
  const payload = JSON.stringify({ boot }).replace('"startedUptimeMs":0', '"startedUptimeMs":18446744073709551615')
    .replace('"firstPossibleSequence":1', '"firstPossibleSequence":18446744073709551615');
  await receiveTimeEvidence(peer([payload]), NODE, INC, ORG, row => store.putTimeEvidence(row));
  db.close(); await db.open(); const [row] = await store.pendingTimeEvidence(ORG);
  expect(row.payload).toBe(payload); expect(evidenceRequestJson(row)).toContain('"firstPossibleSequence":18446744073709551615');
  expect(parseEvidenceJson(row.payload).boot.startedUptimeMs).toBe('18446744073709551615');
});
test('interrupted anchor transfer leaves durable boot and retries exact evidence after reopen', async () => {
  const values = [JSON.stringify({ boot }), anchorPayload()];
  await expect(receiveTimeEvidence(peer(values, 3), NODE, INC, ORG, r => store.putTimeEvidence(r))).rejects.toThrow('disconnected');
  expect(await store.pendingTimeEvidence(ORG)).toHaveLength(1);
  db.close(); await db.open();
  await receiveTimeEvidence(peer(values), NODE, INC, ORG, r => store.putTimeEvidence(r));
  await receiveTimeEvidence(peer(values), NODE, INC, ORG, r => store.putTimeEvidence(r));
  expect(await store.pendingTimeEvidence(ORG)).toHaveLength(2);
});
test('conflicting retries and wrong lifetime never overwrite evidence', async () => {
  const row = evidenceRow(anchorPayload(), 1, NODE, INC, ORG); await store.putTimeEvidence(row);
  await expect(store.putTimeEvidence({ ...row, payload: row.payload.replace('"utcMs":1791748800123', '"utcMs":1791748800124') })).rejects.toThrow('Conflicting');
  await expect(store.putTimeEvidence({ ...row, organizationId: 'foreign' })).rejects.toThrow('Conflicting');
  expect(() => evidenceRow(row.payload, 1, NODE, 'wrong-boot', ORG)).toThrow();
  expect(await store.pendingTimeEvidence(ORG)).toEqual([row]);
});
test('repeated upload, lost HTTP response and mismatched receipt retain evidence until matching confirmation', async () => {
  const row = evidenceRow(anchorPayload(), 1, NODE, INC, ORG); await store.putTimeEvidence(row);
  const uploader = new BackendUploader(store, { maxRetries: 0 }); post.mockRejectedValueOnce(new Error('response lost'));
  expect((await uploader.upload(ORG)).state).toBe('failed'); expect(await store.pendingTimeEvidence(ORG)).toHaveLength(1);
  post.mockResolvedValueOnce({ data: { anchorId: 'wrong-node', status: 'CREATED', version: 1, trustStatus: 'DEVICE_ACCEPTANCE_VERIFIED' } });
  expect((await uploader.upload(ORG)).state).toBe('failed');
  post.mockResolvedValueOnce({ data: { anchorId: vector.acceptance.anchorId, status: 'DUPLICATE_IDENTICAL', version: 1, trustStatus: 'DEVICE_ACCEPTANCE_VERIFIED' } });
  expect((await uploader.upload(ORG)).state).toBe('complete');
  expect(post.mock.calls[2][1].bodySerializer()).toBe(evidenceRequestJson(row));
  await store.putTimeEvidence(row); expect(await store.pendingTimeEvidence(ORG)).toHaveLength(0);
  expect(await db.timeEvidence.count()).toBe(1);
});
test('pre-claim continuity rejection is explained, preserves exact evidence after reopen, and does not block other evidence', async () => {
  const row = evidenceRow(anchorPayload(), 1, NODE, INC, ORG); await store.putTimeEvidence(row);
  const bootRow = evidenceRow(JSON.stringify({ boot }), 0, NODE, INC, ORG); await store.putTimeEvidence(bootRow);
  post.mockImplementation(async endpoint => endpoint.endsWith('/time-evidence')
    ? { error: {}, response: { status: 403, headers: new Headers({ 'X-MM-Time-Evidence': 'PRE_CLAIM_CONTINUITY_UNVERIFIED' }) } }
    : { data: { nodeId: NODE, incarnation: INC, bootCounter: boot.bootCounter, version: 1, status: 'CREATED' } });
  const result = await new BackendUploader(store, { maxRetries: 0 }).upload(ORG);
  expect(result.state).toBe('failed'); expect(result.message).toContain('Zeitbeleg vor Registrierung noch nicht prüfbar.');
  expect(result.message).not.toContain('HTTP 403'); expect(result.message).toContain('unbestätigt');
  expect(post).toHaveBeenCalledTimes(2);
  db.close(); await db.open();
  expect(await store.pendingTimeEvidence(ORG)).toEqual([row]);
  expect((await store.uploadedTimeEvidence(ORG, NODE, INC)).map(r => r.id)).toEqual([bootRow.id]);
});

test('a generic 403 keeps the permission diagnostic and never confirms evidence', async () => {
  const row = evidenceRow(anchorPayload(), 1, NODE, INC, ORG); await store.putTimeEvidence(row);
  post.mockResolvedValue({ error: {}, response: { status: 403, headers: new Headers() } });
  const result = await new BackendUploader(store, { maxRetries: 0 }).upload(ORG);
  expect(result.message).toContain('HTTP 403'); expect(await store.pendingTimeEvidence(ORG)).toEqual([row]);
});

test.each(['offline', 'logout', 'org-switch'])('%s preserves pending signed evidence', async reason => {
  const row = evidenceRow(anchorPayload(), 1, NODE, INC, ORG); await store.putTimeEvidence(row);
  if (reason === 'offline') Object.defineProperty(globalThis, 'navigator', { configurable: true, value: { onLine: false } });
  else post.mockImplementationOnce(async () => {
    auth.mockReturnValue({ user: null, activeOrganizationId: reason === 'logout' ? null : 'other' }); changed.callback();
    return { data: { anchorId: vector.acceptance.anchorId, status: 'CREATED', version: 1, trustStatus: 'DEVICE_ACCEPTANCE_VERIFIED' } };
  });
  expect((await new BackendUploader(store, { maxRetries: 0 }).upload(ORG)).state).not.toBe('complete');
  expect(await store.pendingTimeEvidence(ORG)).toEqual([row]);
});
test('multiple boot epochs remain separate and legacy anchors are retained without invented challenge', async () => {
  await store.putTimeEvidence(evidenceRow(JSON.stringify({ boot }), 0, NODE, INC, ORG));
  await store.putTimeEvidence(evidenceRow(JSON.stringify({ boot: { ...boot, bootCounter: 43 } }), 0, NODE, INC, ORG));
  const p = JSON.parse(anchorPayload()); p.challenge = null; p.challengeBytes = ''; p.acceptanceBytes = '';
  const row = evidenceRow(JSON.stringify(p), 1, NODE, INC, ORG); await store.putTimeEvidence(row);
  expect(() => evidenceRequestJson(row)).toThrow('Historischer'); expect(await db.timeEvidence.count()).toBe(3);
});




test('additive BLE evidence golden frame and signed anchor fixture remain byte-exact', async () => {
  const hex = (bytes: Uint8Array) => Array.from(bytes, b => b.toString(16).padStart(2, '0')).join('');
  const transport = peer([wire.bootPayload, wire.anchorPayload]);
  await receiveTimeEvidence(transport, NODE, INC, ORG, row => store.putTimeEvidence(row));
  expect(hex(transport.write.mock.calls[0][0])).toBe(wire.bootRequestHex);
  const response = fromBase64Url(toBase64Url(new Uint8Array(wire.bootResponseHex.match(/../g)!.map(b => parseInt(b,16)))));
  expect(decodeFrame(response)?.opcode).toBe(Opcode.TimeEvidenceResponse);
  expect((await store.pendingTimeEvidence(ORG)).find(r => r.kind === 1)?.payload).toBe(wire.anchorPayload);
});

test('independent evidence receipt requires durable backend confirmation and survives lost node response', async () => {
  const row = evidenceRow(anchorPayload(), 1, NODE, INC, ORG); await store.putTimeEvidence(row);
  let response = new Uint8Array(), lose = true;
  const transport = { connect: vi.fn(), disconnect: vi.fn(), read: vi.fn(async () => {
    if (lose) throw new Error('receipt response lost'); return response;
  }), write: vi.fn(async (bytes: Uint8Array) => {
    const f = decodeFrame(bytes)!; expect(f.opcode).toBe(Opcode.TimeEvidenceReceiptRequest);
    const p = new Uint8Array(54); p.set(f.payload); response = new Uint8Array(encodeFrame({ version: 1, opcode: Opcode.TimeEvidenceReceiptResponse, payload: p }));
  }) };
  await expect(acknowledgeUploadedEvidence(transport, [row], id => store.confirmNodeEvidenceReceipt(id))).rejects.toThrow('before backend');
  expect(transport.write).not.toHaveBeenCalled();
  await store.confirmTimeEvidence(row.id);
  const uploaded = await store.uploadedTimeEvidence(ORG, NODE, INC);
  await expect(acknowledgeUploadedEvidence(transport, uploaded, id => store.confirmNodeEvidenceReceipt(id))).rejects.toThrow('lost');
  expect(await store.uploadedTimeEvidence(ORG, NODE, INC)).toHaveLength(1);
  lose = false; await acknowledgeUploadedEvidence(transport, uploaded, id => store.confirmNodeEvidenceReceipt(id));
  expect(await store.uploadedTimeEvidence(ORG, NODE, INC)).toHaveLength(0);
  expect((await db.timeEvidence.get(row.id))?.payload).toBe(row.payload);
  const frame = transport.write.mock.calls.at(-1)![0];
  const chunks = evidenceReceiptWriteChunks(frame);
  expect(characteristicForRequest(Opcode.TimeEvidenceReceiptRequest)).toBe(BLE_CHAR_UUIDS.batch);
  expect(chunks.every(c => c.length <= 20 && c[0] === 0x4f)).toBe(true);
  expect(chunks.flatMap(c => Array.from(c.slice(8)))).toEqual(Array.from(frame));
  expect(() => evidenceReceiptWriteChunks(encodeFrame({ version: 1, opcode: Opcode.TimeEvidenceReceiptRequest, payload: new Uint8Array(54) }))).toThrow();
});
