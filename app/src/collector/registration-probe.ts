import { securityClockPolicy } from '../platform/security-clock-policy';
import { claimAttempt, flushClaimAttempt } from '../diagnostics/claim-delivery';
import { diagnosticTransport, advertisementError } from './claim-diagnostic-transport';
import type { ClaimAttempt } from '../diagnostics/claim-attempt';
import { DiagnosticError, errorCodes } from '../diagnostics/errors';
import type { NodeTransport } from '../platform/node-transport';
import { toBase64Url } from '../platform/device-keys';
import { getAuthState } from '../platform/auth';
import { assertAdminMayClaim } from './claim-node';
import { bindNodeOperation } from './node-operation';
import { PublicProbeTransport } from './public-probe';
import { usesAlphaPhoneClock } from './phone-time';
import { bootstrapRegistrationTime } from './registration-time';
import { decodeClaimAdvertisement, decodeFrame, encodeFrame, Opcode, PROTOCOL_VERSION } from './ble-codec';

// Identity preflight. Signed-time bootstrap may persist a time anchor, but
// never reserves ownership. Backend verification remains authoritative at claim.
// This proof binds the public UUID to the device key, never to its BLE name.
export async function verifyRegistrationCandidate(transport: NodeTransport, nodeId: string,
  organizationId: string, signal?: AbortSignal, alreadyConnected = false, diagnostic?: ClaimAttempt): Promise<void> {
  const attempt = diagnostic ?? claimAttempt(nodeId, organizationId, true);
  try {
    await registrationCandidate(diagnosticTransport(transport, attempt), nodeId, organizationId, signal, alreadyConnected, attempt);
    attempt.event('preflight', 'ok');
  } catch (error) { attempt.failure(error); throw error; }
  finally { flushClaimAttempt(attempt); }
}
async function registrationCandidate(transport: NodeTransport, nodeId: string, organizationId: string, signal: AbortSignal | undefined, alreadyConnected: boolean, attempt: ClaimAttempt): Promise<void> {
  attempt.event('discovered', 'ok');
  assertAdminMayClaim(organizationId);
  const operation = bindNodeOperation(transport, getAuthState().user!.userId, organizationId, signal, 'ADMIN');
  try {
    const hello = await new PublicProbeTransport(operation.transport).readPublicHello({ keepConnected: true, alreadyConnected });
    if (hello.nodeId !== nodeId || hello.claimState !== 0) throw new DiagnosticError(errorCodes.CLAIM_CHANGED, 'Napf-Status geändert. Bitte erneut auswählen.');
    attempt.metadata = { caps: hello.caps ?? 0, protocolVersion: hello.serverVer ?? 1, policy: securityClockPolicy() };
    attempt.event('hello', 'ok');
    attempt.event('policy', 'started');
    const alphaClock = usesAlphaPhoneClock(hello.caps ?? 0);
    attempt.event('policy', 'ok');
    if (alphaClock) await bootstrapRegistrationTime(operation.transport, nodeId, organizationId, operation.assertActive, hello.caps, attempt);
    await operation.transport.write(encodeFrame({ version: PROTOCOL_VERSION, opcode: Opcode.ClaimAdvertisementRequest, payload: new Uint8Array() }));
    let frame = decodeFrame(await operation.transport.read());
    if (frame?.opcode === Opcode.Error && !alphaClock) {
      await bootstrapRegistrationTime(operation.transport, nodeId, organizationId, operation.assertActive, 0, attempt);
      await operation.transport.write(encodeFrame({ version: PROTOCOL_VERSION, opcode: Opcode.ClaimAdvertisementRequest, payload: new Uint8Array() }));
      frame = decodeFrame(await operation.transport.read());
    }
    const proof = frame?.version === PROTOCOL_VERSION && frame.opcode === Opcode.ClaimAdvertisementResponse
      ? decodeClaimAdvertisement(frame.payload) : null;
    if (!proof) throw advertisementError(frame);
    if (proof.nodeId !== nodeId || proof.timestampMillis > BigInt(Number.MAX_SAFE_INTEGER)) throw new DiagnosticError(errorCodes.CLAIM_CHANGED, 'Ungültige Napf-Identität.');
    attempt.event('local_signature', 'started');
    const x = toBase64Url(proof.publicKey.slice(1, 33)), y = toBase64Url(proof.publicKey.slice(33));
    const key = await crypto.subtle.importKey('jwk', { kty: 'EC', crv: 'P-256', x, y }, { name: 'ECDSA', namedCurve: 'P-256' }, false, ['verify']);
    const message = new TextEncoder().encode(`MM-CLAIM-v1\n${nodeId}\n${x}\n${y}\n${proof.timestampMillis}\nclaim-mode`);
    if (!await crypto.subtle.verify({ name: 'ECDSA', hash: 'SHA-256' }, key, new Uint8Array(proof.signature), message)) {
      throw new DiagnosticError(errorCodes.CLAIM_SIGNATURE, 'Signatur des Napfs ungültig. Registrierung gesperrt.');
    }
    attempt.event('local_signature', 'ok');
    operation.assertActive();
  } finally { await operation.close(); }
}
