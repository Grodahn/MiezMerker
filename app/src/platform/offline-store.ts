import Dexie, { type Table } from 'dexie';

// Local transport envelope; this is not an HTTP DTO. Payload is opaque BLE data.
export interface PendingUpload {
  // Stable, globally unique batch identity; its envelope must not change on retry.
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
  async put(upload: PendingUpload): Promise<void> {
    await this.database.transaction('rw', this.database.uploads, async () => {
      const existing = await this.database.uploads.get(upload.id);
      if (existing) {
        const fields = ['organizationId', 'appDeviceId', 'nodeId', 'protocolVersion', 'createdAt'] as const;
        const identical = fields.every(field => existing[field] === upload[field])
          && existing.payload.length === upload.payload.length
          && existing.payload.every((byte, index) => byte === upload.payload[index]);
        if (!identical) throw new Error(`Conflicting upload identity: ${upload.id}`);
        return;
      }
      await this.database.uploads.add(upload);
    });
  }
  pending(organizationId: string) {
    return this.database.uploads.where('organizationId').equals(organizationId).toArray();
  }
}
