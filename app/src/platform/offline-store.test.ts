import 'fake-indexeddb/auto';
import { expect, test } from 'vitest';
import { CollectorDatabase, DexieOutbox } from './offline-store';

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
