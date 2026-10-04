# Collector field validation (issue #8)

Target MVP environment: Android smartphone + current Chromium/Chrome with Web
Bluetooth, installed PWA (`/sync`, `start_url: /sync`), HTTPS in regular
operation (usual browser dev exceptions for local development only).

No native Android app, no Safari/iOS, no Firefox without Web Bluetooth, no
background BLE in the MVP.

## What is already proven automatically

- BLE codec golden vectors (`ble-codec.test.ts`) match
  `protocol/fixtures/ble-sync-v1.json` byte-for-byte.
- Sync engine (`sync-engine.test.ts`, `sync-extended.test.ts`):
  normal sync, 2000 records / multiple batches, disconnect mid-batch,
  reconnect with fresh challenge, ACK lost (idempotent resend), duplicate
  delivery (no local duplicates), conflicting payload rejected, app restart
  between receive and ACK (durable Dexie state after reopen), IndexedDB
  quota failure before ACK (no ACK sent), foreign organization (no protected
  reads), MEMBER blocked from claiming (client + server), ADMIN claim path,
  incompatible protocol version, node reboot during sync, UNCLAIMED never
  reaches authorization.
- Durable store (`observation-store.test.ts`): `(node_id, incarnation,
  sequence)` uniqueness, contiguous watermark `{100,101,103}` → ACK 101 only,
  restart persistence, upload/outbox state transitions.
- Backend upload (`backend-upload.test.ts`): batching, decimal-string 64-bit
  mapping to #9 ingest, retry, failure leaves outbox, offline leaves outbox
  untouched, failed upload never turns a successful Node sync into a failed
  field visit.
- Authorization (`authorization.test.ts`): valid credential reuse, online
  renewal, offline expiry with a clear “Node-Sync kann nicht starten” message.
- Transport (`web-bluetooth.test.ts`): exact GATT UUIDs, opcode→characteristic
  routing, read-only vs write characteristics, notify-with-read-fallback for
  Batch, German capability/permission errors.
- PWA shell: production build precaches the app shell; `/sync` reloads offline
  with durable IndexedDB (Playwright foundation spec).

## Practical Web Bluetooth procedure (real device, not mocked)

Perform on current Android + current Chrome/Chromium, PWA installed:

1. Connect/reconnect to a test NapfNode (or #7 simulator BLE node when
   available); verify explicit browser device selection.
2. Sync several thousand dummy records; verify progress, durable IndexedDB,
   `Fertig`, and ACK high-watermark on the node.
3. Interrupt BLE mid-batch (Bluetooth off/on, distance); reconnect and verify
   idempotent resume without duplicates.
4. Lose an ACK (reboot node after persist, before ACK response); verify resend
   of the same watermark succeeds.
5. Lock screen / send app to background mid-sync; document observed behavior
   (no background BLE claimed).
6. Enable airplane mode (no Internet); verify full Node→PWA sync + `Fertig`
   still succeeds and backend upload waits.
7. Restart PWA/tab between receive and ACK; verify resume from durable state.
8. Reuse a previously authorized node (Chrome `getDevices` where available);
   verify explicit selection still works when reuse is unavailable.
9. Expired/missing credential offline: verify clear “Node-Sync kann nicht
   starten” message; online renewal path.
10. Foreign organization node: verify only public owner metadata
    (“Dieser MiezMerker gehört …”) and no observation reads.
11. UNCLAIMED node: verify MEMBER blocked, ADMIN claim flow with physical
    claim-mode confirmation and idempotent backend retry.

Record Android version, Chrome version, node firmware version, record counts,
and any deviations. Do not claim robustness that was not practically observed.

## Status in this PR

Automated mocks above pass. **No physical BLE test node was available during
the agent run, so items 1–11 above are explicitly marked as awaiting
real-device validation rather than claimed as success.** The PWA implements
the exact GATT contract from #6, persist-before-ACK, contiguous watermark from
durable state, idempotent retry, and separate backend upload, ready for that
field validation.
