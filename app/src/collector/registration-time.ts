import { api } from '../api/client';
import { fetchCsrfToken, getAuthState } from '../platform/auth';
import { toBase64Url } from '../platform/device-keys';
import type { NodeTransport } from '../platform/node-transport';
import { assertAdminMayClaim, ClaimError } from './claim-node';
import { decodeFrame, encodeFrame, encodeClaimReceipt, Opcode } from './ble-codec';

// Registration-only bridge to ADR 0017. The phone forwards signed bytes; only
// the firmware's independently pinned verifier may establish trusted UTC.
export async function bootstrapRegistrationTime(transport: NodeTransport, nodeId: string,
  organizationId: string, assertActive: () => void): Promise<number> {
  const started = performance.now();
  assertActive(); assertAdminMayClaim(organizationId);
  const exchange = async (opcode: Opcode, payload: Uint8Array = new Uint8Array()) => {
    await transport.write(encodeFrame({ version: 1, opcode, payload }));
    const frame = decodeFrame(await transport.read());
    assertActive();
    if (!frame || frame.version !== 1 || frame.opcode !== opcode + 1) {
      throw new ClaimError('trusted-time', 'Vertrauenswürdige UTC-Zeit fehlt. Firmware mit signiertem Zeit-Bootstrap und passendem Issuer-Schlüssel erforderlich.');
    }
    return frame.payload;
  };
  const bytes = await exchange(Opcode.RegistrationTimeChallengeRequest);
  if (bytes.length !== 239) throw new ClaimError('trusted-time', 'Ungültige Zeit-Challenge des Napfs.');
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  const uuid = (offset: number) => {
    const hex = Array.from(bytes.slice(offset, offset + 16), b => b.toString(16).padStart(2, '0')).join('');
    return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
  };
  if (view.getUint16(0, true) !== 1 || uuid(2) !== nodeId || bytes[78] !== 4) {
    throw new ClaimError('trusted-time', 'Zeit-Challenge gehört nicht zum ausgewählten Napf.');
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
  assertActive(); assertAdminMayClaim(organizationId);
  const { data, error, response } = await api.POST('/api/v1/nodes/time-bootstrap', {
    body: { organizationId, registrationState: 'UNCLAIMED', challenge },
    // Preserve uint64 as a JSON integer, including values beyond Number's range.
    bodySerializer: body => JSON.stringify(body).replace('"challengeUptimeMs":0', `"challengeUptimeMs":${uptime}`),
    headers: csrf ? { 'X-XSRF-TOKEN': csrf } : {}, signal: AbortSignal.timeout(10_000),
  });
  assertActive(); assertAdminMayClaim(organizationId);
  if (error || !data?.proof) {
    throw new ClaimError('trusted-time', response?.status === 503
      ? 'Zeit-Bootstrap nicht verfügbar. Backend benötigt einen konfigurierten Issuer-Schlüssel und eine bestätigte gesunde UTC-Zeit.'
      : 'Signierter Zeit-Bootstrap abgelehnt. Aktive ADMIN-Mitgliedschaft, Internet und Napf-Zuordnung prüfen.');
  }
  const result = await exchange(Opcode.RegistrationTimeProofRequest, encodeClaimReceipt(data.proof));
  if (result.length !== 1 || result[0] !== 0) throw new ClaimError('trusted-time', 'Napf hat die signierte Zeit nicht bestätigt.');
  // Scheduling hint only: include the full round trip plus server/sampling/
  // drift uncertainty. Strict firmware verification remains authoritative,
  // even if the phone's timer is inaccurate.
  return Math.ceil((performance.now() - started) * 1.01 + 2100);
}
