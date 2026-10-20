import { CollectorDatabase } from './offline-store';
import { subscribeAuth } from './auth';
import { SyncEventOutbox } from '../collector/sync-event-outbox';

// Runs even on the login screen, offline, or after a confirmed account ban.
// A closed PWA cannot execute cleanup; expired rows are never uploaded on reopen.
export function startSyncEventRetention(): () => void {
  const purge = () => {
    const db = new CollectorDatabase();
    void new SyncEventOutbox(db).purge().catch(() => { /* Retry on next lifecycle event. */ }).finally(() => db.close());
  };
  purge();
  const unsubscribe = subscribeAuth(purge);
  window.addEventListener('storage', purge);
  const timer = window.setInterval(purge, 3600_000);
  return () => { unsubscribe(); window.removeEventListener('storage', purge); window.clearInterval(timer); };
}
