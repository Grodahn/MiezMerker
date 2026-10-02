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
}

let state: AuthState = { user: null, csrfToken: null };
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

export async function fetchCsrfToken(): Promise<string> {
  const { data, error } = await api.GET('/api/v1/auth/csrf');
  if (error || !data || !data.token) throw new Error('CSRF token unavailable');
  state = { ...state, csrfToken: data.token };
  emit();
  return data.token;
}

export async function fetchSession(): Promise<SessionUser | null> {
  const { data, error } = await api.GET('/api/v1/auth/session');
  if (error || !data || !data.userId || !data.email) {
    state = { user: null, csrfToken: state.csrfToken };
    emit();
    return null;
  }
  const user: SessionUser = {
    userId: data.userId,
    email: data.email,
    memberships: data.memberships ?? [],
  };
  state = { ...state, user };
  emit();
  return user;
}

export async function login(email: string, password: string): Promise<SessionUser> {
  const token = state.csrfToken ?? (await fetchCsrfToken());
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
  state = { ...state, user };
  emit();
  return user;
}

export async function logout(): Promise<void> {
  const token = state.csrfToken ?? (await fetchCsrfToken());
  await api.POST('/api/v1/auth/logout', { headers: { 'X-XSRF-TOKEN': token } });
  state = { user: null, csrfToken: null };
  emit();
}

export function activeOrganizationIds(): string[] {
  if (!state.user) return [];
  return state.user.memberships
    .filter((m) => m.status === 'ACTIVE')
    .map((m) => m.organizationId as string);
}
