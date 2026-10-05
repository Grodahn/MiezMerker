// @vitest-environment node
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';

const get = vi.hoisted(() => vi.fn());
vi.mock('../api/client', () => ({ api: { GET: get } }));
vi.mock('../platform/auth', () => ({ getAuthState: vi.fn(), subscribeAuth: () => () => {} }));
vi.mock('../platform/node-identity', () => ({
  loadTrustedNodeIdentity: vi.fn(),
  NodeIdentityError: class NodeIdentityError extends Error {
    constructor(readonly kind: string, message: string, readonly status?: number) {
      super(message);
      this.name = 'NodeIdentityError';
    }
  },
}));

import 'fake-indexeddb/auto';
import { getAuthState } from '../platform/auth';
import { loadTrustedNodeIdentity } from '../platform/node-identity';
import { CollectorDatabase } from '../platform/offline-store';
import { CollectorObservationStore } from './observation-store';
import { runEngineWithDiscovery, runFieldSync } from './collector-sync';
import { OfflineIdentity } from '../platform/offline-identity';
import type { NodeTransport } from '../platform/node-transport';
import { decodeFrame, encodeFrame, encodeHelloPublic, encodeOwnerResponse, Opcode } from './ble-codec';

const NODE_ID = '44444444-4444-4444-8444-444444444444';
const INCARNATION = '55555555-5555-4555-9555-555555555555';

const mockedAuth = vi.mocked(getAuthState);
const mockedLoad = vi.mocked(loadTrustedNodeIdentity);

function authAs(org: string | null) {
  mockedAuth.mockReturnValue(org === null
    ? { user: null, csrfToken: null, activeOrganizationId: null }
    : {
        user: { userId: 'u1', email: 'a@b.c', memberships: [
          { organizationId: org, status: 'ACTIVE', role: 'MEMBER' },
        ] },
        csrfToken: null, activeOrganizationId: org,
      });
}

class ProbeOnlyTransport implements NodeTransport {
  constructor(private organizationId = 'org-a', private claimState = 1) {}
  opcodes: Opcode[] = [];
  lastResponse: Uint8Array = new Uint8Array();
  async connect() {}
  async disconnect() {}
  async read() { return this.lastResponse; }
  async write(message: Uint8Array) {
    const frame = decodeFrame(message);
    if (!frame) throw new Error('bad frame');
    this.opcodes.push(frame.opcode);
    if (frame.opcode === Opcode.HelloRequest) {
      this.lastResponse = encodeFrame({ version: 1, opcode: Opcode.HelloPublic, payload: encodeHelloPublic({
        serverVer: 1, caps: 0x1f, nodeId: NODE_ID, incarnation: INCARNATION,
        firmwareVersion: 'test', claimState: this.claimState, clockStatus: 2,
      }) });
    } else if (frame.opcode === Opcode.OwnerRequest) {
      this.lastResponse = encodeFrame({ version: 1, opcode: Opcode.OwnerResponse, payload: encodeOwnerResponse({
        nodeId: NODE_ID, claimState: this.claimState, organizationId: this.organizationId,
        organizationSlug: 'org', organizationName: 'Org', publicContact: '',
      }) });
    }
  }
}

describe('runEngineWithDiscovery node identity resolution', () => {
  let db: CollectorDatabase;
  let store: CollectorObservationStore;
  afterEach(() => { db.close(); vi.restoreAllMocks(); });

  beforeEach(async () => {
    get.mockReset();
    mockedAuth.mockReset();
    mockedLoad.mockReset();
    db = new CollectorDatabase(`discovery-${Math.random().toString(36).slice(2)}`);
    store = new CollectorObservationStore(db);
    await store.open();
    Object.defineProperty(globalThis, 'navigator', { value: { onLine: true }, configurable: true });
  });

  test('offline: falls back to locally stored backend-pinned node key', async () => {
    authAs('org-a');
    mockedLoad.mockRejectedValue(new Error('network'));
    await store.saveNodeMeta({
      nodeId: NODE_ID, incarnation: INCARNATION, claimState: 1, organizationId: 'org-a',
      organizationSlug: 'org', organizationName: 'Org', publicContact: '',
      firmwareVersion: null, lastWatermark: null, lastSyncAt: null, pendingCount: null,
      publicKeyX: 'x'.repeat(43), publicKeyY: 'y'.repeat(43),
    });
    const outcome = await runEngineWithDiscovery(
      new ProbeOnlyTransport(), store, {} as never, 'cred', 'org-a', () => 1790899200,
      () => {}, () => {},
    );
    expect(outcome.message).not.toContain('offline unbekannt');
    expect(outcome.nodeId).toBe(NODE_ID);
  });

  test('offline without stored key fails with clear message', async () => {
    authAs('org-a');
    mockedLoad.mockRejectedValue(new Error('network'));
    const outcome = await runEngineWithDiscovery(
      new ProbeOnlyTransport(), store, {} as never, 'cred', 'org-a', () => 1790899200,
      () => {}, () => {},
    );
    expect(outcome.ok).toBe(false);
    expect(outcome.message).toContain('offline unbekannt');
  });

  test('foreign claimed node (403) shows foreign outcome, not offline error', async () => {
    authAs('org-a');
    const { NodeIdentityError } = await import('../platform/node-identity');
    mockedLoad.mockRejectedValue(new NodeIdentityError('forbidden', 'Node belongs to another organization', 403));
    const outcome = await runEngineWithDiscovery(
      new ProbeOnlyTransport(), store, {} as never, 'cred', 'org-a', () => 1790899200,
      () => {}, () => {},
    );
    expect(outcome.ok).toBe(false);
    expect(outcome.foreign).toBe(true);
    expect(outcome.message).not.toContain('offline unbekannt');
  });

  test('foreign public owner is shown offline without a credential or protected reads', async () => {
    authAs('org-a');
    Object.defineProperty(globalThis, 'navigator', { value: { onLine: false }, configurable: true });
    const credential = vi.spyOn(OfflineIdentity.prototype, 'credential').mockResolvedValue(null);
    const transport = new ProbeOnlyTransport('org-b');
    const outcome = await runFieldSync({ createTransport: () => transport, onUpdate: () => {} });
    expect(outcome.foreign?.organizationName).toBe('Org');
    expect(credential).not.toHaveBeenCalled();
    expect(transport.opcodes).toEqual([Opcode.HelloRequest, Opcode.OwnerRequest]);
  });

  test('ADMIN discovers an unclaimed node before first credential issuance', async () => {
    authAs('org-a');
    mockedAuth.mockReturnValue({ ...getAuthState(), user: { ...getAuthState().user!, memberships: [
      { organizationId: 'org-a', status: 'ACTIVE', role: 'ADMIN' },
    ] } });
    const credential = vi.spyOn(OfflineIdentity.prototype, 'credential').mockResolvedValue(null);
    const transport = new ProbeOnlyTransport('org-a', 0);
    const outcome = await runFieldSync({ createTransport: () => transport, onUpdate: () => {} });
    expect(outcome.unclaimed).toBe(true);
    expect(outcome.owner?.nodeId).toBe(NODE_ID);
    expect(credential).not.toHaveBeenCalled();
    expect(transport.opcodes).toEqual([Opcode.HelloRequest, Opcode.OwnerRequest]);
  });
});
