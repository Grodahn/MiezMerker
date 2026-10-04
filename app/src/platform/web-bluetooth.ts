// Web Bluetooth adapter for the NapfNode GATT contract (issue #8, protocol #6).
//
// Exact contract from protocol/ble/messages.md and fixtures/ble-sync-v1.json.
// Service 6f4a2c1e-8b3d-4e5f-9a0c-1d2e3f4a5b6c, characteristics ...5b01..09.
// No OS-level manual pairing is required; authorization is application-layer
// via offline credentials (#17). No background BLE in the MVP.
//
// Layering: this file owns transport only. Codec stays in collector/ble-codec.ts,
// domain state machine in collector/sync-engine.ts. Management pages never import it.

import { decodeFrame, Opcode } from '../collector/ble-codec';
import type { NodeTransport } from './node-transport';

export const BLE_SERVICE_UUID = '6f4a2c1e-8b3d-4e5f-9a0c-1d2e3f4a5b6c';
export const BLE_CHAR_UUIDS = {
  info: '6f4a2c1e-8b3d-4e5f-9a0c-1d2e3f4a5b01',
  owner: '6f4a2c1e-8b3d-4e5f-9a0c-1d2e3f4a5b02',
  challenge: '6f4a2c1e-8b3d-4e5f-9a0c-1d2e3f4a5b03',
  auth: '6f4a2c1e-8b3d-4e5f-9a0c-1d2e3f4a5b04',
  nodeProof: '6f4a2c1e-8b3d-4e5f-9a0c-1d2e3f4a5b05',
  status: '6f4a2c1e-8b3d-4e5f-9a0c-1d2e3f4a5b06',
  batch: '6f4a2c1e-8b3d-4e5f-9a0c-1d2e3f4a5b07',
  ack: '6f4a2c1e-8b3d-4e5f-9a0c-1d2e3f4a5b08',
  time: '6f4a2c1e-8b3d-4e5f-9a0c-1d2e3f4a5b09',
} as const;

export type BluetoothCapability =
  | 'supported'
  | 'unsupported'
  | 'unknown';

export function bluetoothCapability(): BluetoothCapability {
  if (typeof navigator === 'undefined') return 'unknown';
  return 'bluetooth' in navigator ? 'supported' : 'unsupported';
}

export function bluetoothSupportMessage(capability: BluetoothCapability): string {
  if (capability === 'supported') return '';
  if (capability === 'unknown') return 'Bluetooth-Status kann nicht geprüft werden.';
  return 'Dieser Browser unterstützt kein Web Bluetooth. Bitte aktuelles Chrome/Chromium auf Android verwenden.';
}

// Typed transport errors so the /sync UI can show the exact #8 messages.
export type BluetoothFailureKind =
  | 'unsupported'
  | 'permission-denied'
  | 'no-device'
  | 'connection-failed'
  | 'disconnected'
  | 'protocol'
  | 'timeout';

export class BluetoothTransportError extends Error {
  constructor(
    readonly kind: BluetoothFailureKind,
    message: string,
    readonly cause?: unknown,
  ) {
    super(message);
    this.name = 'BluetoothTransportError';
  }
}

export function describeBluetoothError(error: unknown): string {
  if (error instanceof BluetoothTransportError) return error.message;
  if (error instanceof Error) {
    if (error.name === 'NotFoundError') return 'Kein Node gefunden. Node einschalten und erneut suchen.';
    if (error.name === 'NotAllowedError' || error.name === 'SecurityError') {
      return 'Bluetooth-Berechtigung verweigert. Bitte Zugriff erlauben und erneut versuchen.';
    }
    if (error.name === 'NetworkError') return 'BLE-Verbindung fehlgeschlagen. Näher herangehen und erneut versuchen.';
    return error.message;
  }
  return 'Unbekannter Bluetooth-Fehler.';
}

// Minimal Web Bluetooth shapes (no extra dependency; exact UUID behavior only).
// eslint-disable-next-line @typescript-eslint/no-explicit-any
type AnyBluetooth = any;

function bluetooth(): AnyBluetooth | null {
  if (typeof navigator === 'undefined') return null;
  return (navigator as unknown as Record<string, AnyBluetooth>)['bluetooth'] ?? null;
}

// Explicit user-triggered device selection, as required by Web Bluetooth.
// Must be called from a user gesture (button click).
// eslint-disable-next-line @typescript-eslint/no-explicit-any
export async function requestNodeDevice(): Promise<any> {
  const bt = bluetooth();
  if (!bt) {
    throw new BluetoothTransportError('unsupported', bluetoothSupportMessage('unsupported'));
  }
  try {
    return await bt.requestDevice({
      filters: [{ services: [BLE_SERVICE_UUID] }],
      optionalServices: [BLE_SERVICE_UUID],
    });
  } catch (error) {
    if (error instanceof Error && (error.name === 'NotFoundError')) {
      throw new BluetoothTransportError('no-device', 'Kein Node gefunden. Node einschalten und erneut suchen.', error);
    }
    if (error instanceof Error && (error.name === 'NotAllowedError' || error.name === 'SecurityError')) {
      throw new BluetoothTransportError('permission-denied',
        'Bluetooth-Berechtigung verweigert. Bitte Zugriff erlauben und erneut versuchen.', error);
    }
    throw new BluetoothTransportError('connection-failed', describeBluetoothError(error), error);
  }
}

// Previously authorized devices (Chrome supports getDevices()). Correctness never
// depends on it; explicit selection always remains available.
export async function previouslyAuthorizedDevices(): Promise<unknown[]> {
  const bt = bluetooth();
  if (!bt || typeof bt.getDevices !== 'function') return [];
  try {
    return await bt.getDevices();
  } catch {
    return [];
  }
}

export interface GattOperations {
  read(characteristic: string): Promise<Uint8Array>;
  write(characteristic: string, value: Uint8Array): Promise<void>;
  notify(characteristic: string, timeoutMs: number): Promise<Uint8Array>;
}

// Maps SyncEngine frames onto GATT characteristics per messages.md.
// Read-only characteristics (Info/Owner/Challenge/Status) are served by read();
// write characteristics (Auth/NodeProof/Batch/Ack/Time) by write()+read()/notify().
export function characteristicForRequest(opcode: Opcode): string {
  switch (opcode) {
    case Opcode.HelloRequest: return BLE_CHAR_UUIDS.info;
    case Opcode.OwnerRequest: return BLE_CHAR_UUIDS.owner;
    case Opcode.ChallengeRequest: return BLE_CHAR_UUIDS.challenge;
    case Opcode.AuthRequest: return BLE_CHAR_UUIDS.auth;
    case Opcode.NodeProofRequest: return BLE_CHAR_UUIDS.nodeProof;
    case Opcode.StatusRequest: return BLE_CHAR_UUIDS.status;
    case Opcode.BatchRequest: return BLE_CHAR_UUIDS.batch;
    case Opcode.AckRequest:
    case Opcode.CompactRequest: return BLE_CHAR_UUIDS.ack;
    case Opcode.TimeRequest: return BLE_CHAR_UUIDS.time;
    default: throw new BluetoothTransportError('protocol', `Unbekannte Protokoll-Operation (${opcode}).`);
  }
}

function isReadOnlyRequest(opcode: Opcode): boolean {
  return opcode === Opcode.HelloRequest || opcode === Opcode.OwnerRequest ||
    opcode === Opcode.ChallengeRequest || opcode === Opcode.StatusRequest;
}

const GATT_OP_TIMEOUT_MS = 10_000;

// Frame channel: testable core of the adapter without real Bluetooth hardware.
// The SyncEngine talks write(frame)+read(); this routes to GATT operations.
export class FrameChannel {
  private pendingOpcode: Opcode | null = null;
  private pendingPayload: Uint8Array = new Uint8Array();

  constructor(private readonly gatt: GattOperations) {}

  async write(frame: Uint8Array): Promise<void> {
    const decoded = decodeFrame(frame);
    if (!decoded) throw new BluetoothTransportError('protocol', 'Ungültiger Protokoll-Frame.');
    this.pendingOpcode = decoded.opcode;
    this.pendingPayload = frame;
    if (isReadOnlyRequest(decoded.opcode)) return;
    const characteristic = characteristicForRequest(decoded.opcode);
    try {
      await this.gatt.write(characteristic, frame);
    } catch (error) {
      throw new BluetoothTransportError('disconnected', 'BLE-Verbindung abgebrochen (Schreiben fehlgeschlagen).', error);
    }
  }

  async read(): Promise<Uint8Array> {
    if (this.pendingOpcode === null) {
      throw new BluetoothTransportError('protocol', 'Protokollfehler: Lesen ohne vorheriges Schreiben.');
    }
    const opcode = this.pendingOpcode;
    this.pendingOpcode = null;
    const characteristic = characteristicForRequest(opcode);
    try {
      if (opcode === Opcode.BatchRequest) {
        // Batch pages may arrive as notification; fall back to read.
        try {
          return await this.gatt.notify(characteristic, GATT_OP_TIMEOUT_MS);
        } catch {
          return await this.gatt.read(characteristic);
        }
      }
      if (isReadOnlyRequest(opcode)) return await this.gatt.read(characteristic);
      return await this.gatt.read(characteristic);
    } catch (error) {
      if (error instanceof BluetoothTransportError) throw error;
      throw new BluetoothTransportError('disconnected', 'BLE-Verbindung abgebrochen (Lesen fehlgeschlagen).', error);
    }
  }
}

// Real Web Bluetooth transport behind the NodeTransport interface.
export class WebBluetoothTransport implements NodeTransport {
  private server: AnyBluetooth = null;
  private characteristics = new Map<string, AnyBluetooth>();
  private channel: FrameChannel | null = null;
  private disconnectListeners = new Set<() => void>();
  private connectedFlag = false;
  private disconnectHandler: (() => void) | null = null;

  constructor(private readonly device: AnyBluetooth) {}

  get deviceName(): string {
    return String(this.device?.name ?? this.device?.id ?? 'Unbekannter Node');
  }

  onConnectionLost(listener: () => void): () => void {
    this.disconnectListeners.add(listener);
    return () => this.disconnectListeners.delete(listener);
  }

  get connected(): boolean {
    return this.connectedFlag && Boolean(this.server?.connected);
  }

  async connect(): Promise<void> {
    const bt = bluetooth();
    if (!bt) throw new BluetoothTransportError('unsupported', bluetoothSupportMessage('unsupported'));
    try {
      // Remove any listener from a previous connection before adding a fresh
      // one so reconnects do not stack duplicate disconnect handlers.
      if (this.disconnectHandler) {
        this.device.removeEventListener?.('gattserverdisconnected', this.disconnectHandler);
      }
      this.device.addEventListener?.('gattserverdisconnected', this.handleDisconnect);
      this.disconnectHandler = this.handleDisconnect;
      this.server = await this.device.gatt.connect();
      const service = await this.server.getPrimaryService(BLE_SERVICE_UUID);
      this.characteristics.clear();
      for (const uuid of Object.values(BLE_CHAR_UUIDS)) {
        try {
          this.characteristics.set(uuid, await service.getCharacteristic(uuid));
        } catch {
          // Discovery must expose every v1 characteristic; fail fast below.
        }
      }
      const missing = Object.values(BLE_CHAR_UUIDS).filter(u => !this.characteristics.has(u));
      if (missing.length > 0) {
        throw new BluetoothTransportError('protocol',
          `Node meldet inkompatible Protokoll-Version (fehlende Characteristics: ${missing.length}).`);
      }
      // Batch notifications where supported; correctness does not depend on them.
      try {
        await this.characteristics.get(BLE_CHAR_UUIDS.batch)?.startNotifications?.();
      } catch {
        // Read fallback in FrameChannel covers this.
      }
      const ops: GattOperations = {
        read: async (uuid) => this.readValue(uuid),
        write: async (uuid, value) => this.writeValue(uuid, value),
        notify: async (uuid, timeoutMs) => this.waitNotify(uuid, timeoutMs),
      };
      this.channel = new FrameChannel(ops);
      this.connectedFlag = true;
    } catch (error) {
      if (error instanceof BluetoothTransportError) throw error;
      throw new BluetoothTransportError('connection-failed',
        'BLE-Verbindung fehlgeschlagen. Näher herangehen und erneut versuchen.', error);
    }
  }

  async disconnect(): Promise<void> {
    this.connectedFlag = false;
    try {
      if (this.disconnectHandler) this.device?.removeEventListener?.('gattserverdisconnected', this.disconnectHandler);
    } catch {
      // Ignore cleanup failures.
    }
    try {
      if (this.server?.connected) this.server.disconnect();
    } catch {
      // Disconnect is best-effort.
    }
    this.server = null;
    this.characteristics.clear();
    this.channel = null;
  }

  async write(message: Uint8Array): Promise<void> {
    if (!this.channel || !this.connected) {
      throw new BluetoothTransportError('disconnected', 'Keine BLE-Verbindung. Bitte erneut verbinden.');
    }
    await this.channel.write(message);
  }

  async read(): Promise<Uint8Array> {
    if (!this.channel || !this.connected) {
      throw new BluetoothTransportError('disconnected', 'Keine BLE-Verbindung. Bitte erneut verbinden.');
    }
    return this.channel.read();
  }

  private handleDisconnect = (): void => {
    this.connectedFlag = false;
    for (const listener of this.disconnectListeners) {
      try {
        listener();
      } catch {
        // Listener failures must not break disconnect handling.
      }
    }
  };

  private async readValue(uuid: string): Promise<Uint8Array> {
    const char = this.characteristics.get(uuid);
    if (!char) throw new BluetoothTransportError('protocol', 'Protokollfehler: Characteristic nicht gefunden.');
    const value = await withTimeout(char.readValue() as Promise<DataView>, GATT_OP_TIMEOUT_MS, 'Lesen zeitüberschritten.');
    const view = value as unknown as { buffer: ArrayBuffer; byteOffset: number; byteLength: number };
    return new Uint8Array(view.buffer.slice(view.byteOffset, view.byteOffset + view.byteLength));
  }

  private async writeValue(uuid: string, frame: Uint8Array): Promise<void> {
    const char = this.characteristics.get(uuid);
    if (!char) throw new BluetoothTransportError('protocol', 'Protokollfehler: Characteristic nicht gefunden.');
    // AuthRequest (JWT ~700-900 B + proof) relies on GATT long write where
    // available; the codec caps credentials at 2048 B and fragmentation never
    // changes codec bytes (see messages.md MTU/chunking).
    await withTimeout(char.writeValueWithResponse(new Uint8Array(frame)), GATT_OP_TIMEOUT_MS, 'Schreiben zeitüberschritten.');
  }

  private async waitNotify(uuid: string, timeoutMs: number): Promise<Uint8Array> {
    const char = this.characteristics.get(uuid);
    if (!char) throw new BluetoothTransportError('protocol', 'Protokollfehler: Characteristic nicht gefunden.');
    return new Promise<Uint8Array>((resolve, reject) => {
      const timer = window.setTimeout(() => {
        char.removeEventListener?.('characteristicvaluechanged', onValue);
        reject(new BluetoothTransportError('timeout', 'Node antwortet nicht (Timeout).'));
      }, timeoutMs);
      const onValue = (event: AnyBluetooth): void => {
        window.clearTimeout(timer);
        char.removeEventListener?.('characteristicvaluechanged', onValue);
        try {
          const value = event?.target?.value as unknown as { buffer: ArrayBuffer; byteOffset: number; byteLength: number };
          resolve(new Uint8Array(value.buffer.slice(value.byteOffset, value.byteOffset + value.byteLength)));
        } catch (error) {
          reject(new BluetoothTransportError('protocol', 'Ungültige Node-Antwort.', error));
        }
      };
      char.addEventListener?.('characteristicvaluechanged', onValue);
    });
  }
}

async function withTimeout<T>(promise: Promise<T>, timeoutMs: number, message: string): Promise<T> {
  let timer: number | undefined;
  try {
    return await Promise.race([
      promise,
      new Promise<never>((_, reject) => {
        timer = window.setTimeout(() => reject(new BluetoothTransportError('timeout', message)), timeoutMs);
      }),
    ]);
  } finally {
    if (timer !== undefined) window.clearTimeout(timer);
  }
}
