import { toBase64Url } from './device-keys';

/** Coordinates must come from the authenticated backend/verified claim receipt,
 * never from the node currently being authenticated. */
export interface TrustedNodeIdentity {
  nodeId: string;
  publicKeyX: string;
  publicKeyY: string;
}

/** One fresh, single-use proof of possession for a later node session (#18).
 * The GATT adapter sends challenge and returns the node's raw ES256 signature. */
export async function beginNodeAuthentication(identity: TrustedNodeIdentity) {
  if (!/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(identity.nodeId)) {
    throw new Error('Invalid node identity');
  }
  const nodeId = identity.nodeId.toLowerCase();
  const key = await crypto.subtle.importKey('jwk', {
    kty: 'EC', crv: 'P-256', x: identity.publicKeyX, y: identity.publicKeyY,
  }, { name: 'ECDSA', namedCurve: 'P-256' }, false, ['verify']);
  const nonce = crypto.getRandomValues(new Uint8Array(32));
  const message = new TextEncoder().encode(`MM-NODE-SESSION-v1\n${nodeId}\n${toBase64Url(nonce)}`);
  const expiresAt = Date.now() + 30_000;
  let consumed = false;
  return {
    challenge: nonce.slice(),
    async verify(signature: Uint8Array): Promise<boolean> {
      if (consumed) return false;
      consumed = true;
      if (Date.now() >= expiresAt || signature.length !== 64) return false;
      try {
        return await crypto.subtle.verify({ name: 'ECDSA', hash: 'SHA-256' }, key,
          new Uint8Array(signature), message);
      } catch {
        return false;
      }
    },
  };
}
