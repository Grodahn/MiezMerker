import { activeOrganizationIds, fetchSession, getAuthState, subscribeAuth } from './auth';
import { OfflineIdentity, OfflineIdentityDatabase } from './offline-identity';

export function startOfflineRenewal(): () => void {
  const database = new OfflineIdentityDatabase();
  const identity = new OfflineIdentity(database);
  let lastUser: string | undefined = getAuthState().user?.userId;
  let active = true;
  let queue = Promise.resolve();
  const renew = (force = true) => {
    const user = getAuthState().user;
    const previousUser = lastUser;
    lastUser = user?.userId;
    const orgs = activeOrganizationIds();
    // Cleanup must not wait behind a slow/hung HTTP request. The shared database
    // generation also prevents an in-flight request in another tab from restoring it.
    const cleanup = previousUser && previousUser !== user?.userId
      ? identity.forgetCredentials(previousUser) : Promise.resolve();
    void cleanup.catch(() => {
      if (active) window.dispatchEvent(new Event('offline-credential-renewal-failed'));
    });
    queue = queue.then(async () => {
      await cleanup;
      if (!active || getAuthState().user !== user) return;
      if (user && navigator.onLine) await identity.renewActive(user.userId, orgs, force);
    }).catch(() => {
      // Retain a still-valid offline credential during a transient online failure.
      if (active) window.dispatchEvent(new Event('offline-credential-renewal-failed'));
    });
  };
  // CSRF notifications also emit auth state; only session/membership changes trigger renewal.
  let sessionSignature = '';
  const unsubscribe = subscribeAuth(() => {
    const signature = JSON.stringify([getAuthState().user?.userId, getAuthState().user?.memberships]);
    if (signature !== sessionSignature) { sessionSignature = signature; renew(); }
  });
  const online = () => { void fetchSession().then(() => renew()).catch(() => {}); };
  window.addEventListener('online', online);
  if (navigator.onLine) void fetchSession().catch(() => {});
  const timer = window.setInterval(() => renew(false), 60000);
  return () => { active = false; unsubscribe(); window.removeEventListener('online', online);
    window.clearInterval(timer); database.close(); };
}
