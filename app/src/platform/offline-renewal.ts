import { activeOrganizationIds, fetchSession, getAuthState, subscribeAuth } from './auth';
import { OfflineIdentity, OfflineIdentityDatabase } from './offline-identity';

export function startOfflineRenewal(): () => void {
  const database = new OfflineIdentityDatabase();
  const identity = new OfflineIdentity(database);
  let lastUser: string | undefined;
  let active = true;
  let queue = Promise.resolve();
  const renew = (force = true) => {
    const user = getAuthState().user;
    const previousUser = lastUser;
    lastUser = user?.userId;
    const orgs = activeOrganizationIds();
    queue = queue.then(async () => {
      if (!active) return;
      if (previousUser && previousUser !== user?.userId) await identity.forgetCredentials(previousUser);
      if (user && navigator.onLine) await identity.renewActive(user.userId, orgs, force);
    }).catch(() => {
      // Retain a still-valid offline credential during a transient online failure.
      window.dispatchEvent(new Event('offline-credential-renewal-failed'));
    });
  };
  // CSRF notifications also emit auth state; only session/membership changes trigger renewal.
  let sessionSignature = '';
  const unsubscribe = subscribeAuth(() => {
    const signature = JSON.stringify([getAuthState().user?.userId, activeOrganizationIds()]);
    if (signature !== sessionSignature) { sessionSignature = signature; renew(); }
  });
  const online = () => { void fetchSession().then(() => renew()).catch(() => {}); };
  window.addEventListener('online', online);
  if (navigator.onLine) void fetchSession().catch(() => {});
  const timer = window.setInterval(() => renew(false), 60000);
  return () => { active = false; unsubscribe(); window.removeEventListener('online', online);
    window.clearInterval(timer); database.close(); };
}
