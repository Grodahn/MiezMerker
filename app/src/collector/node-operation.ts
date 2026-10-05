import { getAuthState, subscribeAuth } from '../platform/auth';
import type { NodeTransport } from '../platform/node-transport';

export class NodeOperationCancelledError extends Error {
  constructor() {
    super('Node-Vorgang abgebrochen: Benutzer oder Organisation hat sich geändert. Bitte erneut starten.');
    this.name = 'NodeOperationCancelledError';
  }
}

// A visit/claim owns its initiating account and organization until cleanup.
// Changing away and back still cancels it; hiding stale UI updates alone would
// otherwise let the old operation continue sending authorization or ACKs.
export function bindNodeOperation(
  transport: NodeTransport,
  userId: string,
  organizationId: string,
  signal?: AbortSignal,
  requiredRole?: 'ADMIN',
) {
  let cancelled = false;
  let cancellationCleanup: Promise<void> | undefined;
  const matches = () => {
    const state = getAuthState();
    const membership = state.user?.memberships.find(m => m.organizationId === organizationId && m.status === 'ACTIVE');
    return state.user?.userId === userId && state.activeOrganizationId === organizationId &&
      Boolean(membership) && (!requiredRole || membership?.role === requiredRole);
  };
  const cancel = () => {
    if (cancelled) return;
    cancelled = true;
    cancellationCleanup = transport.disconnect().catch(() => {});
  };
  const assertActive = () => {
    if (cancelled || signal?.aborted || !matches()) throw new NodeOperationCancelledError();
  };
  const unsubscribe = subscribeAuth(() => { if (!matches()) cancel(); });
  signal?.addEventListener('abort', cancel, { once: true });
  const checked = async <T>(operation: () => Promise<T>): Promise<T> => {
    assertActive();
    try {
      const result = await operation();
      assertActive();
      return result;
    } catch (error) {
      // disconnect() rejects pending browser algorithms with Abort/NetworkError.
      // Preserve context cancellation as terminal rather than a reconnectable loss.
      assertActive();
      throw error;
    }
  };
  return {
    assertActive,
    transport: {
      connect: () => checked(() => transport.connect()),
      // Cancellation already disconnected this visit. Late cleanup must not
      // disconnect a newer visit to the same browser GATT server.
      disconnect: () => cancelled ? cancellationCleanup! : transport.disconnect(),
      read: () => checked(() => transport.read()),
      write: (frame: Uint8Array) => checked(() => transport.write(frame)),
    } satisfies NodeTransport,
    async close() {
      unsubscribe();
      signal?.removeEventListener('abort', cancel);
      try {
        if (cancelled) await cancellationCleanup;
        else await transport.disconnect();
      } catch { /* Cleanup must not mask the operation result. */ }
    },
  };
}
