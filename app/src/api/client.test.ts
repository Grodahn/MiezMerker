import { expect, test, vi } from 'vitest';
import createClient from 'openapi-fetch';
import type { paths } from './generated';

test('generated HTTP contract supplies the typed runtime client', async () => {
  const fetch = vi.fn(async (_request: Request) => new Response(JSON.stringify({ apiVersion: 'v1', version: 'test' }),
    { status: 200, headers: { 'Content-Type': 'application/json' } }));
  const client = createClient<paths>({ baseUrl: 'http://localhost', credentials: 'same-origin', fetch });
  const { data, error } = await client.GET('/api/v1/version');
  expect(error).toBeUndefined();
  expect(data?.apiVersion).toBe('v1');
  expect(fetch.mock.calls[0][0].url).toBe('http://localhost/api/v1/version');
  expect(fetch.mock.calls[0][0].credentials).toBe('same-origin');
});
