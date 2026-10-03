import { describe, expect, test, vi, beforeEach } from 'vitest';
import {
  WebCryptoDeviceKeys,
  toBase64Url,
  fromBase64Url,
  fixed32,
} from './device-keys';

describe('Web Crypto AppDevice keys (#17)', () => {
  test('generates a non-exportable P-256 key pair with base64url coordinates', async () => {
    const keys = await WebCryptoDeviceKeys.generate();
    const jwk = await keys.publicKey();
    expect(jwk.kty).toBe('EC');
    expect(jwk.crv).toBe('P-256');
    expect(jwk.x).toMatch(/^[A-Za-z0-9_-]{43}$/);
    expect(jwk.y).toMatch(/^[A-Za-z0-9_-]{43}$/);
    const coords = keys.coordinates();
    expect(coords.x).toBe(jwk.x);
    expect(coords.y).toBe(jwk.y);
  });

  test('signs a challenge with raw 64-byte r||s ECDSA', async () => {
    const keys = await WebCryptoDeviceKeys.generate();
    const challenge = new Uint8Array(32).map((_, i) => i);
    const signature = await keys.signChallenge(challenge);
    expect(signature).toHaveLength(64);
    await expect(keys.signChallenge(new Uint8Array(16))).rejects.toThrow('32 bytes');
  });

  test('signatures differ per challenge (fresh challenge protection)', async () => {
    const keys = await WebCryptoDeviceKeys.generate();
    const c1 = new Uint8Array(32).fill(1);
    const c2 = new Uint8Array(32).fill(2);
    const s1 = await keys.signChallenge(c1);
    const s2 = await keys.signChallenge(c2);
    const b1 = new Uint8Array(s1).join(',');
    const b2 = new Uint8Array(s2).join(',');
    expect(b1).not.toBe(b2);
  });
});

describe('base64url helpers', () => {
  test('round-trips bytes without padding', () => {
    const bytes = new Uint8Array([0, 1, 2, 250, 251, 255]);
    const encoded = toBase64Url(bytes);
    expect(encoded).not.toContain('=');
    expect(encoded).not.toContain('+');
    expect(encoded).not.toContain('/');
    expect(Array.from(fromBase64Url(encoded))).toEqual(Array.from(bytes));
  });

  test('fixed32 left-pads short values', () => {
    expect(Array.from(fixed32(new Uint8Array([1, 2, 3]))).slice(29)).toEqual([1, 2, 3]);
    expect(fixed32(new Uint8Array(32).fill(9)).every((b) => b === 9)).toBe(true);
  });
});
