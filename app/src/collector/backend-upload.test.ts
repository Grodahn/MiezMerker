// @vitest-environment node
import 'fake-indexeddb/auto';
import { beforeEach, describe, expect, test, vi } from 'vitest';

const post = vi.hoisted(() => vi.fn());
vi.mock('../api/client', () => ({ api: { POST: post } }));
vi.mock('../platform/auth', () => ({ fetchCsrfToken: async () => 'fresh-csrf' }));

import { CollectorDatabase } from '../platform/offline-store';
import { CollectorObservationStore } from './observation-store';
import { BackendUploader } from './backend-upload';
import type { BleRecord } from './ble-codec';

const NODE = '44444444-4444-4444-8444-444444444444';
const INC = '55555555-5555-4555-9555-555555555555';

function rec(seq: number): BleRecord {
  return {
    nodeId: NODE, incarnation: INC, sequence: BigInt(seq), chipId: `chip-${seq}`,
    clockStatus: 2, epochMs: 1790899200000n + BigInt(seq), monotonicMs: BigInt(seq), bootCounter: 1,
  };
}

describe('BackendUploader (PWA→Backend independent state machine)', () => {
  let db: CollectorDatabase;
  let store: CollectorObservationStore;

  beforeEach(async () => {
    post.mockReset();
    db = new CollectorDatabase(`upload-${Math.random().toString(36).slice(2)}`);
    store = new CollectorObservationStore(db);
    await store.open();
    Object.defineProperty(globalThis, 'navigator', { value: { onLine: true }, configurable: true });
  });

  test('later upload: pending records batch to #9 ingest with decimal strings', async () => {
    await store.putObservations([rec(1), rec(2)], 'org-a');
    post.mockResolvedValue({ data: { results: [{ status: 'CREATED' }, { status: 'DUPLICATE_IDENTICAL' }] } });
    const result = await new BackendUploader(store, { batchSize: 200 }).upload('org-a');
    expect(result.state).toBe('complete');
    expect(result.uploaded).toBe(1);
    expect(result.duplicates).toBe(1);
    const body = post.mock.calls[0][1].body;
    expect(post.mock.calls[0][1].headers).toEqual({ 'X-XSRF-TOKEN': 'fresh-csrf' });
    expect(body.organizationId).toBe('org-a');
    expect(body.observations[0].sequence).toBe('1');
    expect(body.observations[0].clockStatus).toBe('SYNCED');
    expect((await store.uploadStats(NODE)).uploaded).toBe(2);
  });

  test('backend failure leaves records in outbox for retry (Node sync stays Fertig)', async () => {
    await store.putObservations([rec(1)], 'org-a');
    post.mockRejectedValue(new Error('Backend offline'));
    const uploader = new BackendUploader(store, { maxRetries: 0, retryDelayMs: 0 });
    const result = await uploader.upload('org-a');
    expect(result.state).toBe('failed');
    expect(result.message).toContain('Vor-Ort-Sync bleibt');
    expect((await store.pendingUploads('org-a'))).toHaveLength(1);
    // Retry succeeds later.
    post.mockResolvedValue({ data: { results: [{ status: 'CREATED' }] } });
    expect((await uploader.upload('org-a')).state).toBe('complete');
  });

  test('conflicting backend items do not block siblings', async () => {
    await store.putObservations([rec(1), rec(2)], 'org-a');
    post.mockResolvedValue({ data: { results: [
      { status: 'CONFLICT', message: 'conflict' }, { status: 'CREATED' },
    ] } });
    const result = await new BackendUploader(store).upload('org-a');
    expect(result.state).toBe('failed');
    expect(result.conflicts).toBe(1);
    expect((await store.uploadStats(NODE)).uploaded).toBe(1);
    expect((await store.uploadStats(NODE)).failed).toBe(1);
  });

  test('offline mode leaves outbox untouched', async () => {
    await store.putObservations([rec(1)], 'org-a');
    Object.defineProperty(globalThis, 'navigator', { value: { onLine: false }, configurable: true });
    const result = await new BackendUploader(store).upload('org-a');
    expect(result.state).toBe('waiting-for-network');
    expect(post).not.toHaveBeenCalled();
    expect((await store.pendingUploads('org-a'))).toHaveLength(1);
  });

  test('failed records are retried and never silently reported complete', async () => {
    await store.putObservations([rec(1)], 'org-a');
    post.mockResolvedValue({ data: { results: [{ status: 'CONFLICT' }] } });
    const uploader = new BackendUploader(store);
    expect((await uploader.upload('org-a')).state).toBe('failed');
    expect((await uploader.upload('org-a')).state).toBe('failed');
    post.mockResolvedValue({ data: { results: [{ status: 'CREATED' }] } });
    expect((await uploader.upload('org-a')).state).toBe('complete');
    expect((await store.uploadStats(NODE)).uploaded).toBe(1);
  });

  test('unassigned records cannot enter another organization queue', async () => {
    await store.putObservations([rec(1)]);
    expect(await store.pendingUploads('org-a')).toEqual([]);
  });

  test('a response for a different event never marks the local record uploaded', async () => {
    await store.putObservations([rec(1)], 'org-a');
    post.mockResolvedValue({ data: { results: [{ nodeId: NODE, sequence: '2', status: 'CREATED' }] } });
    const result = await new BackendUploader(store).upload('org-a');
    expect(result.state).toBe('failed'); expect((await store.uploadStats(NODE)).uploaded).toBe(0);
  });

  test('outboxes exceeding 5000 records are fully drained', async () => {
    const rows = Array.from({ length: 5001 }, (_, i) => ({ ...rec(i + 1),
      sequence: String(i + 1), epochMs: String(1790899200000 + i + 1), monotonicMs: String(i + 1),
      receivedAt: Date.now(), organizationId: 'org-a', uploadState: 'pending' as const,
      uploadAttempts: 0, lastUploadError: null, uploadedAt: null }));
    const repository = {
      pendingUploads: async (_org: string, limit = rows.length) => rows.slice(0, limit),
      markUploadResult: vi.fn(async () => {}),
    } as unknown as CollectorObservationStore;
    post.mockImplementation(async (_p: unknown, opts: { body: { observations: unknown[] } }) => ({
      data: { results: opts.body.observations.map(() => ({ status: 'CREATED' })) },
    }));
    expect((await new BackendUploader(repository).upload('org-a')).uploaded).toBe(5001);
    expect(repository.markUploadResult).toHaveBeenCalledTimes(5001);
  }, 30000);

  test('batching splits large outboxes', async () => {
    const records = Array.from({ length: 5 }, (_, i) => rec(i + 1));
    await store.putObservations(records, 'org-a');
    post.mockImplementation(async (_p: unknown, opts: { body: { observations: unknown[] } }) => ({
      data: { results: opts.body.observations.map(() => ({ status: 'CREATED' })) },
    }));
    const result = await new BackendUploader(store, { batchSize: 2 }).upload('org-a');
    expect(post).toHaveBeenCalledTimes(3);
    expect(result.state).toBe('complete');
  });

  test('4xx rejection fails fast without retry (membership/device disabled)', async () => {
    await store.putObservations([rec(1)], 'org-a');
    post.mockResolvedValue({ error: { status: 403 }, response: { status: 403 } });
    const uploader = new BackendUploader(store, { maxRetries: 3, retryDelayMs: 0 });
    const result = await uploader.upload('org-a');
    expect(post).toHaveBeenCalledTimes(1);
    expect(result.state).toBe('failed');
    expect(result.message).toContain('403');
    expect(result.message).toContain('Vor-Ort-Sync bleibt');
  });
});
