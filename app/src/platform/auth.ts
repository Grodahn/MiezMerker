// #16: session state for the same-origin PWA. Server-side session + CSRF token.
import { api } from '../api/client';
import type { components } from '../api/generated';
import { advanceAuthenticationEpoch, authenticationEpoch, authenticationEpochKey,
  authorizationStatus, authorizationStatusKey, publishAuthorization, withAuthorizationLock } from './authorization-status';

export type MembershipRole = 'ADMIN' | 'MEMBER';
export type MembershipStatus = 'PENDING' | 'ACTIVE' | 'DISABLED';

export interface SessionUser {
  userId: string;
  email: string;
  // #32: global AppUser.displayName (null for pre-#32 users). UI falls back to email.
  displayName?: string | null;
  memberships: components['schemas']['MembershipView'][];
}

export interface AuthState {
  user: SessionUser | null;
  csrfToken: string | null;
  activeOrganizationId: string | null;
  // #31: distinguishes an online-verified backend session from a restored
  // offline snapshot. `sessionChecked` is true once the initial online check
  // was attempted (or explicitly marked offline). `sessionVerified` is true
  // only after the backend confirmed a valid session (fetchSession/login).
  sessionChecked: boolean;
  sessionVerified: boolean;
  revocationMessage?: string;
}

export const accountDisabledMessage = 'Dein Zugang wurde vom Betreiber deaktiviert.';
const offlineSessionKey = 'miezmerker-offline-session';
function restoreOfflineContext(): AuthState {
  try {
    const saved = JSON.parse(localStorage.getItem(offlineSessionKey) ?? 'null');
    if (saved?.user && typeof saved.user.userId === 'string' && typeof saved.user.email === 'string' &&
        Array.isArray(saved.user.memberships)) {
      try {
        const owners = JSON.parse(localStorage.getItem('miezmerker-legacy-offline-owners') ?? '{}');
        for (const m of saved.user.memberships) {
          if (typeof m?.organizationId === 'string') owners[m.organizationId] ??= saved.user.userId;
        }
        localStorage.setItem('miezmerker-legacy-offline-owners', JSON.stringify(owners));
      } catch { /* Unknown legacy provenance is quarantined, not guessed. */ }
      const status = authorizationStatus(saved.user.userId);
      if (status?.disabled) return { user: null, csrfToken: null, activeOrganizationId: null,
        sessionChecked: true, sessionVerified: false, revocationMessage: accountDisabledMessage };
      const memberships = saved.user.memberships.filter((m: { status?: string; role?: string; organizationId?: string }) =>
        m && (!status || status.organizations.includes(m.organizationId ?? '')) && m.status === 'ACTIVE' && (m.role === 'ADMIN' || m.role === 'MEMBER') && typeof m.organizationId === 'string');
      return { user: { ...saved.user, memberships }, csrfToken: null,
        activeOrganizationId: memberships.some((m: { organizationId: string }) => m.organizationId === saved.activeOrganizationId)
          ? saved.activeOrganizationId : memberships.length === 1 ? memberships[0].organizationId : null,
        sessionChecked: false, sessionVerified: false };
    }
  } catch { /* Offline context is optional; a valid credential is still required for BLE. */ }
  let revocationMessage: string | undefined;
  try { if (localStorage.getItem('miezmerker-revoked-account')) revocationMessage = accountDisabledMessage; }
  catch { /* Storage access itself can be blocked; authentication must still start. */ }
  return { user: null, csrfToken: null, activeOrganizationId: null, sessionChecked: false, sessionVerified: false, revocationMessage };
}
let state: AuthState = restoreOfflineContext();
const listeners = new Set<(state: AuthState) => void>();
const organizationStorageKey = 'miezmerker-active-organization';
let sessionGeneration = 0;
let changingAuthentication = false;
let authenticationQueue: Promise<void> = Promise.resolve();

function changeAuthentication<T>(operation: () => Promise<T>): Promise<T> {
  const pending = authenticationQueue.then(async () => {
    changingAuthentication = true;
    try {
      await withAuthorizationLock(() => {
        sessionGeneration++;
        advanceAuthenticationEpoch();
      });
      return await operation();
    }
    finally { changingAuthentication = false; sessionGeneration++; }
  });
  authenticationQueue = pending.then(() => {}, () => {});
  return pending;
}

function savedOrganization(userId: string): string | null {
  try {
    const saved = JSON.parse(sessionStorage.getItem(organizationStorageKey) ?? 'null');
    return saved?.userId === userId && typeof saved.organizationId === 'string'
      ? saved.organizationId : null;
  } catch { return null; }
}

function persistOrganization() {
  try {
    if (state.user) localStorage.setItem(offlineSessionKey, JSON.stringify({
      user: state.user, activeOrganizationId: state.activeOrganizationId,
    }));
    else localStorage.removeItem(offlineSessionKey);
    if (state.user && state.activeOrganizationId) {
      sessionStorage.setItem(organizationStorageKey, JSON.stringify({
        userId: state.user.userId, organizationId: state.activeOrganizationId,
      }));
    } else sessionStorage.removeItem(organizationStorageKey);
  } catch { /* Storage restrictions must not prevent authentication or offline access. */ }
}

function emit() {
  for (const listener of listeners) listener(state);
}

export function subscribeAuth(listener: (state: AuthState) => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

export function getAuthState(): AuthState {
  return state;
}

function storeUser(user: SessionUser | null) {
  const activeIds = user?.memberships.filter(m => m.status === 'ACTIVE')
    .map(m => m.organizationId).filter((id): id is string => Boolean(id)) ?? [];
  const previous = (state.user?.userId === user?.userId ? state.activeOrganizationId : null)
    ?? (user ? savedOrganization(user.userId) : null);
  state = { ...state, user, activeOrganizationId: previous && activeIds.includes(previous)
    ? previous : activeIds.length === 1 ? activeIds[0] : null };
  persistOrganization();
  emit();
}

// A shared cookie changes accounts before another document receives its
// storage event. Withdraw the previous local grant at the confirmed switch,
// and keep that fence until the original account is confirmed again.
function fenceAccountChange(previousUser: SessionUser | null, nextUserId: string) {
  const replaced = previousUser && previousUser.userId !== nextUserId ? previousUser : null;
  advanceAuthenticationEpoch(replaced?.userId);
  if (!replaced) return null;
  const status = publishAuthorization(replaced.userId, false, [], replaced.memberships.map(m => m.organizationId!));
  return { userId: replaced.userId, revision: status.revision };
}

export function selectOrganization(organizationId: string): void {
  if (!activeOrganizationIds().includes(organizationId)) throw new Error('Organisation nicht verfügbar');
  state = { ...state, activeOrganizationId: organizationId };
  persistOrganization();
  emit();
}

export async function fetchCsrfToken(): Promise<string> {
  const { data, error } = await api.GET('/api/v1/auth/csrf', { signal: AbortSignal.timeout(10_000) });
  if (error || !data || !data.token) throw new Error('CSRF token unavailable');
  state = { ...state, csrfToken: data.token };
  emit();
  return data.token;
}

type CompleteSession = components['schemas']['SessionView'] & {
  userId: string; email: string; memberships: NonNullable<components['schemas']['SessionView']['memberships']>;
};
function completeSession(data: components['schemas']['SessionView'] | undefined): data is CompleteSession {
  return Boolean(data && typeof data.userId === 'string' && data.userId && typeof data.email === 'string' && data.email
    && Array.isArray(data.memberships) && data.memberships.every(m => m && typeof m.organizationId === 'string' && m.organizationId.length > 0
      && ['ACTIVE', 'PENDING', 'DISABLED'].includes(m.status ?? '') && ['ADMIN', 'MEMBER'].includes(m.role ?? ''))
    && new Set(data.memberships.map(m => m.organizationId)).size === data.memberships.length);
}

export async function fetchSession(): Promise<SessionUser | null> {
  const generation = ++sessionGeneration;
  const epoch = authenticationEpoch();
  const previousUser = state.user;
  let outcome: { data?: unknown; error?: unknown; response?: { status?: number; headers?: Headers } };
  try {
    outcome = await api.GET('/api/v1/auth/session', { signal: AbortSignal.timeout(10_000), cache: 'no-store' });
  } catch (failure) {
    // Offline/network failure must not clear a restored snapshot; it only
    // marks the initial check as done so the #31 gate can fall back to
    // offline-sync eligibility instead of flashing protected content.
    if (generation === sessionGeneration && epoch === authenticationEpoch() && !changingAuthentication && !state.sessionChecked) {
      state = { ...state, sessionChecked: true, sessionVerified: false };
      emit();
    }
    throw failure;
  }
  const { data, error, response } = outcome as { data?: components['schemas']['SessionView']; error?: unknown; response?: { status?: number; headers?: Headers } };
  let cleanup = () => Promise.resolve();
  const result = await withAuthorizationLock(() => {
    // Initial restoration and online refreshes can complete after login/logout or each other.
    if (generation !== sessionGeneration || epoch !== authenticationEpoch() || changingAuthentication) return state.user;
    const revokedUserId = response?.headers?.get('X-MM-User-Id');
    if (response?.status === 401 && response.headers?.get('X-MM-Auth-Status') === 'ACCOUNT_DISABLED'
        && revokedUserId && (!previousUser || revokedUserId === previousUser.userId)) {
      const status = publishAuthorization(revokedUserId, true, []);
      advanceAuthenticationEpoch();
      try { localStorage.setItem('miezmerker-revoked-account', revokedUserId); } catch { /* Optional UX marker. */ }
      state = { ...state, csrfToken: null, sessionChecked: true, sessionVerified: false, revocationMessage: accountDisabledMessage };
      storeUser(null); // Cancel BLE/upload subscribers before any IndexedDB wait.
      cleanup = () => reconcileCredentials(revokedUserId, null, status.revision);
      return null;
    }
    if (error || !completeSession(data) || !data) {
      if ((response?.status ?? 0) !== 401) {
        if (!state.sessionChecked) {
          state = { ...state, sessionChecked: true, sessionVerified: false };
          emit();
        }
        throw new Error('Sitzung konnte nicht geladen werden');
      }
      // #31: preserve the restored snapshot so offline-sync eligibility can
      // survive a backend session expiration; only logout clears it.
      state = { ...state, csrfToken: null, sessionChecked: true, sessionVerified: false };
      emit();
      return null;
    }
    const user: SessionUser = {
      userId: data.userId,
      email: data.email,
      displayName: data.displayName ?? null,
      memberships: data.memberships ?? [],
    };
    const removed = previousUser?.userId === user.userId && previousUser.memberships.some(m =>
      m.status === 'ACTIVE' && !user.memberships.some(next => next.organizationId === m.organizationId && next.status === 'ACTIVE'));
    const replaced = fenceAccountChange(previousUser, user.userId);
    const status = publishAuthorization(user.userId, false, user.memberships.filter(m => m.status === 'ACTIVE').map(m => m.organizationId!),
      previousUser?.userId === user.userId ? previousUser.memberships.map(m => m.organizationId!) : []);
    state = { ...state, sessionChecked: true, sessionVerified: true,
      revocationMessage: removed ? 'Du hast keinen Zugriff mehr auf diese Organisation.' : undefined };
    try { localStorage.removeItem('miezmerker-revoked-account'); } catch { /* Optional UX marker. */ }
    storeUser(user);
    const organizations = activeOrganizationIds();
    cleanup = async () => {
      if (replaced) await reconcileCredentials(replaced.userId, null, replaced.revision);
      await reconcileCredentials(user.userId, organizations, status.revision);
    };

    return user;
  });
  await cleanup();
  return result && state.user?.userId !== result.userId ? state.user : result;
}

export function markOfflineChecked(): void {
  if (state.sessionChecked) return;
  state = { ...state, sessionChecked: true, sessionVerified: false };
  emit();
}

export function markSessionPending(): void {
  // A previous verification cannot authorize a reconnect or resumed document.
  sessionGeneration++;
  state = { ...state, csrfToken: null, sessionChecked: false, sessionVerified: false };
  emit();
}

export function markSessionExpired(): void {
  // Central #31 transition for expired/missing backend sessions observed via
  // 401 on management APIs. Preserves the restored snapshot so offline-sync
  // eligibility (#8) can survive; only logout clears it. The gate locks
  // management because sessionVerified is false.
  sessionGeneration++;
  state = { ...state, csrfToken: null, sessionChecked: true, sessionVerified: false };
  emit();
}

export async function login(email: string, password: string): Promise<SessionUser> {
  return changeAuthentication(async () => {
    const epoch = authenticationEpoch();
    const previousUser = state.user;
    const token = await fetchCsrfToken();
    const { data, error } = await api.POST('/api/v1/auth/login', {
      body: { email, password },
      headers: { 'X-XSRF-TOKEN': token },
    });
    if (error || !completeSession(data) || !data) throw new Error('Login fehlgeschlagen');
    const user: SessionUser = {
      userId: data.userId,
      email: data.email,
      displayName: data.displayName ?? null,
      memberships: data.memberships ?? [],
    };
    const replaced = await withAuthorizationLock(() => {
      if (epoch !== authenticationEpoch()) throw new Error('Anmeldung durch neueren Status ersetzt');
      const previous = fenceAccountChange(previousUser, user.userId);
      publishAuthorization(user.userId, false, user.memberships.filter(m => m.status === 'ACTIVE').map(m => m.organizationId!));
      try { localStorage.removeItem('miezmerker-revoked-account'); } catch { /* Optional UX marker. */ }
      // The server rotates the CSRF token after authentication.
      state = { ...state, csrfToken: null, sessionChecked: true, sessionVerified: true, revocationMessage: undefined };
      storeUser(user);
      return previous;
    });
    if (replaced) await reconcileCredentials(replaced.userId, null, replaced.revision);
    await reconcileCredentials(user.userId, activeOrganizationIds());
    return user;
  });
}

export async function logout(): Promise<void> {
  return changeAuthentication(async () => {
    const previousUser = state.user;
    await withAuthorizationLock(() => {
      if (previousUser) {
        publishAuthorization(previousUser.userId, false, [], previousUser.memberships.map(m => m.organizationId!));
        advanceAuthenticationEpoch(previousUser.userId);
      }
      // Hide private content and invalidate offline access before any network wait.
      // A failed server logout must not restore the local protected view.
      state = { ...state, csrfToken: null, sessionChecked: true, sessionVerified: false };
      storeUser(null);
    });
    if (previousUser) await reconcileCredentials(previousUser.userId, null);
    const token = await fetchCsrfToken();
    try {
      const { response } = await api.POST('/api/v1/auth/logout', {
        signal: AbortSignal.timeout(10_000), headers: { 'X-XSRF-TOKEN': token },
      });
      if (!response.ok && response.status !== 401) throw new Error('Abmelden fehlgeschlagen');
    } finally {
      state = { ...state, csrfToken: null };
      emit();
    }
  });
}

export function activeOrganizationIds(): string[] {
  if (!state.user) return [];
  return state.user.memberships
    .filter((m) => m.status === 'ACTIVE')
    .map((m) => m.organizationId as string);
}

async function reconcileCredentials(userId: string, organizations: string[] | null, revision = authorizationStatus(userId)?.revision): Promise<void> {
  const { OfflineIdentity, OfflineIdentityDatabase } = await import('./offline-identity');
  const database = new OfflineIdentityDatabase();
  try {
    const identity = new OfflineIdentity(database);
    if (organizations) await identity.reconcileOrganizations(userId, organizations, revision);
    else await identity.forgetCredentials(userId, revision);
  } catch {
    // Durable localStorage fence already disables access even if IndexedDB is
    // temporarily unavailable. Retry deletion at the next status exchange.
    window.dispatchEvent(new Event('offline-credential-renewal-failed'));
  } finally { database.close(); }
}

if (typeof window !== 'undefined') window.addEventListener('storage', event => {
  if (event.key !== authenticationEpochKey && !event.key?.startsWith(authorizationStatusKey)) return;
  sessionGeneration++; // Delayed requests in this tab cannot override the new fence.
  const user = state.user;
  const status = user ? authorizationStatus(user.userId) : null;
  let clearUserId: string | undefined;
  try { clearUserId = JSON.parse(authenticationEpoch() ?? 'null')?.clearUserId; } catch { /* Inconclusive event. */ }
  state = { ...state, csrfToken: null, sessionChecked: true, sessionVerified: false };
  if (user && status?.disabled) {
    state = { ...state, revocationMessage: accountDisabledMessage };
    // Do not write a stale snapshot or grant back into shared storage.
    state = { ...state, user: null, activeOrganizationId: null };
  } else if (user && clearUserId === user.userId) {
    state = { ...state, user: null, activeOrganizationId: null };
  } else if (user && status) {
    const memberships = user.memberships.filter(m => status.organizations.includes(m.organizationId!));
    const removed = memberships.length !== user.memberships.length;
    state = { ...state, user: { ...user, memberships },
      activeOrganizationId: memberships.some(m => m.organizationId === state.activeOrganizationId)
        ? state.activeOrganizationId : memberships.length === 1 ? memberships[0].organizationId! : null,
      revocationMessage: removed ? 'Du hast keinen Zugriff mehr auf diese Organisation.' : state.revocationMessage };
  }
  emit();
});
