// #31: centralized route/app-shell gate. Decides between login-only,
// offline-sync-only and authenticated app without duplicating the auth
// state model in auth.ts. Defense-in-depth only; backend remains authoritative.
import { useEffect, useState } from 'react';
import {
  fetchSession,
  getAuthState,
  markOfflineChecked,
  subscribeAuth,
  type AuthState,
} from './auth';
import { OfflineIdentity, OfflineIdentityDatabase } from './offline-identity';

export type GateStatus = 'loading' | 'authenticated' | 'offline-sync' | 'login';

export function isCollectorPath(path: string): boolean {
  return path === '/' || path === '/sync';
}

export function resolveGateStatus(
  auth: AuthState,
  online: boolean,
  offlineEligible: boolean | null,
  path: string,
): GateStatus {
  // Neutral loading until the backend session check resolved. Prevents
  // private-content flashing before authorization is established.
  if (!auth.sessionChecked) return 'loading';
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
  if (auth.user && auth.activeOrganizationId && offlineEligible && isCollectorPath(path)) {
    return 'offline-sync';
  }
  return 'login';
}

export async function checkOfflineSyncEligible(
  userId: string | undefined,
  organizationId: string | null | undefined,
): Promise<boolean> {
  if (!userId || !organizationId) return false;
  const database = new OfflineIdentityDatabase();
  try {
    return (await new OfflineIdentity(database).credential(userId, organizationId)) !== null;
  } catch {
    return false;
  } finally {
    try { database.close(); } catch { /* Closed database must not break gating. */ }
  }
}

export function useAppGate(path: string): { status: GateStatus; online: boolean; offlineEligible: boolean | null } {
  const [auth, setAuth] = useState<AuthState>(() => getAuthState());
  const [online, setOnline] = useState<boolean>(() =>
    typeof navigator === 'undefined' ? true : navigator.onLine);
  const [offlineEligible, setOfflineEligible] = useState<boolean | null>(null);

  useEffect(() => subscribeAuth(() => setAuth({ ...getAuthState() })), []);

  useEffect(() => {
    const update = () => {
      setOnline(navigator.onLine);
      setAuth({ ...getAuthState() });
    };
    window.addEventListener('online', update);
    window.addEventListener('offline', update);
    return () => {
      window.removeEventListener('online', update);
      window.removeEventListener('offline', update);
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

  // Offline-credential eligibility for the #8 exception. Only checked when a
  // snapshot could allow sync-only; otherwise immediately ineligible so the
  // gate does not stay in loading.
  useEffect(() => {
    let active = true;
    const needsCheck = Boolean(auth.user && auth.activeOrganizationId
      && !(auth.user && auth.sessionVerified && online));
    if (!needsCheck) {
      setOfflineEligible(false);
      return () => { active = false; };
    }
    setOfflineEligible(null);
    void checkOfflineSyncEligible(auth.user?.userId, auth.activeOrganizationId)
      .then(eligible => { if (active) setOfflineEligible(eligible); })
      .catch(() => { if (active) setOfflineEligible(false); });
    return () => { active = false; };
  }, [auth.user?.userId, auth.activeOrganizationId, auth.sessionVerified, auth.sessionChecked, online]);

  const status = resolveGateStatus(auth, online, offlineEligible, path);
  return { status, online, offlineEligible };
}
