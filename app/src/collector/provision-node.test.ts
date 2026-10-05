// @vitest-environment node
import 'fake-indexeddb/auto';
import claimVector from '../../../protocol/fixtures/claim-advertisement-v1.hex?raw';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
const { post, auth, listeners } = vi.hoisted(() => ({ post: vi.fn(), auth: vi.fn(), listeners: new Set<() => void>() }));
vi.mock('../api/client', () => ({ api: { POST: post } }));
vi.mock('../platform/auth', () => ({ getAuthState: auth,
  subscribeAuth: (listener: () => void) => { listeners.add(listener); return () => listeners.delete(listener); } }));
import { CollectorDatabase } from '../platform/offline-store';
import { provisionNode } from './provision-node';
import { decodeFrame, encodeFrame, encodeHelloPublic, encodeOwnerResponse, encodeClaimAdvertisement, decodeClaimAdvertisement, Opcode } from './ble-codec';

const NODE = '44444444-4444-4444-8444-444444444444';
const INC = '55555555-5555-4555-9555-555555555555';
test('claim codec matches the frozen firmware/PWA advertisement vector', () => {
  const bytes = new Uint8Array(claimVector.trim().match(/../g)!.map(byte => parseInt(byte, 16)));
  const decoded = decodeClaimAdvertisement(bytes)!;
  expect(decoded.nodeId).toBe('3a3a3a3a-3a3a-4a3a-8a3a-3a3a3a3a3a3a');
  expect(decoded.timestampMillis).toBe(1790899200000n);
  expect(encodeClaimAdvertisement(decoded)).toEqual(bytes);
  expect(decodeClaimAdvertisement(bytes.slice(0, -1))).toBeNull();
});
beforeEach(async () => {
  await new CollectorDatabase().delete(); post.mockReset();
  auth.mockReturnValue({ user: { userId: 'admin', memberships: [{ organizationId: 'org-a', status: 'ACTIVE', role: 'ADMIN' }] },
    activeOrganizationId: 'org-a', csrfToken: 'csrf', sessionChecked: true, sessionVerified: true });
  Object.defineProperty(globalThis, 'navigator', { value: { onLine: true }, configurable: true });
  post.mockResolvedValue({ data: { nodeId: NODE, organizationId: 'org-a', receipt: 'signed-receipt' } });
});
afterEach(async () => { await new CollectorDatabase().delete(); });

class ClaimTransport {
  claimed = false; physicalMode = true; dropReceipt = false;
  last = new Uint8Array(); operations: Opcode[] = [];
  async connect() {} async disconnect() {} async read() { return this.last; }
  async write(bytes: Uint8Array) {
    const request = decodeFrame(bytes)!; this.operations.push(request.opcode);
    let opcode: Opcode; let payload: Uint8Array;
    switch (request.opcode) {
      case Opcode.HelloRequest:
        opcode = Opcode.HelloPublic; payload = encodeHelloPublic({ serverVer: 1, caps: 31, nodeId: NODE,
          incarnation: INC, firmwareVersion: 'test', claimState: this.claimed ? 1 : 0, clockStatus: 2 }); break;
      case Opcode.OwnerRequest:
        opcode = Opcode.OwnerResponse; payload = encodeOwnerResponse({ nodeId: NODE, claimState: this.claimed ? 1 : 0,
          organizationId: this.claimed ? 'org-a' : '', organizationName: this.claimed ? 'Org A' : '', organizationSlug: '', publicContact: '' }); break;
      case Opcode.ClaimAdvertisementRequest:
        if (!this.physicalMode) { opcode = Opcode.Error; payload = new Uint8Array(); break; }
        opcode = Opcode.ClaimAdvertisementResponse; payload = encodeClaimAdvertisement({ nodeId: NODE,
          publicKey: new Uint8Array(65).fill(4), signature: new Uint8Array(64).fill(1), timestampMillis: 1790899200000n }); break;
      case Opcode.ClaimReceiptRequest:
        const db = new CollectorDatabase();
        expect((await db.nodeMeta.get(NODE))?.claimReceipt).toBe('signed-receipt'); db.close();
        if (this.dropReceipt) { this.dropReceipt = false; throw new Error('BLE disconnected'); }
        this.claimed = true; opcode = Opcode.ClaimReceiptResponse; payload = new Uint8Array([0]); break;
      default: throw new Error('Protected request during claim');
    }
    this.last = new Uint8Array(encodeFrame({ version: 1, opcode, payload }));
  }
}

test('claims from node-signed advertisement, persists receipt before delivery, verifies ownership', async () => {
  const transport = new ClaimTransport(); await provisionNode(transport, NODE, 'org-a');
  expect(transport.claimed).toBe(true);
  expect(post.mock.calls[0][1].body.timestampMillis).toBe(1790899200000);
  const db = new CollectorDatabase(); expect((await db.nodeMeta.get(NODE))?.claimState).toBe(1); db.close();
});
test('interrupted receipt delivery resumes without another backend reservation', async () => {
  const transport = new ClaimTransport(); transport.dropReceipt = true;
  await expect(provisionNode(transport, NODE, 'org-a')).rejects.toThrow('disconnected');
  await provisionNode(transport, NODE, 'org-a'); expect(post).toHaveBeenCalledTimes(1);
});
test('MEMBER cannot read or deliver claim material', async () => {
  auth.mockReturnValue({ user: { memberships: [{ organizationId: 'org-a', status: 'ACTIVE', role: 'MEMBER' }] } });
  const transport = new ClaimTransport(); await expect(provisionNode(transport, NODE, 'org-a')).rejects.toThrow('Nur ADMIN');
  expect(transport.operations).toEqual([]); expect(post).not.toHaveBeenCalled();
});
test('inactive physical claim mode never requests a backend receipt', async () => {
  const transport = new ClaimTransport(); transport.physicalMode = false;
  await expect(provisionNode(transport, NODE, 'org-a')).rejects.toThrow('Claiming abgelehnt'); expect(post).not.toHaveBeenCalled();
});

test('a claim response received after switching organization is saved but never delivered to BLE', async () => {
  const initiatingAuth = auth();
  post.mockImplementationOnce(async () => {
    auth.mockReturnValue({ ...initiatingAuth, activeOrganizationId: 'org-b' });
    for (const listener of listeners) listener();
    return { data: { nodeId: NODE, organizationId: 'org-a', receipt: 'signed-receipt' } };
  });
  const transport = new ClaimTransport();
  await expect(provisionNode(transport, NODE, 'org-a')).rejects.toThrow('abgebrochen');
  expect(transport.operations).not.toContain(Opcode.ClaimReceiptRequest);
  expect(transport.claimed).toBe(false);
  const db = new CollectorDatabase();
  expect((await db.nodeMeta.get(NODE))?.claimReceipt).toBe('signed-receipt'); db.close();
  auth.mockReturnValue(initiatingAuth);
  await provisionNode(transport, NODE, 'org-a');
  expect(transport.claimed).toBe(true); expect(post).toHaveBeenCalledTimes(1);
  expect(listeners.size).toBe(0);
});
