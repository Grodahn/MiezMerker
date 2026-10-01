export interface AppDeviceKeys {
  publicKey(): Promise<JsonWebKey>;
  signChallenge(challenge: Uint8Array): Promise<Uint8Array>;
}
export class UnconfiguredDeviceKeys implements AppDeviceKeys {
  async publicKey(): Promise<JsonWebKey> { throw new Error('AppDevice keys not configured'); }
  async signChallenge(_challenge: Uint8Array): Promise<Uint8Array> { throw new Error('AppDevice keys not configured'); }
}
// #17 owns the Web Crypto adapter and key/credential lifecycle.
