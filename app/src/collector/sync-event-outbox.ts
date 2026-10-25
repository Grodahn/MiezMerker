import { DiagnosticError, errorCodes } from '../diagnostics/errors';
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
      let rejected = false;
      for (const row of rows) {
        if (!active()) break;
        const csrf = await fetchCsrfToken();
        if (!active()) break;
        // The snapshot may outlive retention while earlier rows/CSRF wait for HTTP.
        // Never send an expired event just because it was eligible when the drain began.
        if (row.expiresAt <= Date.now()) {
          await this.db.syncEvents.delete(row.eventId);
          continue;
        }
        const { expiresAt: _localExpiry, ...body } = row;
        const { data, error, response } = await api.POST('/api/v1/collector-sync-events', {
          body, headers: { 'X-XSRF-TOKEN': csrf },
          signal: AbortSignal.any([controller.signal, AbortSignal.timeout(10_000)]),
        });
        if (!active()) break;
        if (response.status === 401) {
          markSessionExpired();
          throw new DiagnosticError(errorCodes.UPLOAD_EVENT, 'Sitzung abgelaufen. Ausleseereignisse bleiben für die erneute Anmeldung gespeichert.');
        }
        if (response.status >= 400 && response.status < 500 && response.status !== 429) {
          // One moved/deleted Node, expired report or identity conflict must not
          // strand valid siblings. Keep the rejected original bound until expiry.
          rejected = true;
          continue;
        }
        if (error || !data || data.eventId !== row.eventId ||
            !['CREATED', 'DUPLICATE_IDENTICAL'].includes(data.status ?? '')) {
          throw new DiagnosticError(errorCodes.UPLOAD_EVENT, 'Ausleseereignis noch nicht bestätigt; bleibt bis zum Ablauf identitätsgebunden gespeichert.');
        }
        await this.db.syncEvents.delete(row.eventId);
        uploaded++;
      }
      if (rejected) throw new DiagnosticError(errorCodes.UPLOAD_EVENT, 'Ausleseereignis noch nicht bestätigt; gültige weitere Ereignisse wurden separat übertragen.');
      return uploaded;
    } finally { unsubscribe(); }
  }
}
