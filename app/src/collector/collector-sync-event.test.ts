// @vitest-environment jsdom
import 'fake-indexeddb/auto';
import { beforeEach, afterEach, expect, test, vi } from 'vitest';
const mocks=vi.hoisted(()=>({ auth:vi.fn() }));
vi.mock('../platform/auth',()=>({ getAuthState:mocks.auth, subscribeAuth:()=>()=>{} }));
vi.mock('./authorization',()=>({ resolveCredential:async()=>({ credential:'credential',renewed:false }), CredentialError:class extends Error {} }));
vi.mock('../platform/node-identity',()=>({ loadTrustedNodeIdentity:async(nodeId:string)=>({ nodeId,publicKeyX:'x',publicKeyY:'y' }), NodeIdentityError:class extends Error {} }));
import { runBatchSync } from './batch-sync';
import { runFieldSync } from './collector-sync';
import { CollectorDatabase } from '../platform/offline-store';
import { OfflineIdentity } from '../platform/offline-identity';
import { SyncEngine } from './sync-engine';
import { SyncEventOutbox } from './sync-event-outbox';
import { encodeFrame,decodeFrame,encodeHelloPublic,encodeOwnerResponse,Opcode } from './ble-codec';
const node='44444444-4444-4444-8444-444444444444', inc='55555555-5555-4555-9555-555555555555';
const org='11111111-1111-4111-8111-111111111111';
let db:CollectorDatabase;
let reply:Uint8Array=new Uint8Array();
const transport=(nodeId=node)=>({ connect:async()=>{},disconnect:async()=>{},read:async()=>reply,write:async(bytes:Uint8Array)=>{
  const op=decodeFrame(bytes)!.opcode;
  reply=encodeFrame({ version:1,opcode:op===Opcode.HelloRequest?Opcode.HelloPublic:Opcode.OwnerResponse,
    payload:op===Opcode.HelloRequest?encodeHelloPublic({ serverVer:1,caps:0x1f,nodeId,incarnation:inc,firmwareVersion:'test',claimState:1,clockStatus:2 }):
      encodeOwnerResponse({ nodeId,claimState:1,organizationId:org,organizationSlug:'org',organizationName:'Org',publicContact:'' }) });
} });
beforeEach(async()=>{
  mocks.auth.mockReturnValue({ user:{ userId:'user-a',memberships:[{ organizationId:org,status:'ACTIVE',role:'MEMBER' }] },activeOrganizationId:org });
  vi.spyOn(OfflineIdentity.prototype,'keys').mockResolvedValue({} as never);
  Object.defineProperty(navigator,'onLine',{ value:true, configurable:true });
  db=new CollectorDatabase(); await db.open(); await db.syncEvents.clear();
});
afterEach(async()=>{ await db.delete(); vi.restoreAllMocks(); });
test.each([0,2])('complete individual sync with %i observations persists before success display',async count=>{
  vi.spyOn(SyncEngine.prototype,'sync').mockResolvedValue({ ok:true,recordsReceived:count,watermark:BigInt(count) });
  let persistedAtSuccess=false;
  const add=vi.spyOn(SyncEventOutbox.prototype,'record');
  const result=await runFieldSync({ createTransport:()=>transport(),onUpdate:v=>{ if(v.fertig) persistedAtSuccess=add.mock.settledResults[0]?.type==='fulfilled'; } });
  expect(result.fertig).toBe(true); expect(persistedAtSuccess).toBe(true);
  const rows=await db.syncEvents.toArray(); expect(rows).toHaveLength(1);
  expect(rows[0]).toMatchObject({ ownerUserId:'user-a',organizationId:org,nodeId:node,incarnation:inc });
});
test('failed/incomplete sync creates no event',async()=>{
  vi.spyOn(SyncEngine.prototype,'sync').mockResolvedValue({ ok:false,error:'incomplete: gap',recordsReceived:1,watermark:0n });
  const result=await runFieldSync({ createTransport:()=>transport(),onUpdate:()=>{} });
  expect(result.fertig).toBe(false); expect(await db.syncEvents.count()).toBe(0);
});
test('abort/context change after engine prevents success event',async()=>{
  const controller=new AbortController();
  vi.spyOn(SyncEngine.prototype,'sync').mockImplementation(async()=>{ controller.abort();return { ok:true,recordsReceived:0,watermark:0n }; });
  await expect(runFieldSync({ createTransport:()=>transport(),signal:controller.signal,onUpdate:()=>{} })).rejects.toThrow('abgebrochen');
  expect(await db.syncEvents.count()).toBe(0);
});
test('event storage failure never reruns engine/ACK and never claims captured success',async()=>{
  const engine=vi.spyOn(SyncEngine.prototype,'sync').mockResolvedValue({ ok:true,recordsReceived:0,watermark:0n });
  vi.spyOn(SyncEventOutbox.prototype,'record').mockRejectedValue(new Error('quota'));
  const result=await runFieldSync({ createTransport:()=>transport(),onUpdate:()=>{} });
  expect(engine).toHaveBeenCalledTimes(1); expect(result.fertig).toBe(false);
  expect(result.nodeMessage).toContain('nicht dauerhaft erfasst'); expect(await db.syncEvents.count()).toBe(0);
});

test('batch records each successful Node once, preserving partial success on abort',async()=>{
  const engine=vi.spyOn(SyncEngine.prototype,'sync').mockResolvedValue({ ok:true,recordsReceived:0,watermark:0n });
  const controller=new AbortController();
  const inputs=[node,'66666666-6666-4666-8666-666666666666','77777777-7777-4777-8777-777777777777'].map(id=>({ browserId:id,browserLabel:'',device:id }));
  const result=await runBatchSync(inputs,{ createTransport:device=>transport(device as string),signal:controller.signal,
    onNodeDone:i=>{ if(i===1) controller.abort(); } });
  expect(result.map(r=>r.kind)).toEqual(['success','success','cancelled']);
  expect(engine).toHaveBeenCalledTimes(2);
  const rows=await db.syncEvents.toArray(); expect(rows).toHaveLength(2);
  expect(new Set(rows.map(e=>e.eventId)).size).toBe(2);
  expect(new Set(rows.map(e=>e.nodeId)).size).toBe(2);
});
