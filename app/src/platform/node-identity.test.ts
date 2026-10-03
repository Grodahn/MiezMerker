// @vitest-environment node
import { expect, test, vi } from 'vitest';
import { beginNodeAuthentication } from './node-identity';
import { toBase64Url } from './device-keys';

const nodeId = 'e7062544-6382-41c0-9ffe-1d3c5b7a99b8';
async function keys() {
  const pair = await crypto.subtle.generateKey({ name: 'ECDSA', namedCurve: 'P-256' }, true, ['sign', 'verify']);
  const jwk = await crypto.subtle.exportKey('jwk', pair.publicKey);
  return { pair, identity: { nodeId, publicKeyX: jwk.x!, publicKeyY: jwk.y! } };
}
async function sign(key: CryptoKey, nonce: Uint8Array, id = nodeId) {
  const bytes = new TextEncoder().encode(`MM-NODE-SESSION-v1\n${id}\n${toBase64Url(nonce)}`);
  return new Uint8Array(await crypto.subtle.sign({ name: 'ECDSA', hash: 'SHA-256' }, key, bytes));
}

test('real persisted node key authenticates once; copied node ID with another key fails', async () => {
  const real = await keys();
  const fake = await keys();
  const attempt = await beginNodeAuthentication(real.identity);
  expect(await attempt.verify(await sign(fake.pair.privateKey, attempt.challenge))).toBe(false);
  const success = await beginNodeAuthentication(real.identity);
  const signature = await sign(real.pair.privateKey, success.challenge);
  expect(await success.verify(signature)).toBe(true);
  expect(await success.verify(signature)).toBe(false);
});

test('old challenge signature cannot authenticate a fresh session', async () => {
  const real = await keys();
  const first = await beginNodeAuthentication(real.identity);
  const second = await beginNodeAuthentication(real.identity);
  expect(second.challenge).not.toEqual(first.challenge);
  expect(await second.verify(await sign(real.pair.privateKey, first.challenge))).toBe(false);
});

test('session proof is bound to the node ID and a private copy of the challenge', async () => {
  const real = await keys();
  const attempt = await beginNodeAuthentication(real.identity);
  attempt.challenge.fill(7);
  expect(await attempt.verify(await sign(real.pair.privateKey, attempt.challenge))).toBe(false);
  const other = await beginNodeAuthentication(real.identity);
  expect(await other.verify(await sign(real.pair.privateKey, other.challenge,
    'f7062544-6382-41c0-9ffe-1d3c5b7a99b8'))).toBe(false);
});

test('expired and malformed proofs fail closed', async () => {
  const real = await keys();
  const attempt = await beginNodeAuthentication(real.identity);
  const signature = await sign(real.pair.privateKey, attempt.challenge);
  const wallClock = vi.spyOn(Date, 'now').mockReturnValue(Date.now() - 3_600_000);
  const clock = vi.spyOn(performance, 'now').mockReturnValue(performance.now() + 31_000);
  expect(await attempt.verify(signature)).toBe(false);
  clock.mockRestore();
  wallClock.mockRestore();
  const malformed = await beginNodeAuthentication(real.identity);
  expect(await malformed.verify(new Uint8Array(63))).toBe(false);
});

