// @vitest-environment node
import { describe, expect, test, vi } from 'vitest';
import {
  batchSummaryMessage, classifyBatchView, dedupeBatchInputs,
  retryableBatchInputs, runBatchSync, summarizeBatch,
  type BatchNodeInput,
} from './batch-sync';
import { initialCollectorView } from './collector-sync';

function input(id: string, label = id): BatchNodeInput {
  return { device: { id }, browserId: id, browserLabel: label };
}

function successView(nodeId: string, records = 3) {
  return {
    ...initialCollectorView,
    nodeState: 'complete' as const,
    fertig: true,
    owner: {
      nodeId, claimState: 1 as const, organizationId: 'org-a',
      organizationSlug: 'org-a', organizationName: 'Org A', publicContact: '',
    },
    recordsReceived: records,
    watermark: '3',
    nodeMessage: 'Fertig.',
  };
}

describe('batch orchestration (issue #76)', () => {
  test('two authorized reachable nodes are processed sequentially without a chooser', async () => {
    const order: string[] = [];
    const disconnects: string[] = [];
    const inputs = [input('a', 'Bowl A'), input('b', 'Bowl B')];
    const results = await runBatchSync(inputs, {
      createTransport: device => {
        const id = (device as { id: string }).id;
        return {
          connect: async () => {},
          disconnect: async () => { disconnects.push(id); },
          read: async () => new Uint8Array(),
          write: async () => {},
        };
      },
      runSingleNode: async (transport, _signal, onUpdate) => {
        void transport;
        // Track concurrency: the second node must not start before the first finished.
        order.push('start');
        onUpdate({ ...initialCollectorView, nodeState: 'connecting', nodeMessage: 'Verbinde …' });
        await new Promise(r => setTimeout(r, 5));
        order.push('done');
        return successView(order.length === 2 ? 'node-a' : 'node-b');
      },
    });
    expect(results).toHaveLength(2);
    expect(results.map(r => r.kind)).toEqual(['success', 'success']);
    expect(order).toEqual(['start', 'done', 'start', 'done']);
    expect(disconnects).toHaveLength(2);
  });

  test('a failing node does not stop the remaining nodes; outcomes stay distinct', async () => {
    const inputs = [input('ok-1'), input('bad'), input('ok-2')];
    const results = await runBatchSync(inputs, {
      createTransport: () => ({ connect: async () => {}, disconnect: async () => {}, read: async () => new Uint8Array(), write: async () => {} }),
      runSingleNode: async () => {
        throw new Error('unreachable-stub');
      },
    });
    // All three attempted (no early stop on throw when every node throws).
    expect(results).toHaveLength(3);
    expect(results.every(r => r.kind === 'unreachable' || r.kind === 'failed')).toBe(true);
  });

  test('mixed view outcomes classify to success/unclaimed/foreign/unauthorized/unreachable', async () => {
    const views = [
      successView('node-ok'),
      { ...initialCollectorView, nodeState: 'failed' as const, unclaimed: true, nodeMessage: 'UNCLAIMED', owner: { nodeId: 'node-u', claimState: 0 as const, organizationId: 'org-a', organizationSlug: 'o', organizationName: 'O', publicContact: '' } },
      { ...initialCollectorView, nodeState: 'failed' as const, foreign: { organizationName: 'Fremd', organizationSlug: 'f', publicContact: '' }, nodeMessage: 'gehört Fremd', owner: { nodeId: 'node-f', claimState: 1 as const, organizationId: 'org-b', organizationSlug: 'f', organizationName: 'Fremd', publicContact: '' } },
      { ...initialCollectorView, nodeState: 'failed' as const, nodeMessage: 'Kein gültiges Offline-Credential vorhanden.' },
      { ...initialCollectorView, nodeState: 'failed' as const, nodeMessage: 'BLE-Verbindung fehlgeschlagen. Näher herangehen.' },
    ];
    let call = 0;
    const results = await runBatchSync([input('a'), input('b'), input('c'), input('d'), input('e')], {
      createTransport: () => ({ connect: async () => {}, disconnect: async () => {}, read: async () => new Uint8Array(), write: async () => {} }),
      runSingleNode: async () => views[call++],
    });
    expect(results.map(r => r.kind)).toEqual(['success', 'unclaimed', 'foreign', 'unauthorized', 'unreachable']);
    expect(results[0].nodeId).toBe('node-ok');
    expect(results[1].unclaimed).toBe(true);
    expect(results[2].foreignOrganizationName).toBe('Fremd');
  });

  test('per-node timeout aborts a hung node and continues with the next', async () => {
    const inputs = [input('hung'), input('fast')];
    const results = await runBatchSync(inputs, {
      perNodeTimeoutMs: 30,
      createTransport: () => ({ connect: async () => {}, disconnect: async () => {}, read: async () => new Uint8Array(), write: async () => {} }),
      runSingleNode: async (_transport, signal) => {
        if (signal) {
          await new Promise<void>((_resolve, reject) => {
            const timer = setTimeout(() => {}, 5000);
            signal.addEventListener('abort', () => { clearTimeout(timer); reject(new DOMException('aborted', 'AbortError')); }, { once: true });
          });
        }
        return successView('node-fast');
      },
    });
    expect(results[0].kind).toBe('unreachable');
    expect(results[0].message).toMatch(/Zeitüberschreitung/);
  });

  test('cancellation marks the current node cancelled and the rest skipped', async () => {
    const controller = new AbortController();
    const inputs = [input('a'), input('b'), input('c')];
    const done: string[] = [];
    const promise = runBatchSync(inputs, {
      signal: controller.signal,
      createTransport: () => ({ connect: async () => {}, disconnect: async () => {}, read: async () => new Uint8Array(), write: async () => {} }),
      runSingleNode: async (_t, signal) => {
        controller.abort();
        if (signal?.aborted) throw new DOMException('aborted', 'AbortError');
        return successView('node-x');
      },
      onNodeDone: (_i, _t, r) => { done.push(r.browserId); },
    });
    const results = await promise;
    expect(results[0].kind).toBe('cancelled');
    expect(results.slice(1).map(r => r.kind)).toEqual(['skipped', 'skipped']);
    expect(done).toEqual(['a', 'b', 'c']);
  });

  test('duplicate browser permissions are processed once (dedupe)', async () => {
    let runs = 0;
    const results = await runBatchSync([input('dup'), input('dup'), input('other')], {
      createTransport: () => ({ connect: async () => {}, disconnect: async () => {}, read: async () => new Uint8Array(), write: async () => {} }),
      runSingleNode: async () => { runs++; return successView('node-x'); },
    });
    expect(results).toHaveLength(2);
    expect(runs).toBe(2);
    expect(dedupeBatchInputs([input('a'), input('a')])).toHaveLength(1);
  });

  test('retry selects only failed nodes, never repeats successes', () => {
    const inputs = [input('ok'), input('bad'), input('bad2')];
    const results = [
      { browserId: 'ok', browserLabel: 'ok', kind: 'success' as const, nodeId: 'n', ownerNodeId: 'n', recordsReceived: 1, watermark: '1', message: '', foreignOrganizationName: null, unclaimed: false, durationMs: 1 },
      { browserId: 'bad', browserLabel: 'bad', kind: 'unreachable' as const, nodeId: null, ownerNodeId: null, recordsReceived: 0, watermark: null, message: '', foreignOrganizationName: null, unclaimed: false, durationMs: 1 },
      { browserId: 'bad2', browserLabel: 'bad2', kind: 'failed' as const, nodeId: null, ownerNodeId: null, recordsReceived: 0, watermark: null, message: '', foreignOrganizationName: null, unclaimed: false, durationMs: 1 },
    ];
    expect(retryableBatchInputs(inputs, results).map(i => i.browserId)).toEqual(['bad', 'bad2']);
  });

  test('summary message counts only reachable synchronized bowls honestly', () => {
    const ok = (id: string) => ({ browserId: id, browserLabel: id, kind: 'success' as const, nodeId: id, ownerNodeId: id, recordsReceived: 1, watermark: '1', message: '', foreignOrganizationName: null, unclaimed: false, durationMs: 1 });
    const bad = (id: string) => ({ ...ok(id), kind: 'unreachable' as const, nodeId: null });
    expect(batchSummaryMessage([])).toMatch(/Keine freigegebenen/);
    expect(batchSummaryMessage([ok('a'), ok('b')])).toBe('2 von 2 freigegebenen Näpfen ausgelesen.');
    expect(batchSummaryMessage([ok('a'), bad('b'), bad('c')])).toBe('1 von 3 freigegebenen Näpfen ausgelesen.');
    expect(summarizeBatch([ok('a'), bad('b')])).toMatchObject({ total: 2, succeeded: 1, failed: 1 });
  });

  test('classifyBatchView never invents a node id for unreachable devices', () => {
    const view = { ...initialCollectorView, nodeState: 'failed' as const, nodeMessage: 'BLE-Verbindung fehlgeschlagen.' };
    expect(classifyBatchView(view, false)).toMatchObject({ kind: 'unreachable', nodeId: null });
    expect(classifyBatchView(view, true).kind).toBe('cancelled');
  });

  test('transport creation failure surfaces as unreachable, not success', async () => {
    const results = await runBatchSync([input('gone')], {
      createTransport: () => { throw new Error('GATT connect failed'); },
    });
    expect(results[0].kind).toBe('unreachable');
    expect(results[0].nodeId).toBeNull();
  });

  test('GATT disconnect cleanup runs even when the node operation throws', async () => {
    const disconnect = vi.fn(async () => {});
    await runBatchSync([input('x')], {
      createTransport: () => ({ connect: async () => {}, disconnect, read: async () => new Uint8Array(), write: async () => {} }),
      runSingleNode: async () => { throw new Error('mid-batch abort'); },
    });
    expect(disconnect).toHaveBeenCalled();
  });
});
