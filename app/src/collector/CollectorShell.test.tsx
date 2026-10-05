import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';

const { requestDevice, runFieldSync, upload, session, listeners } = vi.hoisted(() => ({
  requestDevice: vi.fn(), runFieldSync: vi.fn(), upload: vi.fn(),
  session: { user: { userId: 'user', email: 'field@example.org', memberships: [] }, activeOrganizationId: 'org' },
  listeners: new Set<() => void>(),
}));
vi.mock('../platform/web-bluetooth', () => ({
  bluetoothCapability: () => 'supported', previouslyAuthorizedDevices: async () => [],
  requestNodeDevice: requestDevice, WebBluetoothTransport: class {}, describeBluetoothError: String,
}));
vi.mock('../platform/auth', () => ({ getAuthState: () => ({ ...session }),
  subscribeAuth: (listener: () => void) => { listeners.add(listener); return () => listeners.delete(listener); } }));
vi.mock('../platform/offline-store', () => ({
  CollectorDatabase: class { async open() {} close() {} }, requestPersistentStorage: async () => false,
}));
vi.mock('../platform/offline-identity', () => ({
  OfflineIdentityDatabase: class { close() {} }, OfflineIdentity: class { async credential() { return 'credential'; } },
}));
vi.mock('./backend-upload', () => ({ BackendUploader: class { upload = upload; } }));
vi.mock('./observation-store', () => ({ CollectorObservationStore: class { async open() {} async organizationUploadStats() { return { pending: 0, uploaded: 0, failed: 0 }; } } }));
vi.mock('./collector-sync', async importOriginal => ({
  ...await importOriginal<typeof import('./collector-sync')>(), runFieldSync,
}));
import { CollectorShell } from './CollectorShell';

afterEach(cleanup);
beforeEach(() => {
  session.activeOrganizationId = 'org'; requestDevice.mockReset(); runFieldSync.mockReset(); upload.mockReset();
  upload.mockResolvedValue({ state: 'complete', message: '', uploaded: 0, duplicates: 0 });
});

test('device chooser is called in the click before asynchronous field setup', async () => {
  requestDevice.mockReturnValue(new Promise(() => {}));
  render(<CollectorShell />);
  const button = await screen.findByRole('button', { name: 'Node auswählen & synchronisieren' });
  await vi.waitFor(() => expect(button.hasAttribute('disabled')).toBe(false));
  fireEvent.click(button);
  expect(requestDevice).toHaveBeenCalledTimes(1);
  expect(runFieldSync).not.toHaveBeenCalled();
});

test('a pending HTTP upload never disables the BLE chooser', async () => {
  upload.mockReturnValue(new Promise(() => {})); requestDevice.mockReturnValue(new Promise(() => {}));
  render(<CollectorShell />);
  await screen.findByText('Backend-Upload läuft …');
  const button = screen.getByRole('button', { name: 'Node auswählen & synchronisieren' });
  expect(button.hasAttribute('disabled')).toBe(false); fireEvent.click(button);
  expect(requestDevice).toHaveBeenCalledTimes(1);
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

test('a visit finishing during an existing upload queues another outbox drain', async () => {
  let finish!: (value: unknown) => void;
  upload.mockImplementationOnce(() => new Promise(resolve => { finish = resolve; }));
  upload.mockResolvedValue({ state: 'complete', message: 'Visit records uploaded', uploaded: 1, duplicates: 0 });
  requestDevice.mockResolvedValue({});
  runFieldSync.mockResolvedValue(undefined);
  render(<CollectorShell />);
  await screen.findByText('Backend-Upload läuft …');
  fireEvent.click(screen.getByRole('button', { name: 'Node auswählen & synchronisieren' }));
  await vi.waitFor(() => expect(runFieldSync).toHaveBeenCalledTimes(1));
  await vi.waitFor(() => expect(screen.getByRole('button', { name: 'Node auswählen & synchronisieren' }).hasAttribute('disabled')).toBe(false));
  expect(upload).toHaveBeenCalledTimes(1);
  await act(async () => { finish({ state: 'complete', message: 'Old snapshot uploaded', uploaded: 0, duplicates: 0 }); });
  await screen.findByText('Visit records uploaded');
  expect(upload).toHaveBeenCalledTimes(2);
});

test('a chooser started before switching away and back cannot start an obsolete visit', async () => {
  let choose!: (device: unknown) => void;
  requestDevice.mockReturnValue(new Promise(resolve => { choose = resolve; }));
  render(<CollectorShell />);
  const button = await screen.findByRole('button', { name: 'Node auswählen & synchronisieren' });
  fireEvent.click(button);
  act(() => {
    session.activeOrganizationId = 'org-b'; for (const listener of listeners) listener();
    session.activeOrganizationId = 'org'; for (const listener of listeners) listener();
  });
  await act(async () => { choose({}); });
  expect(runFieldSync).not.toHaveBeenCalled();
  expect(button.hasAttribute('disabled')).toBe(false);
});

test('cancelling an old visit allows another visit without old updates or completion disabling it', async () => {
  let finish!: () => void;
  requestDevice.mockResolvedValue({});
  runFieldSync.mockImplementationOnce(() => new Promise<void>(resolve => { finish = resolve; }));
  runFieldSync.mockImplementationOnce(() => new Promise(() => {}));
  render(<CollectorShell />);
  fireEvent.click(await screen.findByRole('button', { name: 'Node auswählen & synchronisieren' }));
  await vi.waitFor(() => expect(runFieldSync).toHaveBeenCalledTimes(1));
  const oldCallbacks = runFieldSync.mock.calls[0][0];
  act(() => { session.activeOrganizationId = 'org-b'; for (const listener of listeners) listener(); });
  expect(oldCallbacks.signal.aborted).toBe(true);
  fireEvent.click(screen.getByRole('button', { name: 'Node auswählen & synchronisieren' }));
  await vi.waitFor(() => expect(runFieldSync).toHaveBeenCalledTimes(2));
  await act(async () => {
    oldCallbacks.onUpdate({ fertig: true, nodeMessage: 'Obsolete success', nodeState: 'complete' });
    finish();
  });
  expect(screen.queryByText('Obsolete success')).toBeNull();
  expect(screen.getByRole('button', { name: 'Synchronisiere …' }).hasAttribute('disabled')).toBe(true);
});
