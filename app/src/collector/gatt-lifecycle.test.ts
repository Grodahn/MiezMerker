import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import { BLE_CHAR_UUIDS, WebBluetoothTransport } from '../platform/web-bluetooth';

beforeEach(() => Object.defineProperty(navigator, 'bluetooth', { value: {}, configurable: true }));
afterEach(() => { vi.useRealTimers(); Reflect.deleteProperty(navigator, 'bluetooth'); });

function fakeDevice() {
  const service = { getCharacteristic: vi.fn(async () => ({})) };
  const server = {
    connected: false,
    connect: vi.fn(async () => { server.connected = true; return server; }),
    disconnect: vi.fn(() => { server.connected = false; }),
    getPrimaryService: vi.fn(async () => service),
  };
  const device = { gatt: server, addEventListener: vi.fn(), removeEventListener: vi.fn() };
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
  await expect(transport.connect()).rejects.toMatchObject({ kind: 'connection-failed' });
  expect(service.getCharacteristic).toHaveBeenCalledTimes(1);
  expect(server.disconnect).toHaveBeenCalledTimes(1);
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
