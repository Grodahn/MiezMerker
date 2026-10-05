import { useSyncExternalStore } from 'react';
import { getAuthState, subscribeAuth } from '../platform/auth';

function identity() {
  const auth = getAuthState();
  const membership = auth.user?.memberships.find(m =>
    m.organizationId === auth.activeOrganizationId && m.status === 'ACTIVE');
  return { userId: auth.user?.userId, organizationId: membership?.organizationId,
    admin: membership?.role === 'ADMIN', role: membership?.role };
}
let current = identity();
let snapshot = { ...current, generation: 0 };
const listeners = new Set<() => void>();
// Invalidate even an A -> B -> A switch that React batches into one render.
subscribeAuth(() => {
  const next = identity();
  if (next.userId === current.userId && next.organizationId === current.organizationId &&
      next.role === current.role) return;
  current = next;
  snapshot = { ...next, generation: snapshot.generation + 1 };
  for (const listener of listeners) listener();
});
export function useManagementContext() {
  return useSyncExternalStore(listener => {
    listeners.add(listener);
    return () => { listeners.delete(listener); };
  }, () => snapshot);
}

export function contextSignal(): AbortController {
  const controller = new AbortController();
  const generation = snapshot.generation;
  const unsubscribe = subscribeAuth(() => {
    if (snapshot.generation !== generation) controller.abort();
  });
  controller.signal.addEventListener('abort', unsubscribe, { once: true });
  return controller;
}
