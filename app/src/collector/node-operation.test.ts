import { beforeEach, expect, test, vi } from 'vitest';
const { state, listeners } = vi.hoisted(() => ({
  state: { user: { userId: 'user', memberships: [{ organizationId: 'org', status: 'ACTIVE', role: 'ADMIN' }] }, activeOrganizationId: 'org' },
  listeners: new Set<() => void>(),
}));
vi.mock('../platform/auth', () => ({ getAuthState: () => state,
  subscribeAuth: (listener: () => void) => { listeners.add(listener); return () => listeners.delete(listener); } }));
import { bindNodeOperation, NodeOperationCancelledError } from './node-operation';

beforeEach(() => {
  state.user.userId = 'user'; state.activeOrganizationId = 'org';
  state.user.memberships = [{ organizationId: 'org', status: 'ACTIVE', role: 'ADMIN' }];
});

test.each(['account', 'organization', 'membership', 'role'])('%s changes stop pending reads and prohibit later ACK writes', async change => {
  let finish!: (frame: Uint8Array) => void;
  const raw = { connect: vi.fn(async () => {}), disconnect: vi.fn(async () => {}),
    write: vi.fn(async () => {}), read: vi.fn(() => new Promise<Uint8Array>(resolve => { finish = resolve; })) };
  const operation = bindNodeOperation(raw, 'user', 'org', undefined, 'ADMIN');
  try {
    const pending = operation.transport.read();
    const rejected = expect(pending).rejects.toThrow('abgebrochen');
    if (change === 'account') state.user.userId = 'other';
    if (change === 'organization') state.activeOrganizationId = 'other';
    if (change === 'membership') state.user.memberships[0].status = 'DISABLED';
    if (change === 'role') state.user.memberships[0].role = 'MEMBER';
    for (const listener of listeners) listener();
    // Returning to the initiating context must not resurrect its old work.
    state.user.userId = 'user'; state.activeOrganizationId = 'org';
    state.user.memberships[0].status = 'ACTIVE'; state.user.memberships[0].role = 'ADMIN';
    finish(new Uint8Array([1]));
    await rejected;
    await expect(operation.transport.write(new Uint8Array([2]))).rejects.toThrow('abgebrochen');
    expect(raw.disconnect).toHaveBeenCalled(); expect(raw.write).not.toHaveBeenCalled();
  } finally { await operation.close(); }
  expect(listeners.size).toBe(0);
});

test('closing the UI aborts its transport while ordinary auth notifications leave a visit active', async () => {
  const raw = { connect: vi.fn(async () => {}), disconnect: vi.fn(async () => {}),
    read: vi.fn(async () => new Uint8Array()), write: vi.fn(async () => {}) };
  const controller = new AbortController();
  const operation = bindNodeOperation(raw, 'user', 'org', controller.signal);
  try {
    for (const listener of listeners) listener();
    await operation.transport.connect();
    controller.abort();
    expect(raw.disconnect).toHaveBeenCalledTimes(1);
    await expect(operation.transport.write(new Uint8Array())).rejects.toThrow('abgebrochen');
    expect(raw.write).not.toHaveBeenCalled();
  } finally { await operation.close(); }
});

test('a browser rejection caused by cancellation retains its terminal cancellation type', async () => {
  let reject!: (error: unknown) => void;
  const raw = { connect: vi.fn(async () => {}), disconnect: vi.fn(async () => {}), write: vi.fn(async () => {}),
    read: vi.fn(() => new Promise<Uint8Array>((_, fail) => { reject = fail; })) };
  const controller = new AbortController();
  const operation = bindNodeOperation(raw, 'user', 'org', controller.signal);
  try {
    const pending = operation.transport.read();
    const rejected = expect(pending).rejects.toBeInstanceOf(NodeOperationCancelledError);
    controller.abort(); reject(new DOMException('GATT disconnected', 'NetworkError'));
    await rejected;
  } finally { await operation.close(); }
});

test('late cancellation notifications and cleanup cannot disconnect the replacement visit', async () => {
  const raw = { connect: vi.fn(async () => {}), disconnect: vi.fn(async () => {}),
    read: vi.fn(async () => new Uint8Array()), write: vi.fn(async () => {}) };
  const controller = new AbortController();
  const operation = bindNodeOperation(raw, 'user', 'org', controller.signal);
  controller.abort();
  await raw.connect(); // The replacement visit now owns the same browser server.
  state.activeOrganizationId = 'other';
  for (const listener of listeners) listener();
  await operation.transport.disconnect();
  await operation.close();
  expect(raw.disconnect).toHaveBeenCalledTimes(1);
  expect(listeners.size).toBe(0);
});
