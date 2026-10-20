// @vitest-environment jsdom
import { afterEach, expect, test, vi } from 'vitest';
const mocks=vi.hoisted(()=>({ purge:vi.fn().mockResolvedValue(undefined),close:vi.fn(),listener:undefined as undefined|(()=>void) }));
vi.mock('./offline-store',()=>({ CollectorDatabase:class { close=mocks.close; } }));
vi.mock('./auth',()=>({ subscribeAuth:(f:()=>void)=>{ mocks.listener=f;return ()=>{ mocks.listener=undefined; }; } }));
vi.mock('../collector/sync-event-outbox',()=>({ SyncEventOutbox:class { purge=mocks.purge; } }));
import { startSyncEventRetention } from './sync-event-retention';
afterEach(()=>{ vi.useRealTimers(); });
test('retention runs without active login, on startup, auth, storage and hourly; cleanup detaches',async()=>{
  vi.useFakeTimers();const stop=startSyncEventRetention();
  expect(mocks.purge).toHaveBeenCalledTimes(1);
  mocks.listener!(); window.dispatchEvent(new StorageEvent('storage'));
  await vi.advanceTimersByTimeAsync(3600_000);
  expect(mocks.purge).toHaveBeenCalledTimes(4);expect(mocks.close).toHaveBeenCalledTimes(4);
  stop();window.dispatchEvent(new StorageEvent('storage'));await vi.advanceTimersByTimeAsync(3600_000);
  expect(mocks.purge).toHaveBeenCalledTimes(4);expect(mocks.listener).toBeUndefined();
});
