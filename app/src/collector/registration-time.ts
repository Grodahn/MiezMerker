import type { ClaimAttempt } from '../diagnostics/claim-attempt';
import { errorCodes } from '../diagnostics/errors';
import { api } from '../api/client';
import { fetchCsrfToken, getAuthState } from '../platform/auth';
import { toBase64Url } from '../platform/device-keys';
import { supplyAlphaPhoneClock, usesAlphaPhoneClock } from './phone-time';
import type { NodeTransport } from '../platform/node-transport';
import { assertAdminMayClaim, ClaimError } from './claim-node';
import { decodeFrame, encodeFrame, encodeClaimReceipt, Opcode } from './ble-codec';

export class TimeBootstrapUnavailableError extends ClaimError {
  constructor(message: string) { super('trusted-time', message, errorCodes.CLOCK_BOOTSTRAP); this.name = 'TimeBootstrapUnavailableError'; }
}

// Shared purpose-scoped bridge to ADR 0017. The phone forwards signed bytes; only
// the firmware's independently pinned verifier may establish trusted UTC.
export async function bootstrapRegistrationTime(transport: NodeTransport, nodeId: string,
  organizationId: string, assertActive: () => void, caps = 0, attempt?: ClaimAttempt): Promise<number> {
  assertActive(); assertAdminMayClaim(organizationId);
  if (usesAlphaPhoneClock(caps)) {
    await supplyAlphaPhoneClock(transport, () => { assertActive(); assertAdminMayClaim(organizationId); });
    return 0;
  }
  return bootstrapNodeTime(transport, nodeId, organizationId, () => {
    assertActive(); assertAdminMayClaim(organizationId);
  }, 'UNCLAIMED', undefined, attempt);
}

export async function bootstrapNodeTime(transport: NodeTransport, nodeId: string,
  organizationId: string, assertActive: () => void, registrationState: 'UNCLAIMED' | 'CLAIMED',
  incarnation?: string, attempt?: ClaimAttempt): Promise<number> {
  const started = performance.now();
  assertActive();
  const exchange = async (opcode: Opcode, payload: Uint8Array = new Uint8Array()) => {
    await transport.write(encodeFrame({ version: 1, opcode, payload }));
    const frame = decodeFrame(await transport.read());
    assertActive();
    if (!frame || frame.version !== 1 || frame.opcode !== opcode + 1) {
      throw new ClaimError('trusted-time', opcode === Opcode.RegistrationTimeProofRequest
        ? 'Signierter UTC-Proof vom Napf abgelehnt: Issuer-Pin mit lokalem Setup und ESP-Log vergleichen; danach mit neuer Challenge erneut versuchen. Ungültige, abgelaufene oder wiederverwendete Proofs bleiben gesperrt.'
        : 'Vertrauenswürdige UTC-Zeit fehlt. Firmware mit signiertem Zeit-Bootstrap und passendem Issuer-Schlüssel erforderlich; issuer_pin im ESP-Log prüfen.',
        opcode === Opcode.RegistrationTimeProofRequest ? errorCodes.CLOCK_REJECTED : errorCodes.CLOCK_CHALLENGE);
    }
    return frame.payload;
  };
  const bytes = await exchange(Opcode.RegistrationTimeChallengeRequest);
  if (bytes.length !== 239) throw new ClaimError('trusted-time', 'Ungültige Zeit-Challenge des Napfs.', errorCodes.CLOCK_CHALLENGE);
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  const uuid = (offset: number) => {
    const hex = Array.from(bytes.slice(offset, offset + 16), b => b.toString(16).padStart(2, '0')).join('');
    return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
  };
  if (view.getUint16(0, true) !== 1 || uuid(2) !== nodeId.toLowerCase() || (incarnation && uuid(18) !== incarnation.toLowerCase()) || view.getUint32(34, true) === 0 || bytes[78] !== 4) {
    throw new ClaimError('trusted-time', 'Zeit-Challenge gehört nicht zum ausgewählten Napf.', errorCodes.CLOCK_CHALLENGE);
  }
  const uptime = view.getBigUint64(70, true);
  const challenge = {
    version: 1, nodeId, incarnation: uuid(18), bootCounter: view.getUint32(34, true),
    challengeNonce: toBase64Url(bytes.slice(38, 70)), challengeUptimeMs: 0,
    devicePublicKey: toBase64Url(bytes.slice(78, 143)),
    devicePublicKeyFingerprint: toBase64Url(bytes.slice(143, 175)),
    deviceChallengeSignature: toBase64Url(bytes.slice(175)),
  };
  const csrf = getAuthState().csrfToken ?? await fetchCsrfToken();
  assertActive();
  const { data, error, response } = await api.POST('/api/v1/nodes/time-bootstrap', {
    body: { organizationId, registrationState, challenge },
    // Preserve uint64 as a JSON integer, including values beyond Number's range.
    bodySerializer: body => JSON.stringify(body).replace('"challengeUptimeMs":0', `"challengeUptimeMs":${uptime}`),
    headers: { ...(csrf ? { 'X-XSRF-TOKEN': csrf } : {}), ...(attempt ? { 'X-MM-Claim-Attempt': attempt.attemptId } : {}) }, signal: AbortSignal.timeout(10_000),
  }).catch(error => {
    assertActive();
    const detail = 'Keine bestätigte Antwort.';
    throw new TimeBootstrapUnavailableError(`Backend-Zeitservice nicht erreichbar. Signierter Zeit-Bootstrap noch nicht bestätigt. Internet und Backend prüfen. ${detail}`);
  });
  assertActive();
  if (error || !data?.proof) {
    const diagnostic = response?.status === 503 ? response.headers?.get('X-MM-Time-Bootstrap') : null;
    if (response?.status === 503) throw new TimeBootstrapUnavailableError(
      diagnostic === 'issuer-key-missing'
        ? 'Zeit-Bootstrap gesperrt: stabiler Backend-Issuer-Schlüssel fehlt. Lokales Issuer-Setup ausführen und IntelliJ-Konfiguration laden; Firmware-Pin vergleichen.'
        : diagnostic === 'utc-unconfirmed'
          ? 'Zeit-Bootstrap gesperrt: Backend-UTC nicht als gesund bestätigt. Serveruhr/NTP prüfen und die lokale UTC-Attestation ausdrücklich setzen.'
          : 'Zeit-Bootstrap nicht verfügbar. Backend benötigt einen konfigurierten Issuer-Schlüssel und eine bestätigte gesunde UTC-Zeit.');
    if (response?.status === 429) throw new TimeBootstrapUnavailableError('Zeitservice ausgelastet. Bitte kurz warten und erneut versuchen.');
    throw new ClaimError('trusted-time', 'Signierter Zeit-Bootstrap abgelehnt. Aktive Mitgliedschaft, Internet und Napf-Zuordnung prüfen.', errorCodes.CLOCK_REJECTED);
  }
  const result = await exchange(Opcode.RegistrationTimeProofRequest, encodeClaimReceipt(data.proof));
  if (result.length !== 1 || result[0] !== 0) throw new ClaimError('trusted-time', 'Napf hat die signierte Zeit nicht bestätigt.', errorCodes.CLOCK_CONFIRMATION);
  // Scheduling hint only: include the full round trip plus server/sampling/
  // drift uncertainty. Strict firmware verification remains authoritative,
  // even if the phone's timer is inaccurate.
  return Math.ceil((performance.now() - started) * 1.01 + 2100);
}
