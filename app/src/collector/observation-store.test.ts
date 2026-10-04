import 'fake-indexeddb/auto';
import { afterEach, describe, expect, test } from 'vitest';
import { CollectorDatabase } from '../platform/offline-store';
import { CollectorObservationStore, contiguousWatermark } from './observation-store';
import type { BleRecord } from './ble-codec';

const NODE = '44444444-4444-4444-8444-444444444444';
const INC = '55555555-5555-4555-9555-555555555555';

function rec(seq: number, chip = 'chip'): BleRecord {
  return {
    nodeId: NODE, incarnation: INC, sequence: BigInt(seq), chipId: `${chip}-${seq}`,
    clockStatus: 2, epochMs: 1790899200000n + BigInt(seq), monotonicMs: BigInt(seq * 10), bootCounter: 1,
  };
}

let dbs: CollectorDatabase[] = [];
function freshDb(): { db: CollectorDatabase; store: CollectorObservationStore } {
  const db = new CollectorDatabase(`collector-${Math.random().toString(36).slice(2)}`);
  dbs.push(db);
  return { db, store: new CollectorObservationStore(db) };
}

afterEach(async () => {
  for (const db of dbs) {
    try {
      db.close();
      await db.delete();
    } catch {
      // Ignore cleanup failures.
    }
  }
  dbs = [];
});

describe('CollectorObservationStore durable local storage', () => {
  test('idempotent insert: repeated delivery creates no duplicates', async () => {
    const { db, store } = freshDb();
    await store.open();
    await store.putObservations([rec(1), rec(2)], 'org-a');
    await store.putObservations([rec(1), rec(2)], 'org-a');
    expect(await store.sequences(NODE, INC)).toEqual([1n, 2n]);
    expect(await store.count(NODE, INC)).toBe(2);
    db.close();
  });

  test('conflicting payload for same identity is rejected without overwrite', async () => {
    const { store } = freshDb();
    await store.open();
    await store.putObservations([rec(1, 'chip')]);
    await expect(store.putObservations([{ ...rec(1, 'chip'), chipId: 'other' }])).rejects.toThrow();
    expect(await store.sequences(NODE, INC)).toEqual([1n]);
  });

  test('ACK high-watermark from durable state never skips gaps', async () => {
    const { store } = freshDb();
    await store.open();
    await store.putObservations([rec(100), rec(101), rec(103)], 'org-a');
    const stored = await store.sequences(NODE, INC);
    expect(contiguousWatermark(99n, stored)).toBe(101n);
    await store.putObservations([rec(102)], 'org-a');
    expect(contiguousWatermark(99n, await store.sequences(NODE, INC))).toBe(103n);
  });

  test('persistent DB state survives close/reopen (restart between receive and ACK)', async () => {
    const name = `restart-${Math.random().toString(36).slice(2)}`;
    const first = new CollectorDatabase(name);
    dbs.push(first);
    const s1 = new CollectorObservationStore(first);
    await s1.open();
    await s1.putObservations([rec(1), rec(2)], 'org-a');
    await s1.saveSession({ nodeId: NODE, incarnation: INC, watermark: '0', phase: 'persisting', updatedAt: 1 });
    first.close();
    const second = new CollectorDatabase(name);
    dbs.push(second);
    const s2 = new CollectorObservationStore(second);
    await s2.open();
    expect(await s2.sequences(NODE, INC)).toEqual([1n, 2n]);
    expect((await s2.session(NODE))?.watermark).toBe('0');
  });

  test('upload state machine: pending → uploaded, failure stays retryable', async () => {
    const { store } = freshDb();
    await store.open();
    await store.putObservations([rec(1), rec(2)], 'org-a');
    expect((await store.pendingUploads('org-a'))).toHaveLength(2);
    expect(await store.pendingUploads('org-b')).toHaveLength(0);
    await store.markUploadResult({ nodeId: NODE, incarnation: INC, sequence: '1' }, true, null);
    expect((await store.uploadStats(NODE)).uploaded).toBe(1);
    await store.markUploadResult({ nodeId: NODE, incarnation: INC, sequence: '2' }, false, 'Backend offline');
    expect((await store.uploadStats(NODE)).failed).toBe(1);
    expect(await store.requeueFailed(NODE)).toBe(1);
    expect((await store.pendingUploads('org-a'))).toHaveLength(1);
  });

  test('node metadata survives for retry display', async () => {
    const { store } = freshDb();
    await store.open();
    await store.saveNodeMeta({
      nodeId: NODE, incarnation: INC, claimState: 1, organizationId: 'org-a',
      organizationSlug: 'org', organizationName: 'Org', publicContact: '',
      firmwareVersion: '1.0.0', lastWatermark: '2', lastSyncAt: 123, pendingCount: 0,
      publicKeyX: 'x'.repeat(43), publicKeyY: 'y'.repeat(43),
    });
    expect((await store.nodeMeta(NODE))?.lastWatermark).toBe('2');
  });
});
