// #16: session state for the same-origin PWA. Server-side session + CSRF token.
import { api } from '../api/client';
import type { components } from '../api/generated';

export type MembershipRole = 'ADMIN' | 'MEMBER';
export type MembershipStatus = 'PENDING' | 'ACTIVE' | 'DISABLED';

export interface SessionUser {
  userId: string;
  email: string;
  memberships: components['schemas']['MembershipView'][];
}

export interface AuthState {
  user: SessionUser | null;
  csrfToken: string | null;
  activeOrganizationId: string | null;
}

let state: AuthState = { user: null, csrfToken: null, activeOrganizationId: null };
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
  const { data, error } = await api.GET('/api/v1/auth/csrf');
  if (error || !data || !data.token) throw new Error('CSRF token unavailable');
  state = { ...state, csrfToken: data.token };
  emit();
  return data.token;
}

export async function fetchSession(): Promise<SessionUser | null> {
  const generation = ++sessionGeneration;
  const { data, error, response } = await api.GET('/api/v1/auth/session');
  // Initial restoration and online refreshes can complete after login/logout or each other.
  if (generation !== sessionGeneration || changingAuthentication) return state.user;
  if (error || !data || !data.userId || !data.email) {
    if (response?.status !== 401) throw new Error('Sitzung konnte nicht geladen werden');
    state = { ...state, csrfToken: null };
    storeUser(null);
    return null;
  }
  const user: SessionUser = {
    userId: data.userId,
    email: data.email,
    memberships: data.memberships ?? [],
  };
  storeUser(user);
  return user;
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
      memberships: data.memberships ?? [],
    };
    // The server rotates the CSRF token after authentication.
    state = { ...state, csrfToken: null };
    storeUser(user);
    return user;
  });
}

export async function logout(): Promise<void> {
  return changeAuthentication(async () => {
    const token = await fetchCsrfToken();
    const { response } = await api.POST('/api/v1/auth/logout', { headers: { 'X-XSRF-TOKEN': token } });
    if (!response.ok && response.status !== 401) throw new Error('Abmelden fehlgeschlagen');
    state = { ...state, csrfToken: null };
    storeUser(null);
  });
}

export function activeOrganizationIds(): string[] {
  if (!state.user) return [];
  return state.user.memberships
    .filter((m) => m.status === 'ACTIVE')
    .map((m) => m.organizationId as string);
}
