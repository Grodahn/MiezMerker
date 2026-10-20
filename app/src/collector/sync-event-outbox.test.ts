// @vitest-environment jsdom
import 'fake-indexeddb/auto';
import Dexie from 'dexie';
import { beforeEach, afterEach, expect, test, vi } from 'vitest';
const mocks = vi.hoisted(() => ({ post: vi.fn(), csrf: vi.fn(), auth: vi.fn(), expired: vi.fn(), listeners: new Set<() => void>() }));
vi.mock('../api/client', () => ({ api: { POST: mocks.post } }));
vi.mock('../platform/auth', () => ({ getAuthState: mocks.auth, fetchCsrfToken: mocks.csrf,
  markSessionExpired: mocks.expired, subscribeAuth: (f: () => void) => { mocks.listeners.add(f); return () => mocks.listeners.delete(f); } }));
import { CollectorDatabase } from '../platform/offline-store';
import { SyncEventOutbox } from './sync-event-outbox';
import { syncTimeQuality, SYNC_EVENT_RETENTION_MS } from './sync-event';
import { publishAuthorization } from '../platform/authorization-status';

let db: CollectorDatabase;
const org='org-a', user='user-a';
const event = () => ({ eventId: crypto.randomUUID(), organizationId: org, ownerUserId: user,
  nodeId: crypto.randomUUID(), incarnation: crypto.randomUUID(), occurredAt: Date.now(), timeQuality: 'PHONE_ESTIMATE' as const });
function auth(owner=user, organizationId=org) {
  mocks.auth.mockReturnValue({ user: { userId: owner, memberships: [{ organizationId, status: 'ACTIVE' }] }, activeOrganizationId: organizationId });
  mocks.listeners.forEach(f => f());
}
beforeEach(async () => {
  localStorage.clear(); mocks.expired.mockReset(); mocks.post.mockReset(); mocks.csrf.mockReset().mockResolvedValue('csrf'); auth();
  Object.defineProperty(navigator,'onLine',{ value:true, configurable:true });
  db=new CollectorDatabase('sync-event-'+crypto.randomUUID()); await db.open();
});
afterEach(async () => { await db.delete(); mocks.listeners.clear(); });

test('additive migration preserves v4 data and never fabricates past success', async () => {
  const name='legacy-'+crypto.randomUUID(), legacy=new Dexie(name);
  legacy.version(4).stores({ uploads:'id, organizationId, [organizationId+nodeId]', observations:'[nodeId+incarnation+sequence], nodeId, [nodeId+uploadState], uploadState', syncSessions:'nodeId', nodeMeta:'nodeId', timeEvidence:'id, organizationId, [organizationId+nodeId]' });
  await legacy.table('uploads').add({ id:'legacy', organizationId:org }); legacy.close();
  const current=new CollectorDatabase(name); await current.open();
  expect(await current.uploads.count()).toBe(1); expect(await current.syncEvents.count()).toBe(0); await current.delete();
});
test('offline durable event survives restart, uploads exact retry identity then deletes only matching receipt', async () => {
  const e=event(); await new SyncEventOutbox(db).record(e,()=>{});
  Object.defineProperty(navigator,'onLine',{ value:false, configurable:true });
  expect(await new SyncEventOutbox(db).upload(org)).toBe(0); expect(mocks.post).not.toHaveBeenCalled();
  const name=db.name; db.close(); db=new CollectorDatabase(name); await db.open();
  Object.defineProperty(navigator,'onLine',{ value:true, configurable:true });
  mocks.post.mockRejectedValueOnce(new Error('lost response'));
  await expect(new SyncEventOutbox(db).upload(org)).rejects.toThrow('lost response');
  expect(await db.syncEvents.count()).toBe(1);
  mocks.post.mockResolvedValue({ data:{ eventId:e.eventId, status:'DUPLICATE_IDENTICAL' },response:{ status:200 } });
  expect(await new SyncEventOutbox(db).upload(org)).toBe(1);
  expect(mocks.post.mock.calls[0][1].body).toEqual(e);
  expect(mocks.post.mock.calls[1][1].body).toEqual(e);
  expect(await db.syncEvents.count()).toBe(0);
});
test('different account/org cannot adopt pending rows; switching away and back during CSRF cancels', async () => {
  await new SyncEventOutbox(db).record(event(),()=>{});
  auth('user-b'); expect(await new SyncEventOutbox(db).upload(org)).toBe(0);
  auth(user,'org-b'); expect(await new SyncEventOutbox(db).upload('org-b')).toBe(0);
  auth(); mocks.csrf.mockImplementationOnce(async () => { auth('user-b'); auth(); return 'csrf'; });
  expect(await new SyncEventOutbox(db).upload(org)).toBe(0);
  expect(mocks.post).not.toHaveBeenCalled(); expect(await db.syncEvents.count()).toBe(1);
});
test('mismatched receipt/conflicting or revoked request preserves bound row until expiry', async () => {
  const e=event(); await new SyncEventOutbox(db).record(e,()=>{});
  mocks.post.mockResolvedValueOnce({ data:{ eventId:'wrong',status:'CREATED' },response:{ status:200 } });
  await expect(new SyncEventOutbox(db).upload(org)).rejects.toThrow('noch nicht');
  mocks.post.mockResolvedValueOnce({ error:{},response:{ status:403 } });
  await expect(new SyncEventOutbox(db).upload(org)).rejects.toThrow('noch nicht');
  expect(await db.syncEvents.count()).toBe(1);
  await db.syncEvents.update(e.eventId,{ expiresAt:Date.now()-1 }); await new SyncEventOutbox(db).purge();
  expect(await db.syncEvents.count()).toBe(0);
});
test('confirmed disabled identity is purged, cancellation during write rolls back', async () => {
  await new SyncEventOutbox(db).record(event(),()=>{});
  publishAuthorization(user,true,[],[org]); await new SyncEventOutbox(db).purge();
  expect(await db.syncEvents.count()).toBe(0);
  let checks=0;
  await expect(new SyncEventOutbox(db).record(event(),()=>{ if(++checks===2) throw new Error('cancelled'); })).rejects.toThrow('cancelled');
  expect(await db.syncEvents.count()).toBe(0);
});
test('minimal event fields, finite local retention and clock-jump detection', async () => {
  const e=event(); await new SyncEventOutbox(db).record(e,()=>{});
  expect(Object.keys((await db.syncEvents.get(e.eventId))!).sort()).toEqual([...Object.keys(e),'expiresAt'].sort());
  expect((await db.syncEvents.get(e.eventId))!.expiresAt-e.occurredAt).toBeLessThanOrEqual(SYNC_EVENT_RETENTION_MS+1000);
  expect(syncTimeQuality(10000,12000,2000)).toBe('PHONE_ESTIMATE');
  expect(syncTimeQuality(10000,12000,2000,false)).toBe('OFFLINE_ESTIMATE');
  expect(syncTimeQuality(10000,999999,2000)).toBe('UNKNOWN');
  expect(syncTimeQuality(10000,9000,2000)).toBe('UNKNOWN');
});

test.each([400, 403, 404, 409, 410])('HTTP %i rejection retains the original but does not strand valid sibling events', async status => {
  const rejected = { ...event(), eventId: 'a-rejected' }, valid = { ...event(), eventId: 'b-valid' };
  await new SyncEventOutbox(db).record(rejected,()=>{});
  await new SyncEventOutbox(db).record(valid,()=>{});
  mocks.post.mockResolvedValueOnce({ error:{}, response:{ status } })
    .mockResolvedValueOnce({ data:{ eventId:valid.eventId,status:'CREATED' },response:{ status:200 } });
  await expect(new SyncEventOutbox(db).upload(org)).rejects.toThrow('noch nicht bestätigt');
  expect(mocks.post).toHaveBeenCalledTimes(2);
  expect((await db.syncEvents.toArray()).map(row=>row.eventId)).toEqual([rejected.eventId]);
  expect(mocks.post.mock.calls[0][1].body).toEqual(rejected);
  expect(mocks.post.mock.calls[1][1].body).toEqual(valid);
});
test('401 reports failure rather than a completed drain, stops requests and retains both events', async()=>{
  await new SyncEventOutbox(db).record({ ...event(),eventId:'a' },()=>{});
  await new SyncEventOutbox(db).record({ ...event(),eventId:'b' },()=>{});
  mocks.post.mockResolvedValueOnce({ error:{},response:{ status:401 } });
  await expect(new SyncEventOutbox(db).upload(org)).rejects.toThrow('Sitzung abgelaufen');
  expect(mocks.expired).toHaveBeenCalledTimes(1);expect(mocks.post).toHaveBeenCalledTimes(1);
  expect(await db.syncEvents.count()).toBe(2);
});
test.each([429,503])('HTTP %i stops transient retries without losing pending siblings',async status=>{
  await new SyncEventOutbox(db).record({ ...event(),eventId:'a' },()=>{});
  await new SyncEventOutbox(db).record({ ...event(),eventId:'b' },()=>{});
  mocks.post.mockResolvedValueOnce({ error:{},response:{ status } });
  await expect(new SyncEventOutbox(db).upload(org)).rejects.toThrow('noch nicht bestätigt');
  expect(mocks.post).toHaveBeenCalledTimes(1);expect(await db.syncEvents.count()).toBe(2);
});
test('event expiring during CSRF acquisition is purged without being sent',async()=>{
  const e=event();await new SyncEventOutbox(db).record(e,()=>{});
  await db.syncEvents.update(e.eventId,{ expiresAt:Date.now()+1000 });
  const now=Date.now();const clock=vi.spyOn(Date,'now');
  try {
    mocks.csrf.mockImplementationOnce(async()=>{ clock.mockReturnValue(now+2000);return 'csrf'; });
    expect(await new SyncEventOutbox(db).upload(org)).toBe(0);
    expect(mocks.post).not.toHaveBeenCalled();expect(await db.syncEvents.count()).toBe(0);
  } finally { clock.mockRestore(); }
});
