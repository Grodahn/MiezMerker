import { errorCodes, type ErrorCode } from './error-codes.generated';
export { errorCodes, type ErrorCode } from './error-codes.generated';

const knownCodes = new Set<string>(Object.values(errorCodes));
export function isErrorCode(value: unknown): value is ErrorCode {
  return typeof value === 'string' && knownCodes.has(value);
}

// Only catalog codes are carried across legacy string-only state boundaries.
// This reads an explicit marker, never infers a code from translated prose.
export function messageCode(message: string): ErrorCode | undefined {
  const code = /\[(MM-[A-Z]+-\d{3})\]/.exec(message)?.[1];
  return isErrorCode(code) ? code : undefined;
}
export function withCode(message: string, code: ErrorCode): string {
  return messageCode(message) ? message : `${message} [${code}]`;
}
export class DiagnosticError extends Error {
  constructor(readonly code: ErrorCode, message: string) {
    super(withCode(message, code));
    this.name = 'DiagnosticError';
  }
}

// Safe fallback at unknown external exception boundaries. Raw browser/server
// errors can contain URLs, payloads, IDs or stacks; never echo them here.
export function safeFailure(error: unknown, fallback: string, code: ErrorCode = errorCodes.UI_UNKNOWN): string {
  if (error instanceof DiagnosticError) return error.message;
  return withCode(fallback, code);
}

export const claimFailureCodes = {
  'forbidden-role': errorCodes.AUTH_DENIED, 'trusted-time': errorCodes.CLOCK_POLICY,
  'conflict-foreign': errorCodes.CLAIM_CONFLICT, backend: errorCodes.CLAIM_BACKEND,
  offline: errorCodes.CLAIM_OFFLINE, uncertain: errorCodes.CLAIM_UNCERTAIN,
} as const;
export const syncFailureCodes = {
  unclaimed: errorCodes.SYNC_UNCLAIMED, foreign: errorCodes.AUTH_DENIED,
  incompatible: errorCodes.BLE_VERSION, unauthorized: errorCodes.AUTH_CREDENTIAL,
  storage: errorCodes.STORAGE_WRITE, connection: errorCodes.BLE_CONNECTION,
  protocol: errorCodes.BLE_PROTOCOL, incomplete: errorCodes.SYNC_GAP,
  cancelled: errorCodes.AUTH_CHANGED, 'trusted-time': errorCodes.CLOCK_POLICY,
} as const;

// SyncError values from the existing v1 wire contract. Zero is success.
// Security denials stay neutral; no resource existence information is added.
const firmwareCodes: Record<number, ErrorCode> = {
  1: errorCodes.BLE_VERSION, 2: errorCodes.AUTH_DENIED, 3: errorCodes.AUTH_DENIED,
  4: errorCodes.BLE_PROTOCOL, 5: errorCodes.FW_SEQUENCE, 6: errorCodes.FW_RECORD,
  7: errorCodes.AUTH_DENIED, 8: errorCodes.SYNC_NODE_FULL, 9: errorCodes.FW_CLOCK,
  10: errorCodes.FW_EXPIRED, 11: errorCodes.FW_REPLAY, 12: errorCodes.FW_CREDENTIAL,
  13: errorCodes.FW_PROOF, 14: errorCodes.FW_STATE, 15: errorCodes.FW_INTERNAL,
};
export function firmwareErrorCode(value: number): ErrorCode | undefined {
  return value === 0 ? undefined : firmwareCodes[value] ?? errorCodes.BLE_PROTOCOL;
}

export function httpErrorCode(status: number): ErrorCode {
  const codes: Record<number, ErrorCode> = {
    400: errorCodes.HTTP_BAD_REQUEST, 401: errorCodes.HTTP_UNAUTHORIZED,
    403: errorCodes.HTTP_FORBIDDEN, 404: errorCodes.HTTP_NOT_FOUND,
    405: errorCodes.HTTP_METHOD, 409: errorCodes.HTTP_CONFLICT,
    413: errorCodes.HTTP_TOO_LARGE, 415: errorCodes.HTTP_MEDIA,
    429: errorCodes.HTTP_RATE, 503: errorCodes.HTTP_UNAVAILABLE,
  };
  return codes[status] ?? errorCodes.HTTP_UNKNOWN;
}

export function apiErrorCode(status: number, body: unknown): ErrorCode {
  const code = body && typeof body === 'object' && 'code' in body ? body.code : undefined;
  return isErrorCode(code) ? code : httpErrorCode(status);
}
