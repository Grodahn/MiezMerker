import { DiagnosticError, errorCodes, safeFailure } from '../diagnostics/errors';
import type { NodeTransport } from '../platform/node-transport';
import { toBase64Url } from '../platform/device-keys';
import { CollectorDatabase } from '../platform/offline-store';
import { assertAdminMayClaim, claimNode, ClaimError } from './claim-node';
import { getAuthState } from '../platform/auth';
import { bindNodeOperation, NodeOperationCancelledError } from './node-operation';
import { PublicProbeTransport } from './public-probe';
import { usesAlphaPhoneClock } from './phone-time';
import { bootstrapRegistrationTime } from './registration-time';
import { runFieldSync } from './collector-sync';
import { decodeFrame, decodeClaimAdvertisement, encodeFrame, encodeClaimReceipt, Opcode, PROTOCOL_VERSION, CAP_CLOCK_SNAPSHOT } from './ble-codec';

// Reservation and receipt persist before delivery. A retry after disconnect can
// redeliver the same backend-issued receipt without recreating or losing it.
//
// Issue #161: a receipt accepted by firmware but lost on BLE transport must not
// surface as a generic claim failure that invites a second cryptographic
// claim. Transport failures after the backend receipt exists map to
// ClaimError(kind='uncertain'): the claim may already be committed, the
// receipt stays retryable, and the UI must recover via authoritative
// backend/node state (fetchNodeSetupState) instead of re-claiming.
function isProvisioningCancellation(error: unknown): boolean {
  if (error instanceof NodeOperationCancelledError) return true;
  if (error instanceof DOMException && error.name === 'AbortError') return true;
  if (error && typeof error === 'object' && (error as { name?: unknown }).name === 'AbortError') return true;
  const message = error instanceof Error ? error.message : String(error ?? '');
  return /abgebrochen.*(Benutzer|Organisation)|Node-Vorgang abgebrochen/i.test(message);
}

function toUncertainError(error: unknown): ClaimError {
  if (error instanceof ClaimError && error.kind === 'uncertain') return error;
  const detail = error instanceof DiagnosticError ? error.message.replace(` [${error.code}]`, '') : 'Übertragung oder Bestätigung unterbrochen';
  return new ClaimError('uncertain',
    `Napf möglicherweise bereits registriert, Bestätigung verloren (${detail}). Receipt bleibt gespeichert; Einrichtung ohne erneutes Claiming über Backend-Status fortsetzen.`);
}
export async function provisionNode(transport: NodeTransport, nodeId: string, organizationId: string, signal?: AbortSignal): Promise<void | { timeAnchorPending: true; message: string }> {
  assertAdminMayClaim(organizationId);
  const operation = bindNodeOperation(transport, getAuthState().user!.userId, organizationId, signal, 'ADMIN');
  transport = operation.transport;
  const db = new CollectorDatabase();
  const roundTrip = async (opcode: Opcode, payload: Uint8Array = new Uint8Array()): Promise<Uint8Array> => {
    await transport.write(encodeFrame({ version: PROTOCOL_VERSION, opcode, payload }));
    const frame = decodeFrame(await transport.read());
    if (!frame || frame.version !== PROTOCOL_VERSION || frame.opcode !== opcode + 1) {
      throw new ClaimError('backend', 'Registrierung abgelehnt. Signierten Identitätsnachweis und Sicherheitszeit-Policy (Alpha: Handyzeit) prüfen. Gespeichertes Receipt bleibt retrybar.');
    }
    return frame.payload;
  };
  try {
    const hello = await new PublicProbeTransport(transport).readPublicHello({ keepConnected: true });
    if (hello.nodeId !== nodeId) throw new ClaimError('backend', 'Node-Identität hat sich geändert. Bitte neu auswählen.');
    if (hello.claimState === 1 && hello.owner?.organizationId !== organizationId) {
      throw new ClaimError('conflict-foreign', 'Node gehört einer anderen Organisation.');
    }
    if (hello.claimState === 1) throw new ClaimError('backend', 'Dieser Napf ist bereits registriert. Bitte erneut auslesen und Einrichtung fortsetzen; kein erneutes Claiming.');
    const receipt = (await db.nodeMeta.get(nodeId));
    let savedReceipt = receipt?.organizationId === organizationId ? receipt.claimReceipt : undefined;
    let issuanceWaitMs = 2100;
    // Receipt recovery needs fresh policy-selected time after reboot.
    const alphaClock = usesAlphaPhoneClock(hello.caps ?? 0);
    if (alphaClock) issuanceWaitMs = await bootstrapRegistrationTime(transport, nodeId, organizationId, operation.assertActive, hello.caps);
    await transport.write(encodeFrame({ version: PROTOCOL_VERSION, opcode: Opcode.ClaimAdvertisementRequest, payload: new Uint8Array() }));
    let frame = decodeFrame(await transport.read());
    if (frame?.opcode === Opcode.Error && !alphaClock) {
      issuanceWaitMs = await bootstrapRegistrationTime(transport, nodeId, organizationId, operation.assertActive);
      frame = decodeFrame(encodeFrame({ version: 1, opcode: Opcode.ClaimAdvertisementResponse,
        payload: await roundTrip(Opcode.ClaimAdvertisementRequest) }));
    }
    const advertisement = frame?.version === 1 && frame.opcode === Opcode.ClaimAdvertisementResponse
      ? decodeClaimAdvertisement(frame.payload) : null;
    if (!advertisement || advertisement.nodeId !== nodeId || advertisement.timestampMillis > BigInt(Number.MAX_SAFE_INTEGER)) {
      throw new ClaimError('backend', 'Ungültiger Claim-Nachweis vom Node.');
    }
    const retrieveReceipt = async () => {
      const outcome = await claimNode(organizationId, {
        nodeId, publicKeyX: toBase64Url(advertisement.publicKey.slice(1, 33)),
        publicKeyY: toBase64Url(advertisement.publicKey.slice(33)),
        claimSignature: toBase64Url(advertisement.signature), timestampMillis: Number(advertisement.timestampMillis),
      }, { assertActive: operation.assertActive });
      return outcome.receipt;
    };
    if (!savedReceipt) savedReceipt = await retrieveReceipt();
    operation.assertActive();
    if (!savedReceipt) throw new ClaimError('backend', 'Claim-Receipt fehlt.');
    // ADR 0017 includes a 1000 ms server error bound plus sampling/drift.
    // A freshly issued receipt's iat may initially overlap the lower bound.
    // Let monotonic time advance; firmware still applies the strict [L,U] guard.
    const waitForIssuanceBound = async () => {
      await new Promise(resolve => setTimeout(resolve, issuanceWaitMs));
      operation.assertActive();
    };
    await waitForIssuanceBound();
    let response: Uint8Array;
    try { response = await roundTrip(Opcode.ClaimReceiptRequest, encodeClaimReceipt(savedReceipt)); }
    catch (error) {
      // Renew a rejected/expired cached receipt once through the same-owner
      // backend path. Alpha retains its original session candidate: no clock
      // replacement or signed-time handshake. A short monotonic wait lets a
      // newly issued receipt's iat become valid; firmware still enforces it.
      // Disconnect/abort errors propagate without another reservation.
      if (isProvisioningCancellation(error)) throw error;
      if (!(error instanceof ClaimError)) throw toUncertainError(error);
      operation.assertActive();
      issuanceWaitMs = alphaClock ? 2100
        : await bootstrapRegistrationTime(transport, nodeId, organizationId, operation.assertActive);
      savedReceipt = await retrieveReceipt();
      if (!savedReceipt) throw new ClaimError('backend', 'Claim-Receipt fehlt.');
      await waitForIssuanceBound();
      try {
        response = await roundTrip(Opcode.ClaimReceiptRequest, encodeClaimReceipt(savedReceipt));
      } catch (retryError) {
        if (isProvisioningCancellation(retryError)) throw retryError;
        if (!(retryError instanceof ClaimError)) throw toUncertainError(retryError);
        throw retryError;
      }
    }
    if (response.length !== 1 || response[0] !== 0) throw new ClaimError('backend', 'Node hat Receipt nicht bestätigt. Bitte erneut versuchen.');
    // Firmware refreshes public Hello/Owner from the committed identity before
    // acknowledging the receipt. Verify those reads on this connection instead
    // of making Android rediscover every characteristic immediately after claim.
    // Final cleanup disconnects; the next protected sync uses a fresh authorizer.
    // A transport loss here (after the receipt ACK) leaves the commit uncertain:
    // the firmware may already be CLAIMED while this connection cannot prove it.
    let after: Awaited<ReturnType<PublicProbeTransport['readPublicHello']>>;
    try {
      after = await new PublicProbeTransport(transport).readPublicHello({ alreadyConnected: true, keepConnected: true });
    } catch (error) {
      if (isProvisioningCancellation(error)) throw error;
      if (error instanceof ClaimError) throw error;
      throw toUncertainError(error);
    }
    if (after.nodeId !== nodeId || after.incarnation !== hello.incarnation || after.claimState !== 1 || after.owner?.organizationId !== organizationId) {
      throw new ClaimError('backend', 'Node-Provisionierung noch nicht bestätigt. Receipt bleibt lokal gespeichert.');
    }
    await db.nodeMeta.update(nodeId, { claimState: 1 });
    if ((after.caps ?? 0) & CAP_CLOCK_SNAPSHOT) {
      // Claim completion grants no session. Reuse the ordinary credential/PoP/
      // pinned-Node-auth sync flow for the initial phone anchor.
      // The claim itself is already committed (receipt ACK + owner verified +
      // durable claimState); a lost anchor sync must not turn it into a claim
      // failure. Surface it as pending instead (#161).
      try {
        const result = await runFieldSync({ createTransport: () => transport, signal, onUpdate: () => {} });
        operation.assertActive();
        if (result.nodeState !== 'complete') return { timeAnchorPending: true, message: `Napf registriert; erste Zeitaufnahme noch ausstehend. Bitte erneut auslesen. ${result.nodeMessage}` };
      } catch (error) {
        if (isProvisioningCancellation(error)) throw error;
        const detail = safeFailure(error, 'Übertragung oder Bestätigung unterbrochen', errorCodes.CLOCK_SNAPSHOT);
        return { timeAnchorPending: true, message: `Napf registriert; erste Zeitaufnahme noch ausstehend. Bitte erneut auslesen. ${detail}` };
      }
    }
  } finally { await operation.close(); db.close(); }
}
