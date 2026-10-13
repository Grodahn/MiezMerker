import type { NodeTransport } from '../platform/node-transport';
import { toBase64Url } from '../platform/device-keys';
import { CollectorDatabase } from '../platform/offline-store';
import { assertAdminMayClaim, claimNode, ClaimError } from './claim-node';
import { getAuthState } from '../platform/auth';
import { bindNodeOperation } from './node-operation';
import { PublicProbeTransport } from './public-probe';
import { bootstrapRegistrationTime } from './registration-time';
import { decodeFrame, decodeClaimAdvertisement, encodeFrame, encodeClaimReceipt, Opcode, PROTOCOL_VERSION } from './ble-codec';

// Reservation and receipt persist before delivery. A retry after disconnect can
// redeliver the same backend-issued receipt without recreating or losing it.
export async function provisionNode(transport: NodeTransport, nodeId: string, organizationId: string, signal?: AbortSignal): Promise<void> {
  assertAdminMayClaim(organizationId);
  const operation = bindNodeOperation(transport, getAuthState().user!.userId, organizationId, signal, 'ADMIN');
  transport = operation.transport;
  const db = new CollectorDatabase();
  const roundTrip = async (opcode: Opcode, payload: Uint8Array = new Uint8Array()): Promise<Uint8Array> => {
    await transport.write(encodeFrame({ version: PROTOCOL_VERSION, opcode, payload }));
    const frame = decodeFrame(await transport.read());
    if (!frame || frame.version !== PROTOCOL_VERSION || frame.opcode !== opcode + 1) {
      throw new ClaimError('backend', 'Registrierung abgelehnt. Signierten Identitätsnachweis und vertrauenswürdige UTC-Zeit prüfen. Gespeichertes Receipt bleibt retrybar.');
    }
    return frame.payload;
  };
  try {
    const hello = await new PublicProbeTransport(transport).readPublicHello();
    if (hello.nodeId !== nodeId) throw new ClaimError('backend', 'Node-Identität hat sich geändert. Bitte neu auswählen.');
    if (hello.claimState === 1 && hello.owner?.organizationId !== organizationId) {
      throw new ClaimError('conflict-foreign', 'Node gehört einer anderen Organisation.');
    }
    if (hello.claimState === 1) throw new ClaimError('backend', 'Dieser Napf ist bereits registriert. Bitte erneut auslesen und Einrichtung fortsetzen; kein erneutes Claiming.');
    const receipt = (await db.nodeMeta.get(nodeId));
    let savedReceipt = receipt?.organizationId === organizationId ? receipt.claimReceipt : undefined;
    let issuanceWaitMs = 2100;
    await transport.connect();
    // Even cached receipt recovery needs fresh trusted time after a node reboot.
    await transport.write(encodeFrame({ version: PROTOCOL_VERSION, opcode: Opcode.ClaimAdvertisementRequest, payload: new Uint8Array() }));
    let frame = decodeFrame(await transport.read());
    if (frame?.opcode === Opcode.Error) {
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
      // A bounded retry renews an expired receipt or an older, wider security
      // interval. Disconnect/abort errors propagate without another reservation.
      if (!(error instanceof ClaimError)) throw error;
      operation.assertActive();
      issuanceWaitMs = await bootstrapRegistrationTime(transport, nodeId, organizationId, operation.assertActive);
      savedReceipt = await retrieveReceipt();
      if (!savedReceipt) throw new ClaimError('backend', 'Claim-Receipt fehlt.');
      await waitForIssuanceBound();
      response = await roundTrip(Opcode.ClaimReceiptRequest, encodeClaimReceipt(savedReceipt));
    }
    if (response.length !== 1 || response[0] !== 0) throw new ClaimError('backend', 'Node hat Receipt nicht bestätigt. Bitte erneut versuchen.');
    await transport.disconnect();
    const after = await new PublicProbeTransport(transport).readPublicHello();
    if (after.nodeId !== nodeId || after.claimState !== 1 || after.owner?.organizationId !== organizationId) {
      throw new ClaimError('backend', 'Node-Provisionierung noch nicht bestätigt. Receipt bleibt lokal gespeichert.');
    }
    await db.nodeMeta.update(nodeId, { claimState: 1 });
  } finally { await operation.close(); db.close(); }
}
