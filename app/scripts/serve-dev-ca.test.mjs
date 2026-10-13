import assert from 'node:assert/strict';
import { test } from 'node:test';
import { createPublicCaServer, DOWNLOAD_PATH, isAllowedLocalAddress, main } from './serve-dev-ca.mjs';

test('requires an explicit private/loopback IPv4 interface, never wildcard/public', () => {
  for (const host of ['192.168.68.155', '10.0.0.8', '172.16.0.1', '172.31.255.254', '127.0.0.1']) {
    assert.equal(isAllowedLocalAddress(host), true, host);
  }
  for (const host of [undefined, '', '0.0.0.0', '192.0.2.4', '8.8.8.8', '172.15.0.1', '172.32.0.1', 'localhost', '::', '::1', '192.168.68.155.evil.com']) {
    assert.equal(isAllowedLocalAddress(host), false, String(host));
  }
});

test('refuses startup without explicit local bind before calling mkcert', async () => {
  await assert.rejects(main([]), /Supply --host/);
  await assert.rejects(main(['--host', '0.0.0.0']), /Supply --host/);
  await assert.rejects(main(['--host', '8.8.8.8']), /Supply --host/);
  await assert.rejects(main(['--host', '127.0.0.1', '--port', '0']), /Invalid --port/);
  await assert.rejects(main(['--host', '127.0.0.1', '--port', '65536']), /Invalid --port/);
  await assert.rejects(main(['--host', '127.0.0.1', '--port', '5174x']), /Invalid --port/);
});

test('only fixed GET path transfers exact public DER; no private-file routes', async () => {
  const publicDer = Buffer.from('public-ca-test-fixture-not-a-real-key');
  const server = createPublicCaServer(publicDer);
  await new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(0, '127.0.0.1', resolve);
  });
  try {
    const base = `http://127.0.0.1:${server.address().port}`;
    const good = await fetch(base + DOWNLOAD_PATH);
    assert.equal(good.status, 200);
    assert.deepEqual(Buffer.from(await good.arrayBuffer()), publicDer);
    assert.equal(good.headers.get('cache-control'), 'no-store');
    assert.equal(good.headers.get('x-content-type-options'), 'nosniff');
    assert.equal(good.headers.get('content-type'), 'application/x-x509-ca-cert');
    assert.match(good.headers.get('content-disposition'), /attachment;/);
    assert.equal(good.headers.get('access-control-allow-origin'), null);

    const forbidden = [
      '/',
      '/rootCA.pem', '/rootCA-key.pem', '/dev-key.pem', '/.cert/dev-key.pem',
      '/__dev/cert/rootCA-key.pem',
      '/__dev/cert/../rootCA-key.pem',
      '/__dev/cert/rootCA.crt?path=rootCA-key.pem',
      '/__dev/cert/rootCA.crt/',
      '/api', '/admin',
    ];
    for (const path of forbidden) {
      const response = await fetch(base + path);
      assert.equal(response.status, 404, path);
      assert.equal(response.headers.get('cache-control'), 'no-store');
    }
    const write = await fetch(base + DOWNLOAD_PATH, { method: 'POST', body: 'x' });
    assert.equal(write.status, 404);
  } finally {
    await new Promise((resolve, reject) =>
      server.close((err) => err ? reject(err) : resolve()));
  }
});

test('rejects empty or non-buffer certificate material', () => {
  assert.throws(() => createPublicCaServer(Buffer.alloc(0)), /non-empty DER/);
  assert.throws(() => createPublicCaServer('rootCA.pem'), /non-empty DER/);
});
