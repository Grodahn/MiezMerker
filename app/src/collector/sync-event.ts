// Platform-independent collector event contract. No BLE, browser or person/location payloads.
export type SyncTimeQuality = 'PHONE_ESTIMATE' | 'OFFLINE_ESTIMATE' | 'UNKNOWN';
export interface SyncEvent {
  eventId: string;
  organizationId: string;
  ownerUserId: string;
  nodeId: string;
  incarnation: string;
  occurredAt: number; // untrusted collector UTC, never proof of attendance
  timeQuality: SyncTimeQuality;
}
export interface StoredSyncEvent extends SyncEvent { expiresAt: number }
export const SYNC_EVENT_RETENTION_MS = 30 * 86400_000;
// Detect wall-clock jumps within this visit; initial phone UTC remains untrusted.
export function syncTimeQuality(startUtc: number, endUtc: number, elapsedMs: number, online = true): SyncTimeQuality {
  return Number.isSafeInteger(endUtc) && endUtc > 0 && elapsedMs >= 0 &&
    Math.abs(endUtc - startUtc - elapsedMs) <= 2000 ? (online ? 'PHONE_ESTIMATE' : 'OFFLINE_ESTIMATE') : 'UNKNOWN';
}
