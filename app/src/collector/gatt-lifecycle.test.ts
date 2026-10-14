import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import { BLE_CHAR_UUIDS, WebBluetoothTransport } from '../platform/web-bluetooth';
import { bluetoothErrorMessage } from '../ui/status-messages';

beforeEach(() => Object.defineProperty(navigator, 'bluetooth', { value: {}, configurable: true }));
afterEach(() => { vi.useRealTimers(); Reflect.deleteProperty(navigator, 'bluetooth'); });

function fakeDevice() {
  const events = new EventTarget();
  const service = { getCharacteristic: vi.fn(async () => ({})) };
  const server = {
    connected: false,
    connect: vi.fn(async () => { server.connected = true; return server; }),
    disconnect: vi.fn(() => { server.connected = false; events.dispatchEvent(new Event('gattserverdisconnected')); }),
    getPrimaryService: vi.fn(async () => service),
  };
  const device = { gatt: server, addEventListener: vi.fn(events.addEventListener.bind(events)),
    removeEventListener: vi.fn(events.removeEventListener.bind(events)) };
  return { service, server, device, transport: new WebBluetoothTransport(device) };
}

test('connect timeout cancels the browser GATT attempt before it becomes connected', async () => {
  vi.useFakeTimers();
  const { server, device, transport } = fakeDevice();
  server.connect.mockImplementation(() => new Promise(() => {}));
  const rejected = expect(transport.connect()).rejects.toMatchObject({ kind: 'timeout' });
  await vi.advanceTimersByTimeAsync(10_000);
  await rejected;
  expect(server.disconnect).toHaveBeenCalledTimes(1);
  expect(server.getPrimaryService).not.toHaveBeenCalled();
  expect(device.removeEventListener).toHaveBeenCalled();
  expect(transport.connected).toBe(false);
});

test('discovery stops on a lost connection instead of misreporting missing firmware features', async () => {
  const { service, server, transport } = fakeDevice();
  service.getCharacteristic.mockRejectedValue(new DOMException('Connection lost', 'NetworkError'));
  await expect(transport.connect()).rejects.toMatchObject({ kind: 'connection-failed', stage: 'characteristic' });
  expect(service.getCharacteristic).toHaveBeenCalledTimes(1);
  expect(server.disconnect).toHaveBeenCalledTimes(1);
});

test.each(['connect', 'service', 'characteristic'] as const)('UI preserves the failing GATT %s phase', async stage => {
  const { server, service, transport } = fakeDevice();
  const failure = new DOMException('Browser-specific details', 'NetworkError');
  if (stage === 'connect') server.connect.mockRejectedValue(failure);
  else if (stage === 'service') server.getPrimaryService.mockRejectedValue(failure);
  else service.getCharacteristic.mockRejectedValue(failure);
  const error = await transport.connect().catch(error => error);
  expect(error).toMatchObject({ kind: 'connection-failed', stage });
  const displayed = bluetoothErrorMessage(error);
  expect(displayed).toContain(stage === 'connect' ? 'GATT-Verbindungsaufbau'
    : stage === 'service' ? 'Dienstsuche' : 'Characteristic-Suche (info)');
  expect(displayed).not.toContain('Browser-specific details');
  expect(displayed).not.toContain('näher herangehen');
});

test('phase diagnostics preserve the actionable permission-denied message', async () => {
  const { server, transport } = fakeDevice();
  server.connect.mockRejectedValue(new DOMException('Denied', 'SecurityError'));
  const error = await transport.connect().catch(error => error);
  expect(bluetoothErrorMessage(error)).toContain('Bluetooth-Berechtigung verweigert');
});

test('discovery timeout is reported as a timeout and disconnects immediately', async () => {
  vi.useFakeTimers();
  const { service, server, transport } = fakeDevice();
  service.getCharacteristic.mockImplementation(() => new Promise(() => {}));
  const rejected = expect(transport.connect()).rejects.toMatchObject({ kind: 'timeout' });
  await vi.advanceTimersByTimeAsync(10_000);
  await rejected;
  expect(service.getCharacteristic).toHaveBeenCalledTimes(1);
  expect(server.disconnect).toHaveBeenCalledTimes(1);
});

test('one stalled service discovery reconnects the same permitted device before reading identity', async () => {
  vi.useFakeTimers();
  const { service, server, transport } = fakeDevice();
  server.getPrimaryService.mockImplementationOnce(() => new Promise(() => {}));
  const connected = transport.connect();
  await vi.advanceTimersByTimeAsync(10_000);
  await connected;
  expect(server.connect).toHaveBeenCalledTimes(2);
  expect(server.disconnect).toHaveBeenCalledTimes(1);
  expect(server.getPrimaryService).toHaveBeenCalledTimes(2);
  expect(service.getCharacteristic).toHaveBeenCalledTimes(11);
  expect(transport.connected).toBe(true);
});

test('repeated service discovery timeout remains bounded and preserves its phase', async () => {
  vi.useFakeTimers();
  const { server, transport } = fakeDevice();
  server.getPrimaryService.mockImplementation(() => new Promise(() => {}));
  const rejected = expect(transport.connect()).rejects.toMatchObject({ kind: 'timeout', stage: 'service' });
  await vi.advanceTimersByTimeAsync(20_000);
  await rejected;
  expect(server.connect).toHaveBeenCalledTimes(2);
  expect(server.disconnect).toHaveBeenCalledTimes(2);
  expect(transport.connected).toBe(false);
});

test('cancellation during service discovery never reconnects after the timeout', async () => {
  vi.useFakeTimers();
  const { server, transport } = fakeDevice();
  server.getPrimaryService.mockImplementation(() => new Promise(() => {}));
  const rejected = expect(transport.connect()).rejects.toMatchObject({ kind: 'disconnected' });
  await vi.advanceTimersByTimeAsync(1);
  await transport.disconnect();
  await vi.advanceTimersByTimeAsync(10_000);
  await rejected;
  expect(server.connect).toHaveBeenCalledTimes(1);
  expect(transport.connected).toBe(false);
});

test('only missing optional claim characteristics are tolerated on older nodes', async () => {
  const { service, transport } = fakeDevice();
  service.getCharacteristic.mockImplementation(async (uuid?: string) => {
    if (uuid === BLE_CHAR_UUIDS.claimAdvertisement || uuid === BLE_CHAR_UUIDS.claimReceipt) {
      throw new DOMException('Not found', 'NotFoundError');
    }
    return {};
  });
  await transport.connect();
  expect(transport.connected).toBe(true);
  await transport.disconnect();
  service.getCharacteristic.mockRejectedValue(new DOMException('Not found', 'NotFoundError'));
  await expect(transport.connect()).rejects.toMatchObject({ kind: 'protocol' });
});

test('a new transport waits for asynchronous Android disconnect before reconnecting the same device', async () => {
  vi.useFakeTimers();
  const device = new EventTarget();
  let disconnecting = false;
  const server = {
    connected: false,
    connect: vi.fn(async () => {
      if (disconnecting) throw new DOMException('Previous GATT connection is closing', 'NetworkError');
      server.connected = true; return server;
    }),
    disconnect: vi.fn(() => {
      server.connected = false; disconnecting = true;
      setTimeout(() => { disconnecting = false; device.dispatchEvent(new Event('gattserverdisconnected')); }, 50);
    }),
    getPrimaryService: vi.fn(async () => ({ getCharacteristic: async () => ({}) })),
  };
  Object.assign(device, { gatt: server });
  const first = new WebBluetoothTransport(device), second = new WebBluetoothTransport(device);
  await first.connect();
  const closing = first.disconnect();
  const connecting = second.connect();
  const verified = expect(connecting).resolves.toBeUndefined();
  await vi.advanceTimersByTimeAsync(50);
  await closing; await verified;
  expect(server.connect).toHaveBeenCalledTimes(2);
  expect(second.connected).toBe(true);
});

test('cancelling while waiting for earlier teardown does not start a late connection', async () => {
  vi.useFakeTimers();
  const device = new EventTarget();
  const server = {
    connected: false,
    connect: vi.fn(async () => { server.connected = true; return server; }),
    disconnect: vi.fn(() => {
      server.connected = false;
      setTimeout(() => device.dispatchEvent(new Event('gattserverdisconnected')), 50);
    }),
    getPrimaryService: vi.fn(async () => ({ getCharacteristic: async () => ({}) })),
  };
  Object.assign(device, { gatt: server });
  const first = new WebBluetoothTransport(device), second = new WebBluetoothTransport(device);
  await first.connect();
  const closing = first.disconnect();
  const rejected = expect(second.connect()).rejects.toMatchObject({ kind: 'disconnected' });
  const cancelled = second.disconnect();
  await vi.advanceTimersByTimeAsync(50);
  await closing; await cancelled; await rejected;
  expect(server.connect).toHaveBeenCalledTimes(1);
  expect(second.connected).toBe(false);
});

test('missing disconnect event has bounded cleanup and leaves no connection handlers', async () => {
  vi.useFakeTimers();
  const { server, device, transport } = fakeDevice();
  server.disconnect.mockImplementation(() => { server.connected = false; });
  await transport.connect();
  let done = false;
  const closing = transport.disconnect().then(() => { done = true; });
  await vi.advanceTimersByTimeAsync(1999);
  expect(done).toBe(false);
  await vi.advanceTimersByTimeAsync(1);
  await closing;
  expect(device.removeEventListener).toHaveBeenCalledTimes(2);
  expect(transport.connected).toBe(false);
});

test('late discovery from a cancelled connection cannot disconnect its replacement', async () => {
  const { service, server, transport } = fakeDevice();
  let finish!: (value: object) => void;
  service.getCharacteristic.mockImplementationOnce(() => new Promise(resolve => { finish = resolve; }));
  const cancelled = expect(transport.connect()).rejects.toMatchObject({ kind: 'disconnected' });
  await vi.waitFor(() => expect(service.getCharacteristic).toHaveBeenCalledTimes(1));
  await transport.disconnect();
  await transport.connect();
  finish({}); await cancelled;
  expect(server.disconnect).toHaveBeenCalledTimes(1);
  expect(transport.connected).toBe(true);
});
