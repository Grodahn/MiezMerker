// @vitest-environment node
import { beforeEach, expect, test, vi } from 'vitest';
import { toBase64Url } from '../platform/device-keys';
import { encodeClaimAdvertisement, encodeFrame, Opcode } from './ble-codec';
const { session, hello } = vi.hoisted(() => ({ session: vi.fn(), hello: vi.fn() }));
vi.mock('../platform/auth', () => ({ getAuthState: session, subscribeAuth: () => () => {} }));
vi.mock('./public-probe', () => ({ PublicProbeTransport: class { readPublicHello = hello; } }));
import { verifyRegistrationCandidate } from './registration-probe';
const NODE = '44444444-4444-4444-8444-444444444444';
beforeEach(() => {
  session.mockReturnValue({ user: { userId: 'admin', memberships: [{ organizationId: 'org', status: 'ACTIVE', role: 'ADMIN' }] }, activeOrganizationId: 'org' });
  hello.mockResolvedValue({ nodeId: NODE, claimState: 0 });
});
async function transport(tamper = false) {
  const key = await crypto.subtle.generateKey({ name: 'ECDSA', namedCurve: 'P-256' }, true, ['sign', 'verify']);
  const publicKey = new Uint8Array(await crypto.subtle.exportKey('raw', key.publicKey));
  const timestampMillis = BigInt(Date.now());
  const x = toBase64Url(publicKey.slice(1, 33)), y = toBase64Url(publicKey.slice(33));
  const message = new TextEncoder().encode(`MM-CLAIM-v1\n${NODE}\n${x}\n${y}\n${timestampMillis}\nclaim-mode`);
  const signature = new Uint8Array(await crypto.subtle.sign({ name: 'ECDSA', hash: 'SHA-256' }, key.privateKey, message));
  if (tamper) signature[0] ^= 1;
  return { connect: vi.fn().mockResolvedValue(undefined), disconnect: vi.fn().mockResolvedValue(undefined),
    write: vi.fn().mockResolvedValue(undefined), read: vi.fn().mockResolvedValue(encodeFrame({ version: 1,
      opcode: Opcode.ClaimAdvertisementResponse, payload: encodeClaimAdvertisement({ nodeId: NODE, publicKey, timestampMillis, signature }) })) };
}
test('real signed claim advertisement verifies without server mutations', async () => {
  const ble = await transport();
  await verifyRegistrationCandidate(ble, NODE, 'org');
  expect(ble.write).toHaveBeenCalledTimes(1);
  expect(ble.disconnect).toHaveBeenCalled();
});
test('forged signature fails closed and disconnects', async () => {
  const ble = await transport(true);
  await expect(verifyRegistrationCandidate(ble, NODE, 'org')).rejects.toThrow('Signatur');
  expect(ble.disconnect).toHaveBeenCalled();
});
test('missing signed UTC bootstrap fails closed', async () => {
  const ble = await transport(); ble.read.mockResolvedValue(encodeFrame({ version: 1, opcode: Opcode.Error, payload: new Uint8Array([1]) }));
  await expect(verifyRegistrationCandidate(ble, NODE, 'org')).rejects.toThrow('Vertrauenswürdige UTC');
});
test('spoofed public UUID is not accepted', async () => {
  hello.mockResolvedValue({ nodeId: 'other', claimState: 0 });
  const ble = await transport();
  await expect(verifyRegistrationCandidate(ble, NODE, 'org')).rejects.toThrow('Status geändert');
  expect(ble.write).not.toHaveBeenCalled();
});
test.each(['MEMBER', 'SYSADMIN'])('%s alone cannot probe claim material', async role => {
  session.mockReturnValue({ user: { userId: 'user', systemRole: role, memberships: role === 'MEMBER' ? [{ organizationId: 'org', status: 'ACTIVE', role }] : [] }, activeOrganizationId: 'org' });
  const ble = await transport();
  await expect(verifyRegistrationCandidate(ble, NODE, 'org')).rejects.toThrow();
  expect(ble.connect).not.toHaveBeenCalled();
});


test.each([false,true])('alpha preflight still verifies Node claim signature (tampered=%s)', async tamper => {
  vi.stubEnv('VITE_MM_SECURITY_CLOCK_POLICY', undefined);
  try {
    hello.mockResolvedValue({ nodeId: NODE, claimState: 0, caps: 0x80 });
    const ble=await transport(tamper);
    const proof=await ble.read();
    ble.read.mockResolvedValueOnce(encodeFrame({ version: 1, opcode: Opcode.AlphaPhoneClockResponse, payload: new Uint8Array([0]) })).mockResolvedValueOnce(proof);
    if (tamper) await expect(verifyRegistrationCandidate(ble,NODE,'org')).rejects.toThrow('Signatur');
    else await expect(verifyRegistrationCandidate(ble,NODE,'org')).resolves.toBeUndefined();
    expect(ble.write.mock.calls[0][0][1]).toBe(Opcode.AlphaPhoneClockRequest);
    expect(ble.write).toHaveBeenCalledTimes(2);
  } finally { vi.unstubAllEnvs(); }
});

test('malformed identity advertisement after Alpha time preflight gets the specific support code', async () => {
  vi.stubEnv('VITE_MM_SECURITY_CLOCK_POLICY', 'FIELD_ALPHA_PHONE_CLOCK');
  try {
    hello.mockResolvedValue({ nodeId: NODE, claimState: 0, caps: 0x80 });
    const ble = await transport();
    ble.read.mockResolvedValueOnce(encodeFrame({ version: 1, opcode: Opcode.AlphaPhoneClockResponse, payload: new Uint8Array([0]) }))
      .mockResolvedValueOnce(encodeFrame({ version: 1, opcode: Opcode.ClaimAdvertisementResponse, payload: new Uint8Array() }));
    await expect(verifyRegistrationCandidate(ble, NODE, 'org')).rejects.toMatchObject({ code: 'MM-CLAIM-004' });
    expect(ble.disconnect).toHaveBeenCalled();
  } finally { vi.unstubAllEnvs(); }
});
