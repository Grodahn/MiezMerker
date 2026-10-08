// #31: centralized route/app-shell gate. Decides between login-only,
// offline-sync-only and authenticated app without duplicating the auth
// state model in auth.ts. Defense-in-depth only; backend remains authoritative.
import { useEffect, useState } from 'react';
import { flushSync } from 'react-dom';
import { liveQuery } from 'dexie';
import {
  fetchSession,
  getAuthState,
  markOfflineChecked,
  markSessionExpired,
  markSessionPending,
  subscribeAuth,
  type AuthState,
} from './auth';
import { OfflineIdentity, OfflineIdentityDatabase } from './offline-identity';

export type GateStatus = 'loading' | 'authenticated' | 'offline-sync' | 'login';

export function isCollectorPath(path: string): boolean {
  return path === '/sync';
}

export function resolveGateStatus(
  auth: AuthState,
  online: boolean,
  offlineEligible: boolean | null,
  path: string,
): GateStatus {
  // Neutral loading until the backend session check resolved. Prevents
  // private-content flashing before authorization is established.
  const activeMembership = auth.user?.memberships.some(m => m.organizationId === auth.activeOrganizationId
    && m.status === 'ACTIVE' && (m.role === 'ADMIN' || m.role === 'MEMBER'));
  const offlineAuthorized = Boolean(activeMembership && offlineEligible && isCollectorPath(path));
  if (!auth.sessionChecked) return offlineAuthorized ? 'offline-sync' : 'loading';
  if (auth.user && auth.sessionVerified && online) return 'authenticated';
  // Offline field-sync exception (#8): previously established AppDevice
  // identity + ACTIVE membership/org context + still-valid offline credential
  // keeps only /sync usable. This is not anonymous access; management routes
  // stay locked and no stale management data is shown.
  if (offlineEligible === null) {
    // Credential lookup still pending for a snapshot that could allow sync.
    // Stay neutral instead of flashing login or protected content.
    if (auth.user && auth.activeOrganizationId && isCollectorPath(path)) return 'loading';
    return 'login';
  }
  if (offlineAuthorized) {
    return 'offline-sync';
  }
  return 'login';
}

export async function offlineSyncExpiresAt(
  userId: string | undefined,
  organizationId: string | null | undefined,
): Promise<number | null> {
  if (!userId || !organizationId) return null;
  const database = new OfflineIdentityDatabase();
  try {
    if (!await new OfflineIdentity(database).credential(userId, organizationId)) return null;
    return (await database.credentials.get([userId, organizationId]))?.expiresAt ?? null;
  } catch {
    return null;
  } finally {
    try { database.close(); } catch { /* Closed database must not break gating. */ }
  }
}

export function useAppGate(path: string): { status: GateStatus; online: boolean; offlineEligible: boolean | null; offlineSyncAvailable: boolean } {
  const [auth, setAuth] = useState<AuthState>(() => getAuthState());
  const [online, setOnline] = useState<boolean>(() =>
    typeof navigator === 'undefined' ? true : navigator.onLine);
  const [offlineCredential, setOfflineCredential] = useState<{ context: string; expiresAt: number | null } | null>(null);
  const [departed, setDeparted] = useState(false);
  const [clockVersion, tick] = useState(0);
  const credentialContext = JSON.stringify([auth.user?.userId, auth.activeOrganizationId]);
  // Look up credentials while online too, so a connectivity change can keep an
  // already-authorized BLE sync mounted without waiting for another IDB read.
  const needsCredential = Boolean(auth.user && auth.activeOrganizationId);
  const offlineEligible = !needsCredential ? false
    : offlineCredential?.context !== credentialContext ? null
      : offlineCredential.expiresAt !== null && Date.now() < offlineCredential.expiresAt;

  useEffect(() => subscribeAuth(() => setAuth({ ...getAuthState() })), []);

  useEffect(() => {
    const update = () => {
      // Invalidate the old verification before React can remount management.
      if (navigator.onLine) markSessionPending();
      else markSessionExpired();
      setOnline(navigator.onLine);
      setAuth({ ...getAuthState() });
      if (navigator.onLine) void fetchSession().catch(() => {});
    };
    // Clear all private content (including account/collector UI) before bfcache.
    const hide = () => flushSync(() => setDeparted(true));
    const show = (event: PageTransitionEvent) => { if (event.persisted) window.location.reload(); };
    window.addEventListener('online', update);
    window.addEventListener('offline', update);
    window.addEventListener('pagehide', hide);
    window.addEventListener('pageshow', show);
    return () => {
      window.removeEventListener('online', update);
      window.removeEventListener('offline', update);
      window.removeEventListener('pagehide', hide);
      window.removeEventListener('pageshow', show);
    };
  }, []);

  // Initial load: determine session/auth state first. Show neutral loading
  // until then; never render navigation or protected contents beforehand.
  useEffect(() => {
    let active = true;
    const current = getAuthState();
    if (!current.sessionChecked) {
      if (typeof navigator !== 'undefined' && !navigator.onLine) {
        markOfflineChecked();
        if (active) setAuth({ ...getAuthState() });
      } else {
        void fetchSession()
          .catch(() => {})
          .finally(() => { if (active) setAuth({ ...getAuthState() }); });
      }
    }
    return () => { active = false; };
  }, []);

  // Bind eligibility to the exact account/org and observe credential renewal
  // or removal in other tabs. A timer closes the shell at credential expiry.
  useEffect(() => {
    if (!needsCredential) { setOfflineCredential(null); return; }
    const subscription = liveQuery(() => offlineSyncExpiresAt(auth.user?.userId, auth.activeOrganizationId))
      .subscribe({
        next: expiresAt => setOfflineCredential({ context: credentialContext, expiresAt }),
        error: () => setOfflineCredential({ context: credentialContext, expiresAt: null }),
      });
    return () => subscription.unsubscribe();
  }, [auth.user?.userId, auth.activeOrganizationId, needsCredential, credentialContext]);

  useEffect(() => {
    const expiresAt = offlineCredential?.expiresAt;
    if (!expiresAt || !offlineEligible) return;
    const timer = window.setTimeout(() => tick(value => value + 1), Math.min(expiresAt - Date.now(), 2_147_483_646) + 1);
    return () => window.clearTimeout(timer);
  }, [offlineCredential, offlineEligible, clockVersion]);

  const status = departed ? 'loading' : resolveGateStatus(auth, online, offlineEligible, path);
  // Also authorize the installed PWA's offline entry via this same gate.
  // This grants no access to Home: App may only replace its URL with /sync.
  const offlineSyncAvailable = !departed && resolveGateStatus(auth, online, offlineEligible, '/sync') === 'offline-sync';
  return { status, online, offlineEligible, offlineSyncAvailable };
}
