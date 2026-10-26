import type { NodeTransport } from '../platform/node-transport';
import { ClaimAttempt, type ClaimPhase } from '../diagnostics/claim-attempt';
import { decodeFrame, decodeErrorPayload, Opcode } from './ble-codec';
import { DiagnosticError, errorCodes, firmwareErrorCode } from '../diagnostics/errors';

const phases = new Map<number, ClaimPhase>([
  [Opcode.HelloRequest, 'hello'], [Opcode.AlphaPhoneClockRequest, 'alpha_clock'],
  [Opcode.RegistrationTimeChallengeRequest, 'time_challenge'], [Opcode.RegistrationTimeProofRequest, 'time_proof'],
  [Opcode.ClaimAdvertisementRequest, 'claim_advert'], [Opcode.ClaimReceiptRequest, 'receipt'],
]);
// Observes existing exchanges; adds no reads, writes, retries or timing changes.
const observed = new WeakMap<NodeTransport, ClaimAttempt>();
export function diagnosticTransport(transport: NodeTransport, attempt: ClaimAttempt): NodeTransport {
  if (observed.get(transport) === attempt) return transport;
  let expected: number | undefined;
  let phase: ClaimPhase | undefined;
  const wrapper: NodeTransport = {
    async connect() { attempt.event('connection', 'started'); await transport.connect(); attempt.event('connection', 'ok'); },
    disconnect: () => transport.disconnect(),
    async write(bytes) {
      const frame = decodeFrame(bytes);
      phase = frame ? phases.get(frame.opcode) : undefined;
      expected = frame ? frame.opcode + 1 : undefined;
      if (phase) attempt.event(phase, 'started');
      await transport.write(bytes);
    },
    async read() {
      const bytes = await transport.read();
      if (phase) {
        const frame = decodeFrame(bytes);
        if (frame?.opcode === Opcode.Error) {
          const error = decodeErrorPayload(frame.payload);
          attempt.event(phase, 'rejected', error ? firmwareErrorCode(error.code) ?? errorCodes.BLE_PROTOCOL : errorCodes.BLE_PROTOCOL);
        } else if (!frame || frame.version !== 1 || frame.opcode !== expected) attempt.event(phase, 'failed', errorCodes.BLE_PROTOCOL);
        else if ((phase === 'receipt' || phase === 'alpha_clock' || phase === 'time_proof') && (frame.payload.length !== 1 || frame.payload[0] !== 0)) {
          attempt.event(phase, 'rejected', phase === 'receipt' ? errorCodes.CLAIM_UNCERTAIN : errorCodes.CLOCK_CONFIRMATION);
        } else attempt.event(phase, 'ok');
      }
      return bytes;
    },
  };
  observed.set(wrapper, attempt);
  return wrapper;
}
export function advertisementError(frame: ReturnType<typeof decodeFrame>): DiagnosticError {
  const error = frame?.opcode === Opcode.Error ? decodeErrorPayload(frame.payload) : null;
  const code = error ? firmwareErrorCode(error.code) ?? errorCodes.BLE_PROTOCOL
    : frame?.opcode === Opcode.ClaimAdvertisementResponse ? errorCodes.CLAIM_IDENTITY : errorCodes.BLE_PROTOCOL;
  return new DiagnosticError(code, error?.code === 9
    ? 'Napf kann keinen Claim-Nachweis liefern: Sicherheitszeit fehlt, ist veraltet oder zu unsicher. Zeit-Policy prüfen.'
    : 'Claim-Nachweis vom Napf abgelehnt oder ungültig.');
}
