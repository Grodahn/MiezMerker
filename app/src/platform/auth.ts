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
  const previous = state.user?.userId === user?.userId ? state.activeOrganizationId : null;
  state = { ...state, user, activeOrganizationId: previous && activeIds.includes(previous)
    ? previous : activeIds.length === 1 ? activeIds[0] : null };
  emit();
}

export function selectOrganization(organizationId: string): void {
  if (!activeOrganizationIds().includes(organizationId)) throw new Error('Organisation nicht verfügbar');
  state = { ...state, activeOrganizationId: organizationId };
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
  const { data, error, response } = await api.GET('/api/v1/auth/session');
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
}

export async function logout(): Promise<void> {
  const token = await fetchCsrfToken();
  const { response } = await api.POST('/api/v1/auth/logout', { headers: { 'X-XSRF-TOKEN': token } });
  if (!response.ok && response.status !== 401) throw new Error('Abmelden fehlgeschlagen');
  state = { user: null, csrfToken: null, activeOrganizationId: null };
  emit();
}

export function activeOrganizationIds(): string[] {
  if (!state.user) return [];
  return state.user.memberships
    .filter((m) => m.status === 'ACTIVE')
    .map((m) => m.organizationId as string);
}
