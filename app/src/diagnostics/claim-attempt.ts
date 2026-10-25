import { DiagnosticError, errorCodes, isErrorCode, type ErrorCode } from './errors';

export const claimPhases = ['discovered', 'connection', 'hello', 'policy', 'alpha_clock',
  'time_challenge', 'time_proof', 'claim_advert', 'local_signature', 'backend_claim',
  'receipt_persisted', 'receipt', 'node_claimed', 'preflight', 'complete'] as const;
export type ClaimPhase = typeof claimPhases[number];
export type ClaimStatus = 'started' | 'ok' | 'rejected' | 'failed' | 'cancelled' | 'timeout';
export interface ClaimEvent {
  sequence: number; phase: ClaimPhase; status: ClaimStatus; code: ErrorCode | 'OK';
  durationMs: number; elapsedMs: number;
  policy?: 'FIELD_ALPHA_PHONE_CLOCK' | 'SIGNED_BACKEND_TIME'; caps?: number; protocolVersion?: number;
}
// Portable bounded recorder. No transport, identity, wall clock, free text or proof material.
export class ClaimAttempt {
  readonly attemptId = crypto.randomUUID();
  readonly events: ClaimEvent[] = [];
  private sequence = 0;
  private readonly started = performance.now();
  private readonly starts = new Map<ClaimPhase, number>();
  metadata: Pick<ClaimEvent, 'policy' | 'caps' | 'protocolVersion'> = {};
  phase: ClaimPhase = 'discovered';
  event(phase: ClaimPhase, status: ClaimStatus, code: ErrorCode | 'OK' = 'OK'): void {
    const now = performance.now();
    this.phase = phase;
    if (status === 'started') this.starts.set(phase, now);
    this.events.push({ policy: this.metadata.policy, caps: this.metadata.caps, protocolVersion: this.metadata.protocolVersion, sequence: ++this.sequence, phase, status,
      code: code === 'OK' || isErrorCode(code) ? code : errorCodes.UI_UNKNOWN,
      durationMs: Math.min(3600000, Math.max(0, Math.round(now - (this.starts.get(phase) ?? this.started)))),
      elapsedMs: Math.min(3600000, Math.max(0, Math.round(now - this.started))) });
    if (this.events.length > 64) this.events.shift();
  }
  failure(error: unknown): void {
    const name = error && typeof error === 'object' && 'name' in error ? error.name : '';
    const status = name === 'TimeoutError' || (error instanceof DiagnosticError && error.code === errorCodes.BLE_TIMEOUT) ? 'timeout' : name === 'AbortError' || name === 'NodeOperationCancelledError' ? 'cancelled' : 'failed';
    this.event(this.phase, status, error instanceof DiagnosticError ? error.code
      : status === 'timeout' ? errorCodes.BLE_TIMEOUT : status === 'cancelled' ? errorCodes.AUTH_CHANGED : errorCodes.BLE_CONNECTION);
  }
  export(): string { return JSON.stringify({ version: 1, attemptId: this.attemptId, delivery: 'not-confirmed', events: this.events }); }
}
