import { afterEach, expect, test } from 'vitest';
import { bluetoothCapability, getDevicesSupport } from './web-bluetooth';

const original = Object.getOwnPropertyDescriptor(navigator, 'bluetooth');
afterEach(() => {
  if (original) Object.defineProperty(navigator, 'bluetooth', original);
  else Reflect.deleteProperty(navigator, 'bluetooth');
});

test('an absent or unusable Bluetooth API is a blocking capability failure', () => {
  for (const value of [undefined, null, {}, { getDevices: async () => [] }]) {
    Object.defineProperty(navigator, 'bluetooth', { configurable: true, value });
    expect(bluetoothCapability()).toBe('unsupported');
  }
});

test('a usable permission chooser without getDevices permits explicit recovery', () => {
  Object.defineProperty(navigator, 'bluetooth', { configurable: true, value: { requestDevice: async () => ({}) } });
  expect(bluetoothCapability()).toBe('supported');
  expect(getDevicesSupport()).toBe('unsupported');
});
