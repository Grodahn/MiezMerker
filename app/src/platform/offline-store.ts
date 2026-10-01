import Dexie, { type Table } from 'dexie';

// Local transport envelope; this is not an HTTP DTO. Payload is opaque BLE data.
export interface PendingUpload {
  id: string;
  organizationId: string;
  appDeviceId: string;
  nodeId: string;
  protocolVersion: number;
  payload: Uint8Array;
  createdAt: number;
}
export interface Outbox {
  put(upload: PendingUpload): Promise<void>;
  pending(organizationId: string): Promise<PendingUpload[]>;
}
export class CollectorDatabase extends Dexie {
  uploads!: Table<PendingUpload, string>;
  constructor(name = 'miezmerker-collector') {
    super(name);
    this.version(1).stores({ uploads: 'id, organizationId, [organizationId+nodeId]' });
  }
}
export class DexieOutbox implements Outbox {
  constructor(private readonly database: CollectorDatabase) {}
  async put(upload: PendingUpload) { await this.database.uploads.put(upload); }
  pending(organizationId: string) {
    return this.database.uploads.where('organizationId').equals(organizationId).toArray();
  }
}
