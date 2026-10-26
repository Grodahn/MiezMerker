// @vitest-environment node
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
const { state, listeners } = vi.hoisted(() => ({ state: { value: {} as any, epoch: 'epoch' }, listeners: new Set<() => void>() }));
vi.mock('../platform/auth', () => ({ getAuthState: () => state.value,
  subscribeAuth: (fn: () => void) => { listeners.add(fn); return () => listeners.delete(fn); }, fetchCsrfToken: async () => 'csrf-secret' }));
vi.mock('../platform/authorization-status', () => ({ authenticationEpoch: () => state.epoch }));
import { ClaimAttempt } from './claim-attempt';
import { claimAttempt, flushClaimAttempt, lastClaimAttempt } from './claim-delivery';
import { diagnosticTransport, advertisementError } from '../collector/claim-diagnostic-transport';
import { decodeFrame, encodeFrame, encodeErrorPayload, Opcode, SyncError } from '../collector/ble-codec';
import { DiagnosticError, errorCodes } from './errors';
beforeEach(() => {
  vi.useFakeTimers();
  state.value = {}; for (const fn of listeners) fn();
  state.value = { user: { userId: 'user-secret', memberships: [{ organizationId: 'org-secret', status: 'ACTIVE', role: 'ADMIN' }] },
    activeOrganizationId: 'org-secret', sessionVerified: true, csrfToken: 'csrf-secret' };
  vi.stubGlobal('navigator', { onLine: true });
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true }));
});
afterEach(() => { state.value = {}; for (const fn of listeners) fn(); vi.useRealTimers(); vi.unstubAllGlobals(); });
test('bounded portable recorder never exports exception text, identity or proof material', () => {
  const a = new ClaimAttempt();
  for (let i=0;i<100;i++) a.event('hello','ok');
  a.failure(new Error('secret-jws\nforged=true RFID person@example.org'));
  expect(a.events).toHaveLength(64);
  expect(a.export()).not.toMatch(/secret|RFID|forged|@/);
  expect(a.events.at(-1)?.code).toBe(errorCodes.BLE_CONNECTION);
  expect(a.attemptId).toMatch(/^[0-9a-f-]{36}$/);
});
test('slow or absent diagnostic server cannot delay completion; retries and memory bounded', async () => {
  const a = claimAttempt('node','org-secret',true); a.event('complete','ok');
  vi.mocked(fetch).mockImplementation(() => new Promise((_resolve, reject) => setTimeout(() => reject(new Error('offline secret')), 1000)));
  expect(flushClaimAttempt(a)).toBeUndefined(); expect(fetch).not.toHaveBeenCalled();
  await vi.runAllTimersAsync(); expect(fetch).toHaveBeenCalledTimes(2);
  expect(lastClaimAttempt()?.export()).toContain('not-confirmed');
});
test('auth/org switch aborts delivery, discards buffer and never sends into new tenant', async () => {
  const a = claimAttempt('node','org-secret',true); a.event('preflight','ok'); flushClaimAttempt(a);
  let signal: AbortSignal | undefined;
  vi.mocked(fetch).mockImplementation((_url, init) => { signal = init?.signal as AbortSignal; return new Promise(() => {}); });
  await vi.advanceTimersByTimeAsync(0); expect(fetch).toHaveBeenCalledTimes(1);
  state.value.activeOrganizationId = 'other'; for (const fn of listeners) fn();
  expect(signal?.aborted).toBe(true); expect(lastClaimAttempt()).toBeUndefined();
  await vi.advanceTimersByTimeAsync(5000); expect(fetch).toHaveBeenCalledTimes(1);
});
test('preflight failure uploads safely with original ID, CSRF and fixed fields', async () => {
  const a = claimAttempt('node','org-secret',true);
  a.event('policy','started'); a.failure(new DiagnosticError(errorCodes.CLOCK_POLICY,'private details'));
  flushClaimAttempt(a); await vi.runAllTimersAsync();
  const [url, options] = vi.mocked(fetch).mock.calls[0];
  expect(url).toBe('/api/v1/nodes/claim-diagnostics');
  expect(options?.headers).toMatchObject({ 'X-XSRF-TOKEN': 'csrf-secret' });
  const body = JSON.parse(options?.body as string);
  expect(body.attemptId).toBe(a.attemptId); expect(body.events.at(-1).code).toBe(errorCodes.CLOCK_POLICY);
  expect(options?.body).not.toMatch(/private|node|user-secret|csrf/);
});
test.each([SyncError.ClockUnavailable, SyncError.InvalidState, SyncError.InvalidFrame, SyncError.Unauthorized])('BLE rejection %s stays distinct with no raw message', async code => {
  const bytes = encodeFrame({ version: 1, opcode: Opcode.Error, payload: encodeErrorPayload({ code, message: 'secret-jws' }) });
  const a = new ClaimAttempt(); const raw = { connect: vi.fn(),disconnect: vi.fn(),write: vi.fn(),read: vi.fn().mockResolvedValue(bytes) };
  const t = diagnosticTransport(raw, a);
  await t.write(encodeFrame({ version: 1, opcode: Opcode.ClaimAdvertisementRequest, payload: new Uint8Array() }));
  expect(await t.read()).toBe(bytes); expect(raw.write).toHaveBeenCalledTimes(1); expect(raw.read).toHaveBeenCalledTimes(1);
  expect(a.events.at(-1)?.code).toBe(advertisementError(decodeFrame(bytes)).code);
  expect(a.export()).not.toContain('secret-jws');
});
test.each([['TimeoutError','timeout',errorCodes.BLE_TIMEOUT], ['NetworkError','failed',errorCodes.BLE_CONNECTION],
  ['AbortError','cancelled',errorCodes.AUTH_CHANGED]] as const)('transport %s safely mapped', (name,status,code) => {
  const a = new ClaimAttempt(); a.event('connection','started'); a.failure({ name, message: 'secret' });
  expect(a.events.at(-1)).toMatchObject({ phase: 'connection', status, code });
});
test('offline preflight remains locally exportable without network requests', async () => {
  vi.stubGlobal('navigator',{ onLine: false }); const a=claimAttempt('node','org-secret',true);
  a.failure(new DiagnosticError(errorCodes.CLOCK_REJECTED,'hidden')); flushClaimAttempt(a);
  await vi.runAllTimersAsync(); expect(fetch).not.toHaveBeenCalled(); expect(lastClaimAttempt()?.events.at(-1)?.code).toBe(errorCodes.CLOCK_REJECTED);
});
test('confirmed same-account session refresh preserves correlation; unverified epoch change discards it', async () => {
  const a = claimAttempt('node','org-secret',true);
  a.event('preflight','ok');
  state.epoch = 'confirmed-refresh'; for (const fn of listeners) fn();
  expect(lastClaimAttempt()?.attemptId).toBe(a.attemptId);
  a.event('complete','ok'); flushClaimAttempt(a); await vi.runAllTimersAsync();
  expect(JSON.parse(vi.mocked(fetch).mock.calls[0][1]?.body as string).attemptId).toBe(a.attemptId);
  state.epoch = 'other-tab-change'; state.value.sessionVerified = false;
  for (const fn of listeners) fn();
  expect(lastClaimAttempt()).toBeUndefined();
});
test('completion flush racing an already drained upload still delivers terminal events', async () => {
  const a = claimAttempt('node', 'org-secret', true); a.event('preflight', 'ok');
  let resolveFirst!: (value: { ok: boolean }) => void;
  vi.mocked(fetch).mockImplementationOnce(() => new Promise(resolve => { resolveFirst = resolve as typeof resolveFirst; }));
  flushClaimAttempt(a); await vi.advanceTimersByTimeAsync(0);
  resolveFirst({ ok: true });
  await Promise.resolve(); // send has drained; its chained finalizer is still pending.
  a.event('complete', 'ok'); flushClaimAttempt(a);
  await vi.runAllTimersAsync();
  expect(fetch).toHaveBeenCalledTimes(2);
  expect(JSON.parse(vi.mocked(fetch).mock.calls[1][1]?.body as string).events).toMatchObject([{ phase: 'complete', status: 'ok' }]);
});
test('coalesced flushes cannot exceed the attempt-wide network budget', async () => {
  const a = claimAttempt('node', 'org-secret', true); a.event('complete', 'ok');
  vi.mocked(fetch).mockRejectedValue(new Error('offline'));
  flushClaimAttempt(a); await vi.advanceTimersByTimeAsync(0);
  for (let i = 0; i < 100; i++) flushClaimAttempt(a);
  await vi.runAllTimersAsync();
  expect(vi.mocked(fetch).mock.calls.length).toBeLessThanOrEqual(4);
});
test('rolling phone time back cannot extend the local diagnostic retention window', () => {
  claimAttempt('node', 'org-secret', true);
  vi.setSystemTime(Date.now() - 3600000);
  vi.advanceTimersByTime(900000);
  expect(lastClaimAttempt()).toBeUndefined();
});
