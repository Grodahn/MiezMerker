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
test('real signed physical claim advertisement verifies without server mutations', async () => {
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
test('missing physical claim mode / UTC proof fails closed', async () => {
  const ble = await transport(); ble.read.mockResolvedValue(encodeFrame({ version: 1, opcode: Opcode.Error, payload: new Uint8Array([1]) }));
  await expect(verifyRegistrationCandidate(ble, NODE, 'org')).rejects.toThrow('vertrauenswürdige UTC');
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
