// Separate lifecycles. No transition/transfer implementation belongs to #2.
export type NodeSyncState = 'idle' | 'connecting' | 'receiving' | 'persisting' | 'acknowledging' | 'complete' | 'failed';
export type BackendSyncState = 'idle' | 'waiting-for-network' | 'uploading' | 'complete' | 'failed';
// Node acknowledgement must follow durable local commit; backend acceptance is separate.
