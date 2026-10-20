import { api } from '../api/client';
import { fetchCsrfToken, getAuthState, markSessionExpired, subscribeAuth } from '../platform/auth';
import { authorizationPermits, authorizationStatus, authenticationEpoch } from '../platform/authorization-status';
import type { CollectorDatabase } from '../platform/offline-store';
import { SYNC_EVENT_RETENTION_MS, type SyncEvent } from './sync-event';

export class SyncEventOutbox {
  constructor(private readonly db: CollectorDatabase) {}

  async record(event: SyncEvent, assertActive: () => void): Promise<void> {
    await this.db.transaction('rw', this.db.syncEvents, async () => {
      assertActive();
      await this.db.syncEvents.add({ ...event, expiresAt: Date.now() + SYNC_EVENT_RETENTION_MS });
      assertActive(); // rollback on cancellation during IndexedDB persistence
    });
  }

  async purge(): Promise<void> {
    await this.db.syncEvents.toCollection().filter(row => row.expiresAt <= Date.now() ||
      authorizationStatus(row.ownerUserId)?.disabled === true).delete();
  }

  // Independent queue: an observation/evidence failure must not prevent this upload.
  // Expected owner is also checked by the server against the cookie principal;
  // aborting alone cannot close a cookie/account switch race in another tab.
  async upload(organizationId: string): Promise<number> {
    await this.purge();
    const initial = getAuthState(), userId = initial.user?.userId, epoch = authenticationEpoch();
    let cancelled = false;
    const active = () => !cancelled && authenticationEpoch() === epoch && Boolean(userId) &&
      getAuthState().user?.userId === userId && getAuthState().activeOrganizationId === organizationId &&
      authorizationPermits(userId!, organizationId) && getAuthState().user?.memberships.some(m =>
        m.organizationId === organizationId && m.status === 'ACTIVE');
    if (!active() || !navigator.onLine) return 0;
    const controller = new AbortController();
    const unsubscribe = subscribeAuth(() => { if (!active()) { cancelled = true; controller.abort(); } });
    try {
      const rows = await this.db.syncEvents.where('[ownerUserId+organizationId]').equals([userId!, organizationId]).toArray();
      let uploaded = 0;
      for (const row of rows) {
        if (!active()) break;
        const csrf = await fetchCsrfToken();
        if (!active()) break;
        const { expiresAt: _localExpiry, ...body } = row;
        const { data, error, response } = await api.POST('/api/v1/collector-sync-events', {
          body, headers: { 'X-XSRF-TOKEN': csrf },
          signal: AbortSignal.any([controller.signal, AbortSignal.timeout(10_000)]),
        });
        if (!active()) break;
        if (response.status === 401) { markSessionExpired(); break; }
        if (error || !data || data.eventId !== row.eventId ||
            !['CREATED', 'DUPLICATE_IDENTICAL'].includes(data.status ?? '')) {
          throw new Error('Ausleseereignis noch nicht bestätigt; bleibt bis zum Ablauf identitätsgebunden gespeichert.');
        }
        await this.db.syncEvents.delete(row.eventId);
        uploaded++;
      }
      return uploaded;
    } finally { unsubscribe(); }
  }
}
