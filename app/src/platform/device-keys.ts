// #17: Web Crypto AppDevice identity. P-256 key pair, private key non-exportable
// where supported. Public key coordinates are base64url (43 chars) for the backend.
export interface AppDeviceKeys {
  publicKey(): Promise<JsonWebKey>;
  signChallenge(challenge: Uint8Array): Promise<Uint8Array>;
}

function toBase64Url(bytes: Uint8Array): string {
  let binary = '';
  for (const b of bytes) binary += String.fromCharCode(b);
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

function fromBase64Url(value: string): Uint8Array {
  const base64 = value.replace(/-/g, '+').replace(/_/g, '/');
  const padded = base64 + '='.repeat((4 - (base64.length % 4)) % 4);
  const binary = atob(padded);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
  return bytes;
}

function fixed32(bytes: Uint8Array): Uint8Array {
  const out = new Uint8Array(32);
  out.set(bytes.slice(-32), 32 - Math.min(bytes.length, 32));
  return out;
}

export class WebCryptoDeviceKeys implements AppDeviceKeys {
  private readonly privateKey: CryptoKey;
  private readonly publicKeyHandle: CryptoKey;
  private readonly x: string;
  private readonly y: string;

  private constructor(privateKey: CryptoKey, publicKeyHandle: CryptoKey, x: string, y: string) {
    this.privateKey = privateKey;
    this.publicKeyHandle = publicKeyHandle;
    this.x = x;
    this.y = y;
  }

  static async generate(): Promise<WebCryptoDeviceKeys> {
    const pair = await crypto.subtle.generateKey(
      { name: 'ECDSA', namedCurve: 'P-256' },
      false,
      ['sign'],
    );
    const jwk = await crypto.subtle.exportKey('jwk', pair.publicKey);
    if (!jwk.x || !jwk.y) throw new Error('Web Crypto returned no P-256 coordinates');
    return new WebCryptoDeviceKeys(pair.privateKey, pair.publicKey, jwk.x, jwk.y);
  }

  async publicKey(): Promise<JsonWebKey> {
    return { kty: 'EC', crv: 'P-256', x: this.x, y: this.y };
  }

  coordinates(): { x: string; y: string } {
    return { x: this.x, y: this.y };
  }

  // ECDSA P-256 + SHA-256 via Web Crypto returns raw 64-byte r||s for this curve,
  // which is exactly the backend/ESP32 wire format (IEEE P1363).
  async signChallenge(challenge: Uint8Array): Promise<Uint8Array> {
    const buffer = new Uint8Array(challenge);
    const signature = await crypto.subtle.sign(
      { name: 'ECDSA', hash: 'SHA-256' },
      this.privateKey,
      buffer,
    );
    return new Uint8Array(signature);
  }
}

export class UnconfiguredDeviceKeys implements AppDeviceKeys {
  async publicKey(): Promise<JsonWebKey> { throw new Error('AppDevice keys not configured'); }
  async signChallenge(_challenge: Uint8Array): Promise<Uint8Array> { throw new Error('AppDevice keys not configured'); }
}

export { toBase64Url, fromBase64Url, fixed32 };
