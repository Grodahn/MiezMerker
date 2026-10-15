// @vitest-environment node
import 'fake-indexeddb/auto';
import claimVector from '../../../protocol/fixtures/claim-advertisement-v1.hex?raw';
import timeVector from '../../../protocol/fixtures/node-time-v1.json';
import { fromBase64Url } from '../platform/device-keys';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
const { post, auth, listeners } = vi.hoisted(() => ({ post: vi.fn(), auth: vi.fn(), listeners: new Set<() => void>() }));
vi.mock('../api/client', () => ({ api: { POST: post } }));
vi.mock('./collector-sync', () => ({ runFieldSync: vi.fn() }));
vi.mock('../platform/auth', () => ({ getAuthState: auth,
  subscribeAuth: (listener: () => void) => { listeners.add(listener); return () => listeners.delete(listener); } }));
import { CollectorDatabase } from '../platform/offline-store';
import { provisionNode } from './provision-node';
import { runFieldSync } from './collector-sync';
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
  post.mockImplementation(async path => ({ data: path === '/api/v1/nodes/time-bootstrap'
    ? { proof: timeVector.proof } : { nodeId: NODE, organizationId: 'org-a', receipt: 'signed-receipt' } }));
});
afterEach(async () => { await new CollectorDatabase().delete(); });

class ClaimTransport {
  claimed = false; trustedTime = true; dropReceipt = false; bootstrapAvailable = true; rejectReceiptOnce = false;
  rejectReconnect = false; connections = 0;
  phoneTime = false; alphaClock = false;
  last: Uint8Array = new Uint8Array(); operations: Opcode[] = [];
  async connect() {
    if (++this.connections > 1 && this.rejectReconnect) throw new Error('Previous GATT connection is closing');
  }
  async disconnect() {} async read() { return this.last; }
  async write(bytes: Uint8Array) {
    const request = decodeFrame(bytes)!; this.operations.push(request.opcode);
    let opcode: Opcode; let payload: Uint8Array;
    switch (request.opcode) {
      case Opcode.HelloRequest:
        opcode = Opcode.HelloPublic; payload = encodeHelloPublic({ serverVer: 1, caps: 31 | (this.phoneTime ? 64 : 0) | (this.alphaClock ? 128 : 0), nodeId: NODE,
          incarnation: INC, firmwareVersion: 'test', claimState: this.claimed ? 1 : 0, clockStatus: 2 }); break;
      case Opcode.OwnerRequest:
        opcode = Opcode.OwnerResponse; payload = encodeOwnerResponse({ nodeId: NODE, claimState: this.claimed ? 1 : 0,
          organizationId: this.claimed ? 'org-a' : '', organizationName: this.claimed ? 'Org A' : '', organizationSlug: '', publicContact: '' }); break;
      case Opcode.ClaimAdvertisementRequest:
        if (!this.trustedTime) { opcode = Opcode.Error; payload = new Uint8Array(); break; }
        opcode = Opcode.ClaimAdvertisementResponse; payload = encodeClaimAdvertisement({ nodeId: NODE,
          publicKey: new Uint8Array(65).fill(4), signature: new Uint8Array(64).fill(1), timestampMillis: 1790899200000n }); break;
      case Opcode.RegistrationTimeChallengeRequest: {
        if (!this.bootstrapAvailable) { opcode = Opcode.Error; payload = new Uint8Array(); break; }
        opcode = Opcode.RegistrationTimeChallengeResponse; payload = new Uint8Array(239);
        const view = new DataView(payload.buffer), c = timeVector.challenge;
        const uuid = (value: string) => new Uint8Array(value.replaceAll('-', '').match(/../g)!.map(b => parseInt(b, 16)));
        view.setUint16(0, 1, true); payload.set(uuid(NODE), 2); payload.set(uuid(c.incarnation), 18);
        view.setUint32(34, c.bootCounter, true); payload.set(fromBase64Url(c.challengeNonce), 38);
        view.setBigUint64(70, 1000n, true); payload.set(fromBase64Url(c.devicePublicKey), 78);
        payload.set(fromBase64Url(c.devicePublicKeyFingerprint), 143); payload.set(fromBase64Url(c.deviceChallengeSignature), 175);
        break;
      }
      case Opcode.AlphaPhoneClockRequest:
        this.trustedTime=true; opcode=Opcode.AlphaPhoneClockResponse; payload=new Uint8Array([0]); break;
      case Opcode.RegistrationTimeProofRequest:
        this.trustedTime = true; opcode = Opcode.RegistrationTimeProofResponse; payload = new Uint8Array([0]); break;
      case Opcode.ClaimReceiptRequest:
        const db = new CollectorDatabase();
        expect((await db.nodeMeta.get(NODE))?.claimReceipt).toBe('signed-receipt'); db.close();
        if (this.dropReceipt) { this.dropReceipt = false; throw new Error('BLE disconnected'); }
        if (this.rejectReceiptOnce) { this.rejectReceiptOnce = false; opcode = Opcode.Error; payload = new Uint8Array(); break; }
        this.claimed = true; opcode = Opcode.ClaimReceiptResponse; payload = new Uint8Array([0]); break;
      default: throw new Error('Protected request during claim');
    }
    this.last = new Uint8Array(encodeFrame({ version: 1, opcode, payload }));
  }
}

test('initial phone anchor reuses ordinary authenticated field sync only after confirmed claim', async () => {
  const transport=new ClaimTransport(); transport.phoneTime=true;
  vi.mocked(runFieldSync).mockImplementationOnce(async callbacks => {
    expect(transport.claimed).toBe(true); expect(transport.operations.at(-1)).toBe(Opcode.OwnerRequest);
    expect(await callbacks.createTransport()).toBeDefined();
    return { nodeState: 'complete' } as never;
  });
  await provisionNode(transport,NODE,'org-a'); expect(runFieldSync).toHaveBeenCalled();
});
test('claim stays committed if initial authenticated time capture needs a retry', async () => {
  const transport=new ClaimTransport(); transport.phoneTime=true;
  vi.mocked(runFieldSync).mockResolvedValueOnce({ nodeState: 'failed', nodeMessage: 'credential unavailable' } as never);
  await expect(provisionNode(transport,NODE,'org-a')).resolves.toEqual({ timeAnchorPending: true, message: 'Napf registriert; erste Zeitaufnahme noch ausstehend. Bitte erneut auslesen. credential unavailable' });
  const db=new CollectorDatabase(); expect((await db.nodeMeta.get(NODE))?.claimState).toBe(1); db.close();
});

test('claims from node-signed advertisement, persists receipt before delivery, verifies ownership', async () => {
  const transport = new ClaimTransport(); await provisionNode(transport, NODE, 'org-a');
  expect(transport.claimed).toBe(true);
  expect(post.mock.calls[0][1].body.timestampMillis).toBe(1790899200000);
  const db = new CollectorDatabase(); expect((await db.nodeMeta.get(NODE))?.claimState).toBe(1); db.close();
});

test('receipt delivery and matching post-claim Hello/Owner use one connection even when reconnect would fail', async () => {
  const transport = new ClaimTransport(); transport.rejectReconnect = true;
  const disconnect = vi.spyOn(transport, 'disconnect');
  await provisionNode(transport, NODE, 'org-a');
  expect(transport.claimed).toBe(true);
  expect(transport.connections).toBe(1);
  expect(disconnect).toHaveBeenCalledTimes(1);
  expect(transport.operations.slice(-2)).toEqual([Opcode.HelloRequest, Opcode.OwnerRequest]);
});

test.each(['owner', 'incarnation', 'claimState'] as const)('post-receipt mismatched %s is not marked confirmed', async mismatch => {
  const transport = new ClaimTransport();
  const write = transport.write.bind(transport);
  transport.write = async bytes => {
    await write(bytes);
    const opcode = decodeFrame(bytes)!.opcode;
    if (!transport.claimed) return;
    if (mismatch === 'owner' && opcode === Opcode.OwnerRequest) {
      transport.last = encodeFrame({ version: 1, opcode: Opcode.OwnerResponse, payload: encodeOwnerResponse({
        nodeId: NODE, claimState: 1, organizationId: 'other-org', organizationName: '', organizationSlug: '', publicContact: '' }) });
    } else if (mismatch !== 'owner' && opcode === Opcode.HelloRequest) {
      transport.last = encodeFrame({ version: 1, opcode: Opcode.HelloPublic, payload: encodeHelloPublic({
        serverVer: 1, caps: 31, nodeId: NODE, incarnation: mismatch === 'incarnation' ? NODE : INC,
        firmwareVersion: 'test', claimState: mismatch === 'claimState' ? 0 : 1, clockStatus: 2 }) });
    }
  };
  await expect(provisionNode(transport, NODE, 'org-a')).rejects.toThrow();
  const db = new CollectorDatabase();
  expect((await db.nodeMeta.get(NODE))?.claimState).toBe(0);
  expect((await db.nodeMeta.get(NODE))?.claimReceipt).toBe('signed-receipt');
  db.close();
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
test('missing trusted time cannot obtain a claim receipt without signed bootstrap', async () => {
  const transport = new ClaimTransport(); transport.trustedTime = false; transport.bootstrapAvailable = false;
  await expect(provisionNode(transport, NODE, 'org-a')).rejects.toThrow('Vertrauenswürdige UTC'); expect(post).not.toHaveBeenCalled();
});

test('cold UNCLAIMED registration installs signed time, claims, and updates durable state without button interaction', async () => {
  const transport = new ClaimTransport(); transport.trustedTime = false;
  await provisionNode(transport, NODE, 'org-a');
  expect(post.mock.calls.map(call => call[0])).toEqual(['/api/v1/nodes/time-bootstrap', '/api/v1/nodes/claim']);
  expect(transport.claimed).toBe(true);
  const db = new CollectorDatabase(); expect((await db.nodeMeta.get(NODE))?.claimState).toBe(1); db.close();
});

test('reboot after interrupted reservation restores time and delivers the cached receipt', async () => {
  const transport = new ClaimTransport(); transport.dropReceipt = true;
  await expect(provisionNode(transport, NODE, 'org-a')).rejects.toThrow('disconnected');
  transport.trustedTime = false; await provisionNode(transport, NODE, 'org-a');
  expect(post.mock.calls.filter(call => call[0] === '/api/v1/nodes/claim')).toHaveLength(1);
  expect(transport.claimed).toBe(true);
});

test('expired receipt or wide old time interval triggers one secure bootstrap and same-owner renewal', async () => {
  const transport = new ClaimTransport(); transport.rejectReceiptOnce = true;
  await provisionNode(transport, NODE, 'org-a');
  expect(post.mock.calls.map(call => call[0])).toEqual(['/api/v1/nodes/claim', '/api/v1/nodes/time-bootstrap', '/api/v1/nodes/claim']);
  expect(transport.claimed).toBe(true);
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


test('alpha workshop claim needs no signed UTC bootstrap and preserves backend reservation/receipt', async () => {
  vi.stubEnv('VITE_MM_SECURITY_CLOCK_POLICY','FIELD_ALPHA_PHONE_CLOCK');
  try {
    const transport=new ClaimTransport(); transport.alphaClock=true; transport.trustedTime=false;
    await provisionNode(transport,NODE,'org-a');
    expect(transport.claimed).toBe(true);
    expect(transport.operations).toContain(Opcode.AlphaPhoneClockRequest);
    expect(transport.operations).not.toContain(Opcode.RegistrationTimeChallengeRequest);
    expect(transport.operations).not.toContain(Opcode.RegistrationTimeProofRequest);
    expect(post.mock.calls.some(([path]) => path === '/api/v1/nodes/time-bootstrap')).toBe(false);
    expect(post.mock.calls.some(([path]) => path === '/api/v1/nodes/claim')).toBe(true);
    expect(transport.operations).toContain(Opcode.ClaimReceiptRequest);
  } finally { vi.unstubAllEnvs(); }
});


test('alpha renews a rejected cached receipt once without replacing its connection clock', async () => {
  vi.stubEnv('VITE_MM_SECURITY_CLOCK_POLICY', 'FIELD_ALPHA_PHONE_CLOCK');
  try {
    const transport = new ClaimTransport(); transport.alphaClock = true; transport.dropReceipt = true;
    // Backend reservation survives a failed BLE delivery; the next visit uses
    // this cached receipt, which the Node then rejects (e.g. after expiration).
    await expect(provisionNode(transport, NODE, 'org-a')).rejects.toThrow('disconnected');
    expect(post.mock.calls.filter(([path]) => path === '/api/v1/nodes/claim')).toHaveLength(1);
    transport.rejectReceiptOnce = true;
    await provisionNode(transport, NODE, 'org-a');
    expect(transport.claimed).toBe(true);
    expect(post.mock.calls.filter(([path]) => path === '/api/v1/nodes/claim')).toHaveLength(2);
    expect(post.mock.calls.some(([path]) => path === '/api/v1/nodes/time-bootstrap')).toBe(false);
    // Exactly one sample for each visit, including the renewed-receipt retry.
    expect(transport.operations.filter(op => op === Opcode.AlphaPhoneClockRequest)).toHaveLength(2);
    expect(transport.operations).not.toContain(Opcode.RegistrationTimeChallengeRequest);
    const db = new CollectorDatabase();
    expect((await db.nodeMeta.get(NODE))?.claimState).toBe(1); db.close();
  } finally { vi.unstubAllEnvs(); }
});
