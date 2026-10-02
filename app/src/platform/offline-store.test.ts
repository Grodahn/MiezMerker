import 'fake-indexeddb/auto';
import { expect, test } from 'vitest';
import { CollectorDatabase, DexieOutbox, type PendingUpload } from './offline-store';

test('outbox survives reopening and partitions local organization queues', async () => {
  const name = 'outbox-test';
  const first = new CollectorDatabase(name);
  await new DexieOutbox(first).put({ id: 'batch-1', organizationId: 'org-a', appDeviceId: 'device-1',
    nodeId: 'node-1', protocolVersion: 1, payload: new Uint8Array([1, 2, 3]), createdAt: 123 });
  first.close();
  const reopened = new CollectorDatabase(name);
  const outbox = new DexieOutbox(reopened);
  expect(await outbox.pending('org-b')).toEqual([]);
  expect(Array.from((await outbox.pending('org-a'))[0].payload)).toEqual([1, 2, 3]);
  await reopened.delete();
});

const upload: PendingUpload = { id: 'batch-1', organizationId: 'org-a', appDeviceId: 'device-1',
  nodeId: 'node-1', protocolVersion: 1, payload: new Uint8Array([1, 2, 3]), createdAt: 123 };

test('identical retries are idempotent but conflicting IDs cannot replace pending data', async () => {
  const database = new CollectorDatabase('outbox-conflict-test');
  try {
    const outbox = new DexieOutbox(database);
    await outbox.put(upload);
    await outbox.put({ ...upload, payload: new Uint8Array(upload.payload) });
    await expect(outbox.put({ ...upload, payload: new Uint8Array([9]) })).rejects.toThrow('Conflicting upload');
    await expect(outbox.put({ ...upload, organizationId: 'org-b' })).rejects.toThrow('Conflicting upload');
    const pending = await outbox.pending('org-a');
    expect(pending).toHaveLength(1);
    expect(Array.from(pending[0].payload)).toEqual([1, 2, 3]);
    expect(await outbox.pending('org-b')).toEqual([]);
  } finally { await database.delete(); }
});

test('concurrent conflicting writes retain exactly one original envelope', async () => {
  const database = new CollectorDatabase('outbox-concurrent-test');
  try {
    const outbox = new DexieOutbox(database);
    const results = await Promise.allSettled([
      outbox.put(upload), outbox.put({ ...upload, payload: new Uint8Array([9]) }),
    ]);
    expect(results.filter(result => result.status === 'fulfilled')).toHaveLength(1);
    expect(results.filter(result => result.status === 'rejected')).toHaveLength(1);
    expect(await outbox.pending('org-a')).toHaveLength(1);
  } finally { await database.delete(); }
});
