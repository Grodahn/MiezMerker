// @vitest-environment node
import { expect, test, vi } from 'vitest';
import { collectPhoneTime } from './phone-time';
import { decodeFrame, encodeFrame, Opcode } from './ble-codec';
import { characteristicForRequest, BLE_CHAR_UUIDS } from '../platform/web-bluetooth';
import { evidenceRequestJson } from './time-evidence';

const NODE = '44444444-4444-4444-8444-444444444444', INC = '55555555-5555-4555-9555-555555555555';
function bytes(boot = 1, uptime = 18446744073709551615n) {
  const p = new Uint8Array(45); p[0] = 1;
  for (const [offset, id] of [[1, NODE], [17, INC]] as const) p.set(id.replaceAll('-', '').match(/../g)!.map(h => parseInt(h, 16)), offset);
  const v = new DataView(p.buffer); v.setUint32(33, boot, true); v.setBigUint64(37, uptime, true); return p;
}
function peer(values = [bytes(), bytes()], failAt = -1) {
  let reads = 0;
  return { connect: vi.fn(), disconnect: vi.fn(), write: vi.fn(async (request: Uint8Array) => {
    const f = decodeFrame(request)!; expect(f.opcode).toBe(Opcode.ClockSnapshotRequest); expect(f.payload).toHaveLength(0);
  }), read: vi.fn(async () => {
    if (reads === failAt) throw new Error('BLE interrupted');
    return encodeFrame({ version: 1, opcode: Opcode.ClockSnapshotResponse, payload: values[reads++] });
  }) };
}
test('authorized read-only snapshot preserves uint64, phone UTC, epoch binding and transport diagnostic', async () => {
  const transport = peer(), row = await collectPhoneTime(transport, NODE, INC, 'org');
  const request = evidenceRequestJson(row);
  expect(request).toContain('"uptimeMs":18446744073709551615');
  const p = JSON.parse(row.payload); expect(p.utcMs).toBeGreaterThan(0); expect(p.transportDurationMs).toBeGreaterThanOrEqual(0);
  expect(p.source).toBe('PHONE_ESTIMATE'); expect(p).not.toHaveProperty('verifiedSecurityTime');
  expect(row).toMatchObject({ nodeId: NODE, incarnation: INC, bootCounter: '1', organizationId: 'org', kind: 2, uploaded: false });
  expect(transport.write).toHaveBeenCalledTimes(2);
  expect(characteristicForRequest(Opcode.ClockSnapshotRequest)).toBe(BLE_CHAR_UUIDS.batch);
});
test.each([0, 1])('BLE interruption at read %i produces no anchor', async failAt => {
  await expect(collectPhoneTime(peer(undefined, failAt), NODE, INC, 'org')).rejects.toThrow('BLE interrupted');
});
test('reboot or monotonic reset between the paired reads produces no anchor', async () => {
  await expect(collectPhoneTime(peer([bytes(1, 100n), bytes(2, 10n)]), NODE, INC, 'org')).rejects.toThrow('neu gestartet');
  await expect(collectPhoneTime(peer([bytes(1, 100n), bytes(1, 10n)]), NODE, INC, 'org')).rejects.toThrow('neu gestartet');
});
test.each(['node', 'incarnation', 'version', 'boot', 'length'])('invalid %s binding produces no anchor', async field => {
  let p = bytes();
  if (field === 'node') p[1] ^= 1;
  if (field === 'incarnation') p[17] ^= 1;
  if (field === 'version') p[0] = 2;
  if (field === 'boot') new DataView(p.buffer).setUint32(33, 0, true);
  if (field === 'length') p = p.slice(0, 44);
  await expect(collectPhoneTime(peer([p, p]), NODE, INC, 'org')).rejects.toThrow();
});
test('authorization error and context cancellation cannot produce an anchor', async () => {
  const transport = peer(); transport.read.mockResolvedValue(encodeFrame({ version: 1, opcode: Opcode.Error, payload: new Uint8Array() }));
  await expect(collectPhoneTime(transport, NODE, INC, 'org')).rejects.toThrow('abgelehnt');
  let active = true; const switched = peer(); switched.read.mockImplementation(async () => {
    active = false; return encodeFrame({ version: 1, opcode: Opcode.ClockSnapshotResponse, payload: bytes() });
  });
  await expect(collectPhoneTime(switched, NODE, INC, 'org', () => { if (!active) throw new Error('Org switched'); })).rejects.toThrow('Org switched');
});
