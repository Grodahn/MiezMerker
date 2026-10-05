// Backend upload state machine (issue #8, ingest API from #9).
//
// Node→PWA and PWA→Backend are independent state machines. A successful Node
// sync never depends on backend/network availability; upload runs later when
// online and while the PWA is active. Explicit retry on next app open / online
// state is sufficient; Background Sync is not required for correctness.
//
// Uses the generated OpenAPI client and the #9 batch ingest API with batching,
// retry, idempotent confirmation handling (relies on #9 (node_id, sequence)
// idempotency), and clear separation:
// - safely copied from Node (durably stored locally)
// - acknowledged to Node (ACK high-watermark)
// - pending backend upload
// - successfully uploaded
// A failed backend upload never turns a successful Node sync into a failed
// field visit.

import { api } from '../api/client';
import { fetchCsrfToken } from '../platform/auth';
import type { CollectorObservationStore } from './observation-store';

export type BackendSyncState =
  | 'idle'
  | 'waiting-for-network'
  | 'uploading'
  | 'complete'
  | 'failed';

export interface UploadResult {
  state: BackendSyncState;
  uploaded: number;
  duplicates: number;
  conflicts: number;
  failed: number;
  message: string;
}

export interface UploaderOptions {
  batchSize?: number;
  maxRetries?: number;
  retryDelayMs?: number;
}

function clockToBackend(clockStatus: number): string {
  if (clockStatus === 0) return 'UNKNOWN';
  if (clockStatus === 1) return 'RTC_ONLY';
  return 'SYNCED';
}

export class BackendUploader {
  constructor(
    private readonly store: CollectorObservationStore,
    private readonly options: UploaderOptions = {},
  ) {}

  // Uploads pending observations for one organization. Offline leaves the
  // outbox untouched. Backend failure leaves records in the outbox for retry.
  async upload(organizationId: string): Promise<UploadResult> {
    if (typeof navigator !== 'undefined' && !navigator.onLine) {
      return { state: 'waiting-for-network', uploaded: 0, duplicates: 0, conflicts: 0, failed: 0,
        message: 'Kein Internet. Upload wartet auf Verbindung.' };
    }
    const pending = await this.store.pendingUploads(organizationId);
    if (pending.length === 0) {
      return { state: 'complete', uploaded: 0, duplicates: 0, conflicts: 0, failed: 0,
        message: 'Alle lokal gespeicherten Beobachtungen sind hochgeladen.' };
    }
    const batchSize = this.options.batchSize ?? 200;
    if (!Number.isInteger(batchSize) || batchSize < 1 || batchSize > 200) {
      throw new Error('Upload batch size must be between 1 and 200.');
    }
    let uploaded = 0;
    let duplicates = 0;
    let conflicts = 0;
    let failed = 0;
    let lastMessage = '';
    for (let offset = 0; offset < pending.length; offset += batchSize) {
      const batch = pending.slice(offset, offset + batchSize);
      const outcome = await this.uploadBatch(organizationId, batch.map(r => ({
        nodeId: r.nodeId, incarnation: r.incarnation, sequence: r.sequence,
        chipId: r.chipId, clockStatus: r.clockStatus,
        epochMs: r.epochMs, monotonicMs: r.monotonicMs, bootCounter: r.bootCounter,
      })));
      uploaded += outcome.uploaded;
      duplicates += outcome.duplicates;
      conflicts += outcome.conflicts;
      failed += outcome.failed;
      if (outcome.message) lastMessage = outcome.message;
    }
    const outstanding = failed + conflicts;
    if (outstanding > 0 && uploaded + duplicates === 0) {
      return { state: 'failed', uploaded, duplicates, conflicts, failed,
        message: lastMessage || `Backend-Upload fehlgeschlagen (${outstanding} Records ausstehend). Vor-Ort-Sync bleibt gültig.` };
    }
    if (outstanding > 0 && lastMessage) {
      return { state: 'failed', uploaded, duplicates, conflicts, failed, message: lastMessage };
    }
    if (outstanding > 0) {
      return { state: 'failed', uploaded, duplicates, conflicts, failed,
        message: `Teilweise hochgeladen (${uploaded + duplicates}), ${outstanding} ausstehend. Vor-Ort-Sync bleibt gültig.` };
    }
    return { state: 'complete', uploaded, duplicates, conflicts, failed,
      message: `Backend-Upload erfolgreich (${uploaded + duplicates} Records).` };
  }

  private async uploadBatch(
    organizationId: string,
    batch: Array<{
      nodeId: string; incarnation: string; sequence: string; chipId: string;
      clockStatus: number; epochMs: string | null; monotonicMs: string; bootCounter: number;
    }>,
  ): Promise<{ uploaded: number; duplicates: number; conflicts: number; failed: number; message?: string }> {
    const maxRetries = this.options.maxRetries ?? 3;
    const retryDelay = this.options.retryDelayMs ?? 800;
    let lastError: unknown = null;
    for (let attempt = 0; attempt <= maxRetries; attempt++) {
      try {
        const csrfToken = await fetchCsrfToken();
        const { data, error, response } = await api.POST('/api/v1/observations/ingest', {
          signal: AbortSignal.timeout(10_000),
          headers: { 'X-XSRF-TOKEN': csrfToken },
          body: {
            organizationId,
            observations: batch.map(r => ({
              nodeId: r.nodeId,
              sequence: r.sequence,
              chipId: r.chipId,
              clockStatus: clockToBackend(r.clockStatus),
              incarnation: r.incarnation,
              monotonicMs: r.monotonicMs,
              ...(r.epochMs !== null ? { observedAtMillis: r.epochMs } : {}),
              bootCounter: r.bootCounter,
            })),
          },
        });
        if (error || !data) {
          const status = (response as Response | undefined)?.status;
          // 4xx (except 429) is a definitive rejection — retrying cannot help
          // (e.g. membership/device disabled, tenant conflict). Fail fast.
          if (status !== undefined && status >= 400 && status < 500 && status !== 429) {
            const message = `Backend-Upload abgelehnt (HTTP ${status}). Vor-Ort-Sync bleibt gültig.`;
            for (const item of batch) {
              await this.store.markUploadResult(
                { nodeId: item.nodeId, incarnation: item.incarnation, sequence: item.sequence },
                false, message);
            }
            return { uploaded: 0, duplicates: 0, conflicts: 0, failed: batch.length, message };
          }
          throw new Error(`Backend-Upload abgelehnt (HTTP ${status ?? '?'}).`);
        }
        let uploaded = 0;
        let duplicates = 0;
        let conflicts = 0;
        let failed = 0;
        const results = data.results ?? [];
        for (let i = 0; i < batch.length; i++) {
          const item = batch[i];
          const result = results[i];
          const identityMatches = result && result.nodeId === item.nodeId && result.sequence === item.sequence;
          const status = identityMatches ? result?.status : undefined;
          const key = { nodeId: item.nodeId, incarnation: item.incarnation, sequence: item.sequence };
          if (status === 'CREATED') {
            await this.store.markUploadResult(key, true, null);
            uploaded++;
          } else if (status === 'DUPLICATE_IDENTICAL') {
            await this.store.markUploadResult(key, true, null);
            duplicates++;
          } else if (status === 'CONFLICT' || status === 'INVALID' || status === 'UNKNOWN_NODE') {
            // Kept locally with a visible error; siblings continue. Retry is
            // safe (idempotent) but the record needs operator attention.
            await this.store.markUploadResult(key, false, result?.message ?? status);
            if (status === 'CONFLICT') conflicts++;
            else failed++;
          } else {
            await this.store.markUploadResult(key, false, result?.message ?? 'Unbekannte Backend-Antwort.');
            failed++;
          }
        }
        return { uploaded, duplicates, conflicts, failed };
      } catch (e) {
        lastError = e;
        if (attempt < maxRetries) {
          await new Promise(r => setTimeout(r, retryDelay * (attempt + 1)));
        }
      }
    }
    // Network/backend failure: leave every record in the outbox for retry.
    // Records stay pending (not parked as failed) so the next online retry
    // picks them up; the error is returned, not persisted per record.
    const detail = `${lastError instanceof Error ? lastError.message : 'Backend nicht erreichbar.'} Vor-Ort-Sync bleibt gültig.`;
    try {
      await this.store.markPending(batch.map(item => ({
        nodeId: item.nodeId, incarnation: item.incarnation, sequence: item.sequence,
      })));
    } catch {
      // Best effort; records were already pending.
    }
    return { uploaded: 0, duplicates: 0, conflicts: 0, failed: batch.length, message: detail };
  }
}
