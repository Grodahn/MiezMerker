// @vitest-environment node
import { beforeEach, expect, test, vi } from 'vitest';
import { toBase64Url } from '../platform/device-keys';
import { encodeClaimAdvertisement, encodeFrame, encodeHelloPublic, encodeOwnerResponse, decodeFrame, Opcode } from './ble-codec';
const { auth } = vi.hoisted(() => ({ auth: vi.fn() }));
vi.mock('../platform/auth', () => ({ getAuthState: auth, subscribeAuth: () => () => {} }));
import { verifyRegistrationCandidate } from './registration-probe';

const NODE = '44444444-4444-4444-8444-444444444444';
beforeEach(() => {
  auth.mockReturnValue({ user: { userId: 'admin', memberships: [{ organizationId: 'org', role: 'ADMIN', status: 'ACTIVE' }] }, activeOrganizationId: 'org' });
});

async function transport(options: { forged?: boolean; foreignOwner?: boolean } = {}) {
  const key = await crypto.subtle.generateKey({ name: 'ECDSA', namedCurve: 'P-256' }, true, ['sign', 'verify']);
  const publicKey = new Uint8Array(await crypto.subtle.exportKey('raw', key.publicKey));
  const x = toBase64Url(publicKey.slice(1, 33)), y = toBase64Url(publicKey.slice(33));
  const timestampMillis = BigInt(Date.now());
  const signature = new Uint8Array(await crypto.subtle.sign({ name: 'ECDSA', hash: 'SHA-256' }, key.privateKey,
    new TextEncoder().encode(`MM-CLAIM-v1\n${NODE}\n${x}\n${y}\n${timestampMillis}\nclaim-mode`)));
  if (options.forged) signature[0] ^= 1;
  let connected = false, response = new Uint8Array(), connects = 0;
  const operations: Opcode[] = [];
  return {
    operations, get connected() { return connected; },
    connect: vi.fn(async () => {
      if (++connects > 1) throw new Error('Android GATT teardown is still pending');
      connected = true;
    }),
    disconnect: vi.fn(async () => { connected = false; }),
    read: vi.fn(async () => { if (!connected) throw new Error('Disconnected'); return response; }),
    write: vi.fn(async (bytes: Uint8Array) => {
      if (!connected) throw new Error('Disconnected');
      const op = decodeFrame(bytes)!.opcode; operations.push(op);
      let payload: Uint8Array;
      switch (op) {
        case Opcode.HelloRequest:
          payload = encodeHelloPublic({ nodeId: NODE, incarnation: '55555555-5555-4555-8555-555555555555',
            serverVer: 1, caps: 31, claimState: 0, clockStatus: 2, firmwareVersion: 'test' }); break;
        case Opcode.OwnerRequest:
          payload = encodeOwnerResponse({ nodeId: options.foreignOwner ? '66666666-6666-4666-8666-666666666666' : NODE,
            claimState: 0, organizationId: '', organizationSlug: '', organizationName: '', publicContact: '' }); break;
        case Opcode.ClaimAdvertisementRequest:
          payload = encodeClaimAdvertisement({ nodeId: NODE, publicKey, timestampMillis, signature }); break;
        default: throw new Error('Unexpected protected or claiming operation during preflight');
      }
      response = new Uint8Array(encodeFrame({ version: 1, opcode: op + 1, payload }));
    }),
  };
}

test('real public probe and signature verification share one connection until cleanup', async () => {
  const ble = await transport();
  await verifyRegistrationCandidate(ble, NODE, 'org');
  expect(ble.connect).toHaveBeenCalledTimes(1);
  expect(ble.disconnect).toHaveBeenCalledTimes(1);
  expect(ble.operations).toEqual([Opcode.HelloRequest, Opcode.OwnerRequest, Opcode.ClaimAdvertisementRequest]);
  expect(ble.connected).toBe(false);
});
test('forged identity still rejects and closes the retained connection', async () => {
  const ble = await transport({ forged: true });
  await expect(verifyRegistrationCandidate(ble, NODE, 'org')).rejects.toThrow('Signatur');
  expect(ble.connected).toBe(false);
});
test('invalid public identity closes before any signature or claim request', async () => {
  const ble = await transport({ foreignOwner: true });
  await expect(verifyRegistrationCandidate(ble, NODE, 'org')).rejects.toThrow('öffentliche Node-Identität');
  expect(ble.operations).toEqual([Opcode.HelloRequest, Opcode.OwnerRequest]);
  expect(ble.connected).toBe(false);
});
