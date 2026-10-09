// @vitest-environment node
import { afterEach, describe, expect, test, vi } from 'vitest';
import { activeDeploymentFor, resolveBatchContexts } from './batch-context';

const NODE_A = '44444444-4444-4444-8444-444444444444';
const NODE_B = '55555555-5555-4555-9555-555555555555';
const SITE_A = 'site-a';
const SITE_B = 'site-b';

function loaderFor(opts: {
  nodes?: unknown[]; deployments?: unknown[]; sites?: unknown[]; activity?: Record<string, unknown[]>;
  fail?: string | null;
}) {
  return {
    get: vi.fn(async (path: string, options?: { params?: { path?: { siteId?: string } } }) => {
      if (opts.fail && path.includes(opts.fail)) return { error: {}, response: { status: 500 } };
      if (path.includes('/cat-activity')) {
        const siteId = options?.params?.path?.siteId ?? SITE_A;
        return { data: opts.activity?.[siteId] ?? [] };
      }
      if (path.endsWith('/nodes')) return { data: opts.nodes ?? [] };
      if (path.endsWith('/deployments')) return { data: opts.deployments ?? [] };
      if (path.endsWith('/feeding-sites')) return { data: opts.sites ?? [] };
      return { data: [] };
    }),
  };
}

describe('batch feeding-site context (issue #76)', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    Object.defineProperty(globalThis, 'navigator', { value: { onLine: true }, configurable: true });
  });

  test('two active nodes at one site share the site, nodes at different sites stay distinct', async () => {
    Object.defineProperty(globalThis, 'navigator', { value: { onLine: true }, configurable: true });
    const loader = loaderFor({
      nodes: [
        { nodeId: NODE_A, displayName: 'Der Grüne' },
        { nodeId: NODE_B, displayName: 'Silberner' },
      ],
      deployments: [
        { id: 'd1', nodeId: NODE_A, feedingSiteId: SITE_A, validFrom: '2025-01-01T00:00:00Z', validUntil: null },
        { id: 'd2', nodeId: NODE_B, feedingSiteId: SITE_B, validFrom: '2025-01-01T00:00:00Z', validUntil: null },
      ],
      sites: [
        { id: SITE_A, name: 'Garten' },
        { id: SITE_B, name: 'Scheune' },
      ],
      activity: {
        [SITE_A]: [{ chipId: 'CHIP-1', catId: null, catName: null, lastReliableSightingAt: null, lastReceivedAt: '2026-10-01T00:00:00Z', visitCount: 0 }],
        [SITE_B]: [],
      },
    });
    const contexts = await resolveBatchContexts([NODE_A, NODE_B], 'org-a', {}, loader as never);
    expect(contexts.get(NODE_A)?.siteName).toBe('Garten');
    expect(contexts.get(NODE_B)?.siteName).toBe('Scheune');
    expect(contexts.get(NODE_A)?.displayName).toBe('Der Grüne');
    expect(contexts.get(NODE_A)?.activity).toHaveLength(1);
  });

  test('multiple active nodes at the same feeding site are normal, not a schema special case', async () => {
    Object.defineProperty(globalThis, 'navigator', { value: { onLine: true }, configurable: true });
    const loader = loaderFor({
      nodes: [{ nodeId: NODE_A, displayName: 'A' }, { nodeId: NODE_B, displayName: 'B' }],
      deployments: [
        { id: 'd1', nodeId: NODE_A, feedingSiteId: SITE_A, validFrom: '2025-01-01T00:00:00Z', validUntil: null },
        { id: 'd2', nodeId: NODE_B, feedingSiteId: SITE_A, validFrom: '2025-02-01T00:00:00Z', validUntil: null },
      ],
      sites: [{ id: SITE_A, name: 'Garten' }],
      activity: { [SITE_A]: [] },
    });
    const contexts = await resolveBatchContexts([NODE_A, NODE_B], 'org-a', {}, loader as never);
    expect(contexts.get(NODE_A)?.siteId).toBe(SITE_A);
    expect(contexts.get(NODE_B)?.siteId).toBe(SITE_A);
  });

  test('node without an active deployment is honestly unassigned', async () => {
    Object.defineProperty(globalThis, 'navigator', { value: { onLine: true }, configurable: true });
    const loader = loaderFor({
      nodes: [{ nodeId: NODE_A, displayName: null }],
      deployments: [],
      sites: [{ id: SITE_A, name: 'Garten' }],
    });
    const contexts = await resolveBatchContexts([NODE_A], 'org-a', {}, loader as never);
    expect(contexts.get(NODE_A)?.siteId).toBeNull();
    expect(contexts.get(NODE_A)?.contextNote).toMatch(/keiner Futterstelle/);
    expect(contexts.get(NODE_A)?.displayName).toBeNull();
  });

  test('closed deployments are not shown as current; the latest valid assignment wins', async () => {
    const deployments = [
      { id: 'old', nodeId: NODE_A, feedingSiteId: SITE_A, validFrom: '2024-01-01T00:00:00Z', validUntil: '2024-06-01T00:00:00Z' },
      { id: 'open', nodeId: NODE_A, feedingSiteId: SITE_B, validFrom: '2025-01-01T00:00:00Z', validUntil: null },
    ];
    expect(activeDeploymentFor(deployments as never, NODE_A, Date.parse('2026-01-01T00:00:00Z'))?.id).toBe('open');
    expect(activeDeploymentFor(deployments as never, NODE_A, Date.parse('2024-03-01T00:00:00Z'))?.id).toBe('old');
    expect(activeDeploymentFor([], NODE_A, Date.now())).toBeNull();
    // Overlapping valid rows resolve deterministically by latest validFrom.
    const overlapping = [
      { id: 'first', nodeId: NODE_A, feedingSiteId: SITE_A, validFrom: '2025-01-01T00:00:00Z', validUntil: null },
      { id: 'second', nodeId: NODE_A, feedingSiteId: SITE_B, validFrom: '2025-06-01T00:00:00Z', validUntil: null },
    ];
    expect(activeDeploymentFor(overlapping as never, NODE_A, Date.parse('2026-01-01T00:00:00Z'))?.id).toBe('second');
    expect(activeDeploymentFor([...overlapping].reverse() as never, NODE_A, Date.parse('2026-01-01T00:00:00Z'))?.id).toBe('second');
  });

  test('offline keeps the local sync valid with an honest unavailable note', async () => {
    Object.defineProperty(globalThis, 'navigator', { value: { onLine: false }, configurable: true });
    const loader = loaderFor({});
    const contexts = await resolveBatchContexts([NODE_A], 'org-a', {}, loader as never);
    expect(contexts.get(NODE_A)?.contextAvailable).toBe(false);
    expect(contexts.get(NODE_A)?.activity).toBeNull();
    expect(loader.get).not.toHaveBeenCalled();
  });

  test('backend failure never invents assignments or cat activity', async () => {
    Object.defineProperty(globalThis, 'navigator', { value: { onLine: true }, configurable: true });
    const loader = loaderFor({ fail: '/nodes' });
    const contexts = await resolveBatchContexts([NODE_A], 'org-a', {}, loader as never);
    expect(contexts.get(NODE_A)?.contextAvailable).toBe(false);
    expect(contexts.get(NODE_A)?.siteId).toBeNull();
    expect(contexts.get(NODE_A)?.activity).toBeNull();
  });

  test('unknown node id is reported as foreign/unavailable, not guessed', async () => {
    Object.defineProperty(globalThis, 'navigator', { value: { onLine: true }, configurable: true });
    const loader = loaderFor({ nodes: [], deployments: [], sites: [] });
    const contexts = await resolveBatchContexts([NODE_A], 'org-a', {}, loader as never);
    expect(contexts.get(NODE_A)?.contextAvailable).toBe(false);
    expect(contexts.get(NODE_A)?.contextNote).toMatch(/nicht verfügbar/);
  });

  test('activity preview is bounded to the requested limit', async () => {
    Object.defineProperty(globalThis, 'navigator', { value: { onLine: true }, configurable: true });
    const rows = Array.from({ length: 10 }, (_, i) => ({ chipId: `CHIP-${i}`, lastReceivedAt: null, visitCount: 0 }));
    const loader = loaderFor({
      nodes: [{ nodeId: NODE_A, displayName: 'A' }],
      deployments: [{ id: 'd', nodeId: NODE_A, feedingSiteId: SITE_A, validFrom: '2025-01-01T00:00:00Z', validUntil: null }],
      sites: [{ id: SITE_A, name: 'Garten' }],
      activity: { [SITE_A]: rows },
    });
    const contexts = await resolveBatchContexts([NODE_A], 'org-a', { activityLimit: 3 }, loader as never);
    expect(contexts.get(NODE_A)?.activity).toHaveLength(3);
  });
});
