// @vitest-environment node
import { beforeEach, expect, test, vi } from 'vitest';
import vector from '../../../protocol/fixtures/node-time-v1.json';
import { fromBase64Url } from '../platform/device-keys';
import { decodeFrame, encodeFrame, Opcode } from './ble-codec';
const { post, auth } = vi.hoisted(() => ({ post: vi.fn(), auth: vi.fn() }));
vi.mock('../api/client', () => ({ api: { POST: post } }));
vi.mock('../platform/auth', () => ({ getAuthState: auth }));
import { bootstrapNodeTime, bootstrapRegistrationTime } from './registration-time';

const NODE = vector.challenge.nodeId;
function uuid(value: string) { return new Uint8Array(value.replaceAll('-', '').match(/../g)!.map(b => parseInt(b, 16))); }
function challenge(uptime = 1000n) {
  const c = vector.challenge;
  const out = new Uint8Array(239), view = new DataView(out.buffer);
  view.setUint16(0, 1, true); out.set(uuid(c.nodeId), 2); out.set(uuid(c.incarnation), 18);
  view.setUint32(34, c.bootCounter, true); out.set(fromBase64Url(c.challengeNonce), 38);
  view.setBigUint64(70, uptime, true); out.set(fromBase64Url(c.devicePublicKey), 78);
  out.set(fromBase64Url(c.devicePublicKeyFingerprint), 143); out.set(fromBase64Url(c.deviceChallengeSignature), 175);
  return out;
}
function transport(payload = challenge(), accept = true) {
  let reply = new Uint8Array();
  return { connect: vi.fn(), disconnect: vi.fn(), read: vi.fn(async () => reply),
    write: vi.fn(async (bytes: Uint8Array) => {
      const f = decodeFrame(bytes)!;
      reply = new Uint8Array(encodeFrame({ version: 1,
        opcode: f.opcode === Opcode.RegistrationTimeChallengeRequest ? Opcode.RegistrationTimeChallengeResponse
          : accept ? Opcode.RegistrationTimeProofResponse : Opcode.Error,
        payload: f.opcode === Opcode.RegistrationTimeChallengeRequest ? payload : new Uint8Array([0]) }));
    }) };
}
beforeEach(() => {
  post.mockReset(); post.mockResolvedValue({ data: { proof: vector.proof } });
  auth.mockReturnValue({ user: { memberships: [{ organizationId: 'org', status: 'ACTIVE', role: 'ADMIN' }] }, csrfToken: 'csrf' });
});
test('forwards frozen signed challenge and exact proof without supplying phone UTC', async () => {
  const ble = transport(); await bootstrapRegistrationTime(ble, NODE, 'org', () => {});
  const request = post.mock.calls[0][1];
  expect(JSON.parse(request.bodySerializer(request.body)).challenge).toEqual(vector.challenge);
  expect(request.body.registrationState).toBe('UNCLAIMED');
  expect(request.headers).toEqual({ 'X-XSRF-TOKEN': 'csrf' });
  const proof = decodeFrame(ble.write.mock.calls[1][0])!;
  expect(new TextDecoder().decode(proof.payload.slice(2))).toBe(vector.proof);
});
test('preserves unsigned 64-bit uptime without Number rounding', async () => {
  await bootstrapRegistrationTime(transport(challenge(18446744073709551615n)), NODE, 'org', () => {});
  const request = post.mock.calls[0][1];
  expect(request.bodySerializer(request.body)).toContain('"challengeUptimeMs":18446744073709551615');
});
test('receipt scheduling includes a slow proof round trip without changing verifier authority', async () => {
  const timer = vi.spyOn(performance, 'now').mockReturnValueOnce(100).mockReturnValueOnce(5100);
  try {
    expect(await bootstrapRegistrationTime(transport(), NODE, 'org', () => {})).toBe(7150);
  } finally { timer.mockRestore(); }
});
test.each([new Uint8Array(238), (() => { const c = challenge(); c[2] ^= 1; return c; })()])('rejects malformed or foreign challenge before HTTP', async bytes => {
  await expect(bootstrapRegistrationTime(transport(bytes), NODE, 'org', () => {})).rejects.toThrow();
  expect(post).not.toHaveBeenCalled();
});
test('unhealthy issuer clock is concrete failure and does not submit proof', async () => {
  post.mockResolvedValue({ error: {}, response: { status: 503 } }); const ble = transport();
  await expect(bootstrapRegistrationTime(ble, NODE, 'org', () => {})).rejects.toThrow('gesunde UTC');
  expect(ble.write).toHaveBeenCalledTimes(1);
});
test('firmware rejection of forged, replayed or stale proof fails closed', async () => {
  await expect(bootstrapRegistrationTime(transport(challenge(), false), NODE, 'org', () => {})).rejects.toThrow('Issuer-Pin');
});
test.each([
  ['issuer-key-missing', 'stabiler Backend-Issuer'],
  ['utc-unconfirmed', 'Backend-UTC nicht als gesund bestätigt'],
])('authorized bootstrap diagnostic %s is actionable', async (code, message) => {
  post.mockResolvedValue({ error: {}, response: { status: 503, headers: new Headers({ 'X-MM-Time-Bootstrap': code }) } });
  const ble = transport();
  await expect(bootstrapRegistrationTime(ble, NODE, 'org', () => {})).rejects.toThrow(message);
  expect(ble.write).toHaveBeenCalledTimes(1);
});
test.each(['MEMBER', 'inactive', 'unauthenticated'])('%s cannot start time bootstrap', async role => {
  auth.mockReturnValue({ user: role === 'unauthenticated' ? null : { memberships: [
    { organizationId: 'org', role: role === 'MEMBER' ? role : 'ADMIN', status: role === 'inactive' ? 'DISABLED' : 'ACTIVE' }] } });
  const ble = transport(); await expect(bootstrapRegistrationTime(ble, NODE, 'org', () => {})).rejects.toThrow();
  expect(ble.write).not.toHaveBeenCalled(); expect(post).not.toHaveBeenCalled();
});
test('organization switch after HTTP prevents proof delivery', async () => {
  let active = true; post.mockImplementation(async () => { active = false; return { data: { proof: vector.proof } }; });
  const ble = transport();
  await expect(bootstrapRegistrationTime(ble, NODE, 'org', () => { if (!active) throw new Error('cancelled'); })).rejects.toThrow('cancelled');
  expect(ble.write).toHaveBeenCalledTimes(1);
});

test('backend time request timeout remains a time-service failure and never submits a proof', async () => {
  post.mockRejectedValue(new DOMException('The operation was aborted due to timeout', 'TimeoutError'));
  const ble = transport();
  const failure = await bootstrapRegistrationTime(ble, NODE, 'org', () => {}).catch(error => error);
  expect(failure.kind).toBe('trusted-time');
  expect(failure.message).toContain('Backend-Zeitservice nicht erreichbar');
  expect(failure.message).toContain('noch nicht bestätigt');
  expect(ble.write).toHaveBeenCalledTimes(1);
});


test('ACTIVE MEMBER normal sync reuses the signed bootstrap with CLAIMED backend authorization', async () => {
  auth.mockReturnValue({ user: { memberships: [{ organizationId: 'org', status: 'ACTIVE', role: 'MEMBER' }] }, csrfToken: 'csrf' });
  const ble = transport(); await bootstrapNodeTime(ble, NODE, 'org', () => {}, 'CLAIMED', vector.challenge.incarnation);
  const request = post.mock.calls[0][1]; expect(request.body.registrationState).toBe('CLAIMED');
  expect(JSON.parse(request.bodySerializer(request.body)).challenge).toEqual(vector.challenge);
});
test('normal sync rejects an incarnation change before backend issuance', async () => {
  await expect(bootstrapNodeTime(transport(), NODE, 'org', () => {}, 'CLAIMED', 'wrong-incarnation')).rejects.toThrow();
  expect(post).not.toHaveBeenCalled();
});
