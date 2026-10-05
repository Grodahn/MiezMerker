import { toBase64Url } from './device-keys';
import { api } from '../api/client';

/** Coordinates must come from the authenticated backend/verified claim receipt,
 * never from the node currently being authenticated. */
export interface TrustedNodeIdentity {
  nodeId: string;
  publicKeyX: string;
  publicKeyY: string;
}

export type NodeIdentityErrorKind = 'forbidden' | 'unavailable';

export class NodeIdentityError extends Error {
  constructor(
    readonly kind: NodeIdentityErrorKind,
    message: string,
    readonly status?: number,
  ) {
    super(message);
    this.name = 'NodeIdentityError';
  }
}

export async function loadTrustedNodeIdentity(nodeId: string): Promise<TrustedNodeIdentity> {
  const { data, error, response } = await api.GET('/api/v1/nodes/{nodeId}', {
    signal: AbortSignal.timeout(5_000),
    params: { path: { nodeId } },
  });
  if (error || !data || data.nodeId?.toLowerCase() !== nodeId.toLowerCase()
      || data.state !== 'CLAIMED' || !data.publicKeyX || !data.publicKeyY) {
    const status = (response as Response | undefined)?.status;
    // 403/404 means the node exists but is not visible to this tenant (foreign
    // organization) — distinct from a network/offline failure.
    if (status === 403 || status === 404) {
      throw new NodeIdentityError('forbidden', 'Node belongs to another organization', status);
    }
    throw new NodeIdentityError('unavailable', 'Trusted node identity unavailable', status);
  }
  return { nodeId: data.nodeId, publicKeyX: data.publicKeyX, publicKeyY: data.publicKeyY };
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
  const expiresAt = performance.now() + 30_000;
  let consumed = false;
  return {
    challenge: nonce.slice(),
    async verify(signature: Uint8Array): Promise<boolean> {
      if (consumed) return false;
      consumed = true;
      if (performance.now() >= expiresAt || signature.length !== 64) return false;
      try {
        const valid = await crypto.subtle.verify({ name: 'ECDSA', hash: 'SHA-256' }, key,
          new Uint8Array(signature), message);
        return valid && performance.now() < expiresAt;
      } catch {
        return false;
      }
    },
  };
}
