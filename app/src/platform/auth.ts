// #16: session state for the same-origin PWA. Server-side session + CSRF token.
import { api } from '../api/client';
import type { components } from '../api/generated';

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
}

const offlineSessionKey = 'miezmerker-offline-session';
function restoreOfflineContext(): AuthState {
  try {
    const saved = JSON.parse(localStorage.getItem(offlineSessionKey) ?? 'null');
    if (saved?.user && typeof saved.user.userId === 'string' && typeof saved.user.email === 'string' &&
        Array.isArray(saved.user.memberships)) {
      const memberships = saved.user.memberships.filter((m: { status?: string; role?: string; organizationId?: string }) =>
        m && m.status === 'ACTIVE' && (m.role === 'ADMIN' || m.role === 'MEMBER') && typeof m.organizationId === 'string');
      return { user: { ...saved.user, memberships }, csrfToken: null,
        activeOrganizationId: memberships.some((m: { organizationId: string }) => m.organizationId === saved.activeOrganizationId)
          ? saved.activeOrganizationId : memberships.length === 1 ? memberships[0].organizationId : null,
        sessionChecked: false, sessionVerified: false };
    }
  } catch { /* Offline context is optional; a valid credential is still required for BLE. */ }
  return { user: null, csrfToken: null, activeOrganizationId: null, sessionChecked: false, sessionVerified: false };
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
    sessionGeneration++;
    try { return await operation(); }
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

export async function fetchSession(): Promise<SessionUser | null> {
  const generation = ++sessionGeneration;
  let outcome: { data?: unknown; error?: unknown; response?: { status?: number } };
  try {
    outcome = await api.GET('/api/v1/auth/session', { signal: AbortSignal.timeout(10_000), cache: 'no-store' });
  } catch (failure) {
    // Offline/network failure must not clear a restored snapshot; it only
    // marks the initial check as done so the #31 gate can fall back to
    // offline-sync eligibility instead of flashing protected content.
    if (generation === sessionGeneration && !changingAuthentication && !state.sessionChecked) {
      state = { ...state, sessionChecked: true, sessionVerified: false };
      emit();
    }
    throw failure;
  }
  const { data, error, response } = outcome as { data?: components['schemas']['SessionView']; error?: unknown; response?: { status?: number } };
  // Initial restoration and online refreshes can complete after login/logout or each other.
  if (generation !== sessionGeneration || changingAuthentication) return state.user;
  if (error || !data || !data.userId || !data.email) {
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
  state = { ...state, sessionChecked: true, sessionVerified: true };
  storeUser(user);
  return user;
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
    const token = await fetchCsrfToken();
    const { data, error } = await api.POST('/api/v1/auth/login', {
      body: { email, password },
      headers: { 'X-XSRF-TOKEN': token },
    });
    if (error || !data || !data.userId || !data.email) throw new Error('Login fehlgeschlagen');
    const user: SessionUser = {
      userId: data.userId,
      email: data.email,
      displayName: data.displayName ?? null,
      memberships: data.memberships ?? [],
    };
    // The server rotates the CSRF token after authentication.
    state = { ...state, csrfToken: null, sessionChecked: true, sessionVerified: true };
    storeUser(user);
    return user;
  });
}

export async function logout(): Promise<void> {
  return changeAuthentication(async () => {
    // Hide private content and invalidate offline access before any network wait.
    // A failed server logout must not restore the local protected view.
    state = { ...state, csrfToken: null, sessionChecked: true, sessionVerified: false };
    storeUser(null);
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
