// @vitest-environment node
import { beforeEach, expect, test, vi } from 'vitest';
const get = vi.hoisted(() => vi.fn());
vi.mock('../api/client', () => ({ api: { GET: get } }));
import { loadTrustedNodeIdentity } from './node-identity';
const nodeId = 'e7062544-6382-41c0-9ffe-1d3c5b7a99b8';
beforeEach(() => get.mockReset());
test('loads the node key from the tenant-authorized backend record', async () => {
  get.mockResolvedValue({ data: { nodeId, state: 'CLAIMED', publicKeyX: 'x', publicKeyY: 'y' } });
  expect(await loadTrustedNodeIdentity(nodeId)).toEqual({ nodeId, publicKeyX: 'x', publicKeyY: 'y' });
  expect(get).toHaveBeenCalledWith('/api/v1/nodes/{nodeId}', { params: { path: { nodeId } } });
});
test('rejects denied, incomplete, unclaimed and mismatched backend records', async () => {
  for (const response of [
    { error: { status: 403 } },
    { data: { nodeId, state: 'CLAIMED' } },
    { data: { nodeId, state: 'UNCLAIMED', publicKeyX: 'x', publicKeyY: 'y' } },
    { data: { nodeId: 'another-node', state: 'CLAIMED', publicKeyX: 'x', publicKeyY: 'y' } },
  ]) {
    get.mockResolvedValue(response);
    await expect(loadTrustedNodeIdentity(nodeId)).rejects.toThrow('Trusted node identity unavailable');
  }
});
