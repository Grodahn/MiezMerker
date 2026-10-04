import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, expect, test, vi } from 'vitest';

const { requestDevice, runFieldSync } = vi.hoisted(() => ({ requestDevice: vi.fn(), runFieldSync: vi.fn() }));
vi.mock('../platform/web-bluetooth', () => ({
  bluetoothCapability: () => 'supported', previouslyAuthorizedDevices: async () => [],
  requestNodeDevice: requestDevice, WebBluetoothTransport: class {}, describeBluetoothError: String,
}));
vi.mock('../platform/auth', () => ({ getAuthState: () => ({
  user: { userId: 'user', email: 'field@example.org', memberships: [] }, activeOrganizationId: 'org',
}), subscribeAuth: () => () => {} }));
vi.mock('../platform/offline-store', () => ({
  CollectorDatabase: class { async open() {} close() {} }, requestPersistentStorage: async () => false,
}));
vi.mock('../platform/offline-identity', () => ({
  OfflineIdentityDatabase: class { close() {} }, OfflineIdentity: class { async credential() { return 'credential'; } },
}));
vi.mock('./backend-upload', () => ({ BackendUploader: class { async upload() { return { state: 'complete', message: '', uploaded: 0, duplicates: 0 }; } } }));
vi.mock('./observation-store', () => ({ CollectorObservationStore: class { async open() {} async pendingUploads() { return []; } } }));
vi.mock('./collector-sync', async importOriginal => ({
  ...await importOriginal<typeof import('./collector-sync')>(), runFieldSync,
}));
import { CollectorShell } from './CollectorShell';

afterEach(cleanup);

test('device chooser is called in the click before asynchronous field setup', async () => {
  requestDevice.mockReturnValue(new Promise(() => {}));
  render(<CollectorShell />);
  const button = await screen.findByRole('button', { name: 'Node auswählen & synchronisieren' });
  await vi.waitFor(() => expect(button.hasAttribute('disabled')).toBe(false));
  fireEvent.click(button);
  expect(requestDevice).toHaveBeenCalledTimes(1);
  expect(runFieldSync).not.toHaveBeenCalled();
});
