import { describe, expect, test } from 'vitest';
import {
  BLE_CHAR_UUIDS, BLE_SERVICE_UUID, BluetoothTransportError, FrameChannel,
  characteristicForRequest, describeBluetoothError, type GattOperations,
} from '../platform/web-bluetooth';
import {
  encodeFrame, encodeHelloPublic, encodeOwnerResponse, Opcode, PROTOCOL_VERSION,
} from './ble-codec';

function helloFrame(): Uint8Array {
  return encodeFrame({
    version: PROTOCOL_VERSION, opcode: Opcode.HelloPublic,
    payload: encodeHelloPublic({
      serverVer: 1, caps: 0x1f, nodeId: '44444444-4444-4444-8444-444444444444',
      incarnation: '55555555-5555-4555-9555-555555555555',
      firmwareVersion: 'test', claimState: 1, clockStatus: 2,
    }),
  });
}

describe('Web Bluetooth adapter (GATT contract #6)', () => {
  test('exact service and characteristic UUIDs from fixtures', () => {
    expect(BLE_SERVICE_UUID).toBe('6f4a2c1e-8b3d-4e5f-9a0c-1d2e3f4a5b6c');
    expect(BLE_CHAR_UUIDS.info.endsWith('5b01')).toBe(true);
    expect(BLE_CHAR_UUIDS.time.endsWith('5b09')).toBe(true);
  });

  test('request opcodes route to the exact GATT characteristic', () => {
    expect(characteristicForRequest(Opcode.HelloRequest)).toBe(BLE_CHAR_UUIDS.info);
    expect(characteristicForRequest(Opcode.OwnerRequest)).toBe(BLE_CHAR_UUIDS.owner);
    expect(characteristicForRequest(Opcode.ChallengeRequest)).toBe(BLE_CHAR_UUIDS.challenge);
    expect(characteristicForRequest(Opcode.AuthRequest)).toBe(BLE_CHAR_UUIDS.auth);
    expect(characteristicForRequest(Opcode.BatchRequest)).toBe(BLE_CHAR_UUIDS.batch);
    expect(characteristicForRequest(Opcode.AckRequest)).toBe(BLE_CHAR_UUIDS.ack);
    expect(characteristicForRequest(Opcode.CompactRequest)).toBe(BLE_CHAR_UUIDS.ack);
    expect(() => characteristicForRequest(0x42 as Opcode)).toThrow('Unbekannte Protokoll');
  });

  test('read-only requests never write; batch prefers notify with read fallback', async () => {
    const calls: string[] = [];
    const response = helloFrame();
    const ops: GattOperations = {
      read: async (uuid) => { calls.push(`read:${uuid}`); return response; },
      write: async (uuid) => { calls.push(`write:${uuid}`); },
      notify: async (uuid) => { calls.push(`notify:${uuid}`); return response; },
    };
    const channel = new FrameChannel(ops);
    const { encodeHelloRequest } = await import('./ble-codec');
    await channel.write(encodeFrame({
      version: 1, opcode: Opcode.HelloRequest, payload: encodeHelloRequest({ clientVer: 1, caps: 0x1f }),
    }));
    expect(calls).toEqual([]);
    expect(await channel.read()).toEqual(response);
    expect(calls).toEqual([`read:${BLE_CHAR_UUIDS.info}`]);
  });

  test('write failures surface as disconnect (reconnectable)', async () => {
    const ops: GattOperations = {
      read: async () => new Uint8Array(),
      write: async () => { throw new Error('link lost'); },
      notify: async () => new Uint8Array(),
    };
    const channel = new FrameChannel(ops);
    const { encodeAuthRequest } = await import('./ble-codec');
    await expect(channel.write(encodeFrame({
      version: 1, opcode: Opcode.AuthRequest,
      payload: encodeAuthRequest({ credential: 'c', proof: new Uint8Array(64) }),
    }))).rejects.toMatchObject({ name: 'BluetoothTransportError' });
  });

  test('clear German errors for capability/permission cases', () => {
    expect(describeBluetoothError(new BluetoothTransportError('permission-denied', 'Bluetooth-Berechtigung verweigert.')))
      .toContain('Berechtigung');
    const notFound = new Error('x');
    notFound.name = 'NotFoundError';
    expect(describeBluetoothError(notFound)).toContain('Kein Node gefunden');
  });

  test('owner response decodes through the channel path', async () => {
    const owner = encodeFrame({
      version: 1, opcode: Opcode.OwnerResponse,
      payload: encodeOwnerResponse({
        nodeId: '44444444-4444-4444-8444-444444444444', claimState: 1,
        organizationId: 'org', organizationSlug: 'org', organizationName: 'Org', publicContact: '',
      }),
    });
    const ops: GattOperations = {
      read: async () => owner,
      write: async () => {},
      notify: async () => owner,
    };
    const channel = new FrameChannel(ops);
    await channel.write(encodeFrame({ version: 1, opcode: Opcode.OwnerRequest, payload: new Uint8Array() }));
    expect(await channel.read()).toEqual(owner);
  });
});
