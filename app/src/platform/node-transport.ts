export interface NodeTransport {
  connect(): Promise<void>;
  disconnect(): Promise<void>;
  read(): Promise<Uint8Array>;
  write(message: Uint8Array): Promise<void>;
}

export function supportsWebBluetooth(): boolean { return 'bluetooth' in navigator; }

// Real GATT operations and authorization are implemented in subsequent tickets.
export class UnconfiguredNodeTransport implements NodeTransport {
  async connect(): Promise<void> { throw new Error('Node transport not configured'); }
  async disconnect(): Promise<void> {}
  async read(): Promise<Uint8Array> { throw new Error('Node transport not configured'); }
  async write(_message: Uint8Array): Promise<void> { throw new Error('Node transport not configured'); }
}
