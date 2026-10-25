import { ClaimAttempt } from './claim-attempt';
import { fetchCsrfToken, getAuthState, subscribeAuth } from '../platform/auth';
import { authenticationEpoch } from '../platform/authorization-status';

// Only one current attempt is retained, for at most 15 minutes. Never persisted.
let current: { attempt: ClaimAttempt; node: string; org: string; user: string; epoch: string | null;
  provisioned: boolean; created: number; sent: number; posts: number; pending: boolean; controller?: AbortController; timer?: ReturnType<typeof setTimeout> } | undefined;
function valid(c: NonNullable<typeof current>): boolean {
  const s = getAuthState();
  return s.user?.userId === c.user && s.activeOrganizationId === c.org && authenticationEpoch() === c.epoch
    && Date.now() - c.created < 900000 && !!s.user.memberships.some(m => m.organizationId === c.org && m.role === 'ADMIN' && m.status === 'ACTIVE');
}
function clear() { if (current) { clearTimeout(current.timer); current.controller?.abort(); current = undefined; } }
subscribeAuth(() => {
  const s = getAuthState();
  // fetchSession rotates the shared epoch even on a normal same-account
  // refresh. Adopt that confirmed refresh; cross-tab changes are unverified
  // and must still discard the attempt before any further delivery.
  if (current && s.sessionVerified && s.user?.userId === current.user && s.activeOrganizationId === current.org) {
    current.epoch = authenticationEpoch();
  }
  if (current && !valid(current)) clear();
});
if (typeof window !== 'undefined') window.addEventListener('storage', () => { if (current && !valid(current)) clear(); });
export function claimAttempt(node: string, org: string, fresh = false): ClaimAttempt {
  if (current && (!valid(current) || fresh || (current.node !== node && current.node !== '') || current.org !== org)) clear();
  if (!current) current = { attempt: new ClaimAttempt(), node, org, user: getAuthState().user?.userId ?? '',
    epoch: authenticationEpoch(), provisioned: false, created: Date.now(), sent: 0, posts: 0, pending: false };
  current.node = node;
  return current.attempt;
}
export function startProvisionAttempt(node: string, org: string): ClaimAttempt {
  const attempt = claimAttempt(node, org, current?.provisioned ?? false);
  current!.provisioned = true;
  return attempt;
}
export function lastClaimAttempt(): ClaimAttempt | undefined {
  if (current && !valid(current)) clear();
  return current?.attempt;
}
export function flushClaimAttempt(attempt: ClaimAttempt): void {
  const c = current;
  if (!c || c.attempt !== attempt || c.pending || c.posts >= 4 || !valid(c)) return;
  c.pending = true;
  // Scheduling, CSRF fetch and network all happen after returning to the caller.
  c.timer = setTimeout(() => { void send(c).catch(() => {}).finally(() => { c.pending = false; }); }, 0);
}
async function send(c: NonNullable<typeof current>): Promise<void> {
  for (let retry = 0; retry < 2 && c.posts < 4; retry++) {
    if (current !== c || !valid(c) || !getAuthState().sessionVerified || (typeof navigator !== 'undefined' && !navigator.onLine)) return;
    const events = c.attempt.events.filter(e => e.sequence > c.sent).slice(0, 32);
    if (!events.length) return;
    c.posts++;
    c.controller = new AbortController();
    const timeout = setTimeout(() => c.controller?.abort(), 2000);
    try {
      // Existing CSRF infrastructure, never part of the BLE critical path.
      const token = getAuthState().csrfToken ?? await fetchCsrfToken();
      if (current !== c || !valid(c) || c.controller.signal.aborted || !token) return;
      const response = await fetch('/api/v1/nodes/claim-diagnostics', { method: 'POST', credentials: 'same-origin',
        signal: c.controller.signal, headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': token },
        body: JSON.stringify({ attemptId: c.attempt.attemptId, organizationId: c.org, events }) });
      if (response.ok) { c.sent = events.at(-1)!.sequence; retry--; }
      else if (response.status < 500) return;
    } catch { /* Best effort; recorder remains available for local export. */ }
    finally { clearTimeout(timeout); }
  }
}
