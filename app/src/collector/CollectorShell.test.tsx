import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';

const { requestDevice, runBatch, resolveContexts, upload, cachedCredential, session, listeners, authorized } = vi.hoisted(() => ({
  requestDevice: vi.fn(), runBatch: vi.fn(), resolveContexts: vi.fn(), upload: vi.fn(),
  cachedCredential: vi.fn(),
  session: { user: { userId: 'user', email: 'field@example.org', memberships: [] }, activeOrganizationId: 'org' },
  listeners: new Set<() => void>(),
  authorized: { value: [] as unknown[] },
}));
vi.mock('../platform/web-bluetooth', () => ({
  bluetoothCapability: () => 'supported',
  previouslyAuthorizedDevices: async () => authorized.value,
  getDevicesSupport: () => 'supported',
  getDevicesFallbackMessage: (kind: string) => kind === 'unsupported' ? 'kein getDevices' : 'Noch kein Napf freigegeben',
  browserDeviceId: (d: { id?: string }, fallback: string) => d?.id ?? fallback,
  browserDeviceLabel: (d: { name?: string }, fallback: string) => d?.name ?? fallback,
  requestNodeDevice: requestDevice, WebBluetoothTransport: class {}, describeBluetoothError: String,
}));
vi.mock('../platform/auth', () => ({ getAuthState: () => ({ ...session }),
  subscribeAuth: (listener: () => void) => { listeners.add(listener); return () => listeners.delete(listener); } }));
vi.mock('../platform/offline-store', () => ({
  CollectorDatabase: class {
    nodeMeta = { get: async () => undefined };
    async open() {} close() {}
  }, requestPersistentStorage: async () => false,
}));
vi.mock('../platform/offline-identity', () => ({
  OfflineIdentityDatabase: class { close() {} }, OfflineIdentity: class { credential = cachedCredential; },
}));
vi.mock('./backend-upload', () => ({ BackendUploader: class { upload = upload; } }));
vi.mock('./observation-store', () => ({ CollectorObservationStore: class { async open() {} async organizationUploadStats() { return { pending: 0, uploaded: 1, failed: 0 }; } } }));
vi.mock('./batch-sync', async importOriginal => ({
  ...await importOriginal<typeof import('./batch-sync')>(),
  runBatchSync: runBatch,
}));
vi.mock('./batch-context', () => ({ resolveBatchContexts: resolveContexts }));
import { CollectorShell } from './CollectorShell';

afterEach(cleanup);
beforeEach(() => {
  session.activeOrganizationId = 'org'; requestDevice.mockReset(); runBatch.mockReset(); upload.mockReset();
  resolveContexts.mockReset(); resolveContexts.mockResolvedValue(new Map());
  cachedCredential.mockReset(); cachedCredential.mockResolvedValue('credential');
  upload.mockResolvedValue({ state: 'complete', message: '', uploaded: 0, duplicates: 0 });
  authorized.value = [];
});

function successResult(browserId: string, nodeId: string) {
  return {
    browserId, browserLabel: `Browser ${browserId}`, kind: 'success', nodeId, ownerNodeId: nodeId,
    recordsReceived: 2, watermark: '5', message: 'Fertig.', foreignOrganizationName: null,
    unclaimed: false, durationMs: 5,
  };
}

test('primary batch button reads all authorized bowls without another chooser', async () => {
  authorized.value = [{ id: 'a', name: 'Bowl A' }, { id: 'b', name: 'Bowl B' }];
  runBatch.mockResolvedValue([successResult('a', 'node-a'), successResult('b', 'node-b')]);
  render(<CollectorShell />);
  const button = await screen.findByRole('button', { name: 'Futterstelle auslesen' });
  await vi.waitFor(() => expect(button.hasAttribute('disabled')).toBe(false));
  fireEvent.click(button);
  await vi.waitFor(() => expect(runBatch).toHaveBeenCalledTimes(1));
  // Batch uses getDevices, never requestDevice for known bowls.
  expect(requestDevice).not.toHaveBeenCalled();
  const inputs = runBatch.mock.calls[0][0] as Array<{ browserId: string }>;
  expect(inputs.map(i => i.browserId).sort()).toEqual(['a', 'b']);
  expect(await screen.findByText('2 von 2 freigegebenen Näpfen ausgelesen.')).toBeTruthy();
});

test('requestDevice is only called by the explicit release action, never by batch start', async () => {
  authorized.value = [{ id: 'a', name: 'Bowl A' }];
  runBatch.mockResolvedValue([successResult('a', 'node-a')]);
  render(<CollectorShell />);
  fireEvent.click(await screen.findByRole('button', { name: 'Futterstelle auslesen' }));
  await vi.waitFor(() => expect(runBatch).toHaveBeenCalledTimes(1));
  expect(requestDevice).not.toHaveBeenCalled();
  requestDevice.mockResolvedValue({ id: 'new', name: 'Neuer Napf' });
  runBatch.mockResolvedValue([successResult('new', 'node-new')]);
  fireEvent.click(screen.getByRole('button', { name: 'Weiteren Napf freigeben' }));
  await vi.waitFor(() => expect(requestDevice).toHaveBeenCalledTimes(1));
  await vi.waitFor(() => expect(runBatch).toHaveBeenCalledTimes(2));
});

test('empty permission list is an honest fallback, never a false success', async () => {
  authorized.value = [];
  render(<CollectorShell />);
  fireEvent.click(await screen.findByRole('button', { name: 'Futterstelle auslesen' }));
  expect((await screen.findAllByText('Noch kein Napf freigegeben')).length).toBeGreaterThanOrEqual(1);
  expect(runBatch).not.toHaveBeenCalled();
  expect(screen.queryByText(/von .* freigegebenen Näpfen ausgelesen/)).toBeNull();
});

test('a pending HTTP upload never disables the batch button', async () => {
  upload.mockReturnValue(new Promise(() => {}));
  authorized.value = [{ id: 'a', name: 'Bowl A' }];
  runBatch.mockReturnValue(new Promise(() => {}));
  render(<CollectorShell />);
  await screen.findByText('Backend-Upload läuft …');
  const button = screen.getByRole('button', { name: 'Futterstelle auslesen' });
  expect(button.hasAttribute('disabled')).toBe(false);
  fireEvent.click(button);
  // requestDevice (chooser) is not part of batch start.
  expect(requestDevice).not.toHaveBeenCalled();
  await vi.waitFor(() => expect(runBatch).toHaveBeenCalledTimes(1));
});

test('failed bowls can be retried without repeating successes', async () => {
  authorized.value = [{ id: 'ok', name: 'Ok' }, { id: 'bad', name: 'Bad' }];
  runBatch.mockResolvedValueOnce([
    successResult('ok', 'node-ok'),
    { browserId: 'bad', browserLabel: 'Bad', kind: 'unreachable', nodeId: null, ownerNodeId: null, recordsReceived: 0, watermark: null, message: 'nicht erreichbar', foreignOrganizationName: null, unclaimed: false, durationMs: 1 },
  ]);
  render(<CollectorShell />);
  fireEvent.click(await screen.findByRole('button', { name: 'Futterstelle auslesen' }));
  await screen.findByText('1 von 2 freigegebenen Näpfen ausgelesen.');
  runBatch.mockResolvedValueOnce([successResult('bad', 'node-bad')]);
  fireEvent.click(screen.getByRole('button', { name: /Offene Näpfe erneut versuchen/ }));
  await vi.waitFor(() => expect(runBatch).toHaveBeenCalledTimes(2));
  const retryInputs = runBatch.mock.calls[1][0] as Array<{ browserId: string }>;
  expect(retryInputs.map(i => i.browserId)).toEqual(['bad']);
});

test('repeated presses while running cannot start overlapping batches', async () => {
  authorized.value = [{ id: 'a', name: 'A' }];
  let release!: (v: unknown) => void;
  runBatch.mockImplementationOnce(() => new Promise(resolve => { release = resolve as never; }));
  render(<CollectorShell />);
  const button = await screen.findByRole('button', { name: 'Futterstelle auslesen' });
  fireEvent.click(button);
  fireEvent.click(screen.getByRole('button', { name: 'Synchronisiere …' }));
  await vi.waitFor(() => expect(runBatch).toHaveBeenCalledTimes(1));
  await act(async () => { release([successResult('a', 'node-a')]); });
  await screen.findByText('1 von 1 freigegebenem Napf ausgelesen.');
  expect(runBatch).toHaveBeenCalledTimes(1);
});

test('a completed credential check with renewal deferred until sync is not an endless loading state', async () => {
  cachedCredential.mockResolvedValue(null);
  render(<CollectorShell/>);
  await screen.findByText('Kein gültiges Offline-Credential — wird bei Sync erneuert (Internet verfügbar).');
  expect(screen.queryByRole('progressbar', { name: 'Offline-Berechtigung' })).toBeNull();
});

test('old upload completion cannot overwrite a newly selected organization', async () => {
  let finish!: (value: unknown) => void;
  upload.mockImplementationOnce(() => new Promise(resolve => { finish = resolve; }));
  upload.mockResolvedValue({ state: 'complete', message: 'New organization upload', uploaded: 0, duplicates: 0 });
  render(<CollectorShell />); await screen.findByText('Backend-Upload läuft …');
  act(() => { session.activeOrganizationId = 'org-b'; for (const listener of listeners) listener(); });
  await act(async () => { finish({ state: 'failed', message: 'Old organization failure', uploaded: 0, duplicates: 0 }); });
  await screen.findByText('New organization upload');
  expect(screen.queryByText('Old organization failure')).toBeNull();
  expect(upload).toHaveBeenLastCalledWith('org-b');
});

test('retry after browser permission revocation releases the busy state', async () => {
  authorized.value = [{ id: 'bad', name: 'Bad' }];
  runBatch.mockResolvedValue([{ ...successResult('bad', 'node-bad'), kind: 'unreachable' }]);
  render(<CollectorShell/>);
  fireEvent.click(await screen.findByRole('button', { name: 'Futterstelle auslesen' }));
  const retry = await screen.findByRole('button', { name: /Offene Näpfe erneut versuchen/ });
  authorized.value = [];
  fireEvent.click(retry);
  await screen.findByText(/Keine wiederholbaren Näpfe mehr vorhanden/);
  expect(screen.getByRole('button', { name: 'Futterstelle auslesen' }).hasAttribute('disabled')).toBe(false);
  expect(runBatch).toHaveBeenCalledTimes(1);
});

test('a chooser resolved after an organization change cannot start a batch', async () => {
  let finish!: (device: unknown) => void;
  requestDevice.mockReturnValue(new Promise(resolve => { finish = resolve; }));
  render(<CollectorShell/>);
  fireEvent.click(await screen.findByRole('button', { name: 'Weiteren Napf freigeben' }));
  act(() => { session.activeOrganizationId = 'org-b'; for (const listener of listeners) listener(); });
  await act(async () => { finish({ id: 'old', name: 'Old selection' }); });
  expect(runBatch).not.toHaveBeenCalled();
  expect(screen.queryByText(/Old selection/)).toBeNull();
});

test('releasing an additional device during a batch queues it without overlapping sync', async () => {
  let finish!: (device: unknown) => void;
  requestDevice.mockReturnValue(new Promise(resolve => { finish = resolve; }));
  authorized.value = [{ id: 'known', name: 'Known' }];
  let complete!: (results: unknown[]) => void;
  runBatch.mockReturnValue(new Promise(resolve => { complete = resolve; }));
  render(<CollectorShell/>);
  fireEvent.click(await screen.findByRole('button', { name: 'Weiteren Napf freigeben' }));
  // The first release starts a batch; another release stays explicit and
  // joins the next batch while the first session remains active.
  await act(async () => { finish({ id: 'new', name: 'New' }); });
  await vi.waitFor(() => expect(runBatch).toHaveBeenCalledTimes(1));
  requestDevice.mockReturnValue(new Promise(resolve => { finish = resolve; }));
  fireEvent.click(screen.getByRole('button', { name: 'Weiteren Napf freigeben' }));
  await act(async () => { finish({ id: 'another', name: 'Another' }); });
  expect(runBatch).toHaveBeenCalledTimes(1);
  await screen.findByText(/für den nächsten Sammel-Sync vorgemerkt/);
  await act(async () => { complete([successResult('new', 'node-new')]); });
});

test('cancelled partial results do not leave context loading stuck', async () => {
  authorized.value = [{ id: 'a', name: 'A' }, { id: 'b', name: 'B' }];
  runBatch.mockImplementation(async (_inputs, options) => {
    options.signal.addEventListener('abort', () => {}, { once: true });
    return [successResult('a', 'node-a'), { ...successResult('b', 'node-b'), kind: 'cancelled' }];
  });
  let finish!: (contexts: Map<string, unknown>) => void;
  resolveContexts.mockReturnValue(new Promise(resolve => { finish = resolve; }));
  render(<CollectorShell/>);
  fireEvent.click(await screen.findByRole('button', { name: 'Futterstelle auslesen' }));
  await vi.waitFor(() => expect(resolveContexts).toHaveBeenCalledTimes(1));
  fireEvent.click(screen.getByRole('button', { name: 'Abbrechen' }));
  await act(async () => { finish(new Map()); });
  await screen.findByText('1 von 2 freigegebenen Näpfen ausgelesen.');
  expect(screen.queryByText('Futterstellenkontext wird geladen …')).toBeNull();
});

test('retry resumes interrupted bowls and updates the credential header', async () => {
  authorized.value = [{ id: 'ok' }, { id: 'interrupted' }, { id: 'skipped' }];
  runBatch.mockResolvedValueOnce([successResult('ok', 'node-ok'),
    { ...successResult('interrupted', 'node-interrupted'), kind: 'cancelled' },
    { ...successResult('skipped', 'node-skipped'), kind: 'skipped' }]);
  render(<CollectorShell/>);
  fireEvent.click(await screen.findByRole('button', { name: 'Futterstelle auslesen' }));
  const retry = await screen.findByRole('button', { name: /Offene Näpfe erneut versuchen/ });
  runBatch.mockImplementationOnce(async (inputs, options) => {
    options.onNodeUpdate(0, inputs.length, inputs[0], { credentialState: 'Offline-Credential erneuert.' });
    return inputs.map((i: { browserId: string }) => successResult(i.browserId, `node-${i.browserId}`));
  });
  fireEvent.click(retry);
  await screen.findByText('3 von 3 freigegebenen Näpfen ausgelesen.');
  expect(runBatch.mock.calls[1][0].map((i: { browserId: string }) => i.browserId)).toEqual(['interrupted', 'skipped']);
  expect(screen.getByText('Offline-Credential erneuert.')).toBeTruthy();
});

test('Sync illustration is replaceable centrally without changing Collector behavior', async () => {
  const { assets } = await import('../assets');
  const original = assets.illustrations.sync;
  try {
    assets.illustrations.sync = '/replacement-sync.svg';
    const { container } = render(<CollectorShell/>);
    await screen.findByText('Lokaler Speicher bereit');
    expect(container.querySelector('h1 img')?.getAttribute('src')).toBe('/replacement-sync.svg');
    expect(screen.getByRole('button', { name: 'Futterstelle auslesen' })).toBeTruthy();
  } finally { assets.illustrations.sync = original; }
});
