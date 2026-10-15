import type { NodeTransport } from '../platform/node-transport';
import { toBase64Url } from '../platform/device-keys';
import { getAuthState } from '../platform/auth';
import { assertAdminMayClaim, ClaimError } from './claim-node';
import { bindNodeOperation } from './node-operation';
import { PublicProbeTransport } from './public-probe';
import { usesAlphaPhoneClock } from './phone-time';
import { bootstrapRegistrationTime } from './registration-time';
import { decodeClaimAdvertisement, decodeFrame, encodeFrame, Opcode, PROTOCOL_VERSION } from './ble-codec';

// Identity preflight. Signed-time bootstrap may persist a time anchor, but
// never reserves ownership. Backend verification remains authoritative at claim.
// This proof binds the public UUID to the device key, never to its BLE name.
export async function verifyRegistrationCandidate(transport: NodeTransport, nodeId: string,
  organizationId: string, signal?: AbortSignal, alreadyConnected = false): Promise<void> {
  assertAdminMayClaim(organizationId);
  const operation = bindNodeOperation(transport, getAuthState().user!.userId, organizationId, signal, 'ADMIN');
  try {
    const hello = await new PublicProbeTransport(operation.transport).readPublicHello({ keepConnected: true, alreadyConnected });
    if (hello.nodeId !== nodeId || hello.claimState !== 0) throw new Error('Napf-Status geändert. Bitte erneut auswählen.');
    const alphaClock = usesAlphaPhoneClock(hello.caps ?? 0);
    if (alphaClock) await bootstrapRegistrationTime(operation.transport, nodeId, organizationId, operation.assertActive, hello.caps);
    await operation.transport.write(encodeFrame({ version: PROTOCOL_VERSION, opcode: Opcode.ClaimAdvertisementRequest, payload: new Uint8Array() }));
    let frame = decodeFrame(await operation.transport.read());
    if (frame?.opcode === Opcode.Error && !alphaClock) {
      await bootstrapRegistrationTime(operation.transport, nodeId, organizationId, operation.assertActive);
      await operation.transport.write(encodeFrame({ version: PROTOCOL_VERSION, opcode: Opcode.ClaimAdvertisementRequest, payload: new Uint8Array() }));
      frame = decodeFrame(await operation.transport.read());
    }
    const proof = frame?.version === PROTOCOL_VERSION && frame.opcode === Opcode.ClaimAdvertisementResponse
      ? decodeClaimAdvertisement(frame.payload) : null;
    if (!proof) throw new ClaimError('trusted-time', 'Kein gültiger Identitätsnachweis. Sicherheitszeit-Policy, Handyzeit (Alpha) bzw. signierten UTC-Bootstrap prüfen.');
    if (proof.nodeId !== nodeId || proof.timestampMillis > BigInt(Number.MAX_SAFE_INTEGER)) throw new Error('Ungültige Napf-Identität.');
    const x = toBase64Url(proof.publicKey.slice(1, 33)), y = toBase64Url(proof.publicKey.slice(33));
    const key = await crypto.subtle.importKey('jwk', { kty: 'EC', crv: 'P-256', x, y }, { name: 'ECDSA', namedCurve: 'P-256' }, false, ['verify']);
    const message = new TextEncoder().encode(`MM-CLAIM-v1\n${nodeId}\n${x}\n${y}\n${proof.timestampMillis}\nclaim-mode`);
    if (!await crypto.subtle.verify({ name: 'ECDSA', hash: 'SHA-256' }, key, new Uint8Array(proof.signature), message)) {
      throw new Error('Signatur des Napfs ungültig. Registrierung gesperrt.');
    }
    operation.assertActive();
  } finally { await operation.close(); }
}
