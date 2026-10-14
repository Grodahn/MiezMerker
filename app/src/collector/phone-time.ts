import type { NodeTransport } from '../platform/node-transport';
import { decodeFrame, encodeFrame, Opcode } from './ble-codec';
import type { StoredTimeEvidence } from './time-evidence';

function snapshot(bytes: Uint8Array, nodeId: string, incarnation: string) {
  if (bytes.length !== 45 || bytes[0] !== 1) throw new Error('Ungültiger Laufzeit-Snapshot.');
  const uuid = (offset: number) => {
    const h = Array.from(bytes.slice(offset, offset + 16), b => b.toString(16).padStart(2, '0')).join('');
    return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`;
  };
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  const bootCounter = view.getUint32(33, true), uptimeMs = view.getBigUint64(37, true);
  if (uuid(1) !== nodeId || uuid(17) !== incarnation || !bootCounter) throw new Error('Snapshot gehört zu einem anderen Napf.');
  return { bootCounter, uptimeMs };
}

// The caller has authenticated both the session and Node. A second read detects
// reboot/timer discontinuity during collection. No time is sent to the Node.
export async function collectPhoneTime(transport: NodeTransport, nodeId: string, incarnation: string,
  organizationId: string, assertActive: () => void = () => {}): Promise<StoredTimeEvidence> {
  const read = async () => {
    assertActive();
    await transport.write(encodeFrame({ version: 1, opcode: Opcode.ClockSnapshotRequest, payload: new Uint8Array() }));
    const frame = decodeFrame(await transport.read());
    assertActive();
    if (!frame || frame.version !== 1 || frame.opcode !== Opcode.ClockSnapshotResponse) throw new Error('Laufzeit-Snapshot unterbrochen oder abgelehnt.');
    return snapshot(frame.payload, nodeId, incarnation);
  };
  const started = performance.now();
  const first = await read();
  const utcMs = Date.now(); // Phone UTC sampled immediately after Node response.
  const transportDurationMs = Math.ceil(performance.now() - started); // Diagnostic only.
  const second = await read();
  if (first.bootCounter !== second.bootCounter || second.uptimeMs < first.uptimeMs) throw new Error('Napf während Zeitaufnahme neu gestartet. Bitte erneut auslesen.');
  if (!Number.isSafeInteger(utcMs) || utcMs <= 0) throw new Error('Handyzeit nicht darstellbar.');
  const anchorId = crypto.randomUUID();
  return {
    id: `${nodeId}/${incarnation}/${first.bootCounter}/${anchorId}`, organizationId, nodeId, incarnation,
    bootCounter: String(first.bootCounter), kind: 2, uploaded: false,
    payload: JSON.stringify({ formatVersion: 1, source: 'PHONE_ESTIMATE', anchorId, nodeId, incarnation,
      bootCounter: first.bootCounter, uptimeMs: first.uptimeMs.toString(), utcMs, transportDurationMs }),
  };
}
