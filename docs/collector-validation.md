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
  routing, read-only vs write characteristics, direct response reads for Batch,
  German capability/permission errors.
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
11. UNCLAIMED node: verify MEMBER blocked and ADMIN provisioning with the physical
    claim mode active. The PWA reads the node-signed advertisement, requests a
    backend receipt, persists it, delivers it over BLE and verifies CLAIMED owner
    metadata. Interrupt receipt delivery and retry using the saved receipt.

Record Android version, Chrome version, node firmware version, record counts,
and any deviations. Do not claim robustness that was not practically observed.

## Status in this PR

Automated mocks above pass. **No physical BLE test node was available during
the agent run, so items 1–11 above are explicitly marked as awaiting
real-device validation rather than claimed as success.** The PWA implements
the exact GATT contract from #6, persist-before-ACK, contiguous watermark from
durable state, idempotent retry, and separate backend upload, ready for that
field validation. The board shim must implement the Auth fragment envelope in
`protocol/ble/messages.md`; original JWT-sized writes cannot work through Web
Bluetooth's 512-byte attribute-write limit.

## PR #26 review corrections

- Device selection runs directly in the button's user gesture before asynchronous
  IndexedDB, persistent-storage or credential work.
- Auth frames stream in <=20-byte transport fragments and the shared firmware
  GATT router reassembles the exact original frame with bounded length, ordered
  offsets and disconnect reset. Batch responses use the documented read path and
  one record per request, avoiding lost notifications and oversized attributes.
- GATT discovery operations have timeouts and failed discovery cleans up the
  connection. Public hello/owner identities and versions must agree; a later
  hello must match the backend-pinned node before any credential is sent.
- Account and chosen ACTIVE organization metadata survive a cold offline launch.
  This snapshot grants no backend authorization; BLE still requires the stored,
  unexpired organization-bound credential and device-key proof. Logout/401 clears it.
- Ingest requests fetch a fresh CSRF token. Outbox recovery runs on open, online
  restoration and after a visit, includes failed/interrupted uploads, drains more
  than 5000 records, propagates storage failures and excludes unassigned or
  foreign-organization rows. HTTP upload returns separately from the BLE visit.
- Backend-pinned keys are cached before transfer so an interrupted first visit can
  resume offline. Cached keys are scoped to the selected organization. Foreign
  public owner metadata stops the flow before credential/protected requests.
- Empty terminal batches cannot hide records described by Status. Optional session
  metadata failure after durable persistence and ACK does not invalidate the copy.
- Claim receipts are retained in IndexedDB before BLE delivery. Optional v1
  characteristics `…5b0a/0b` carry the manager-signed advertisement and fragmented
  receipt. The PWA verifies the resulting CLAIMED owner metadata; no manual
  signature/key fields or peer-supplied timestamps are required.
- Pending HTTP uploads do not disable BLE visits. Completions from previous
  user/organization contexts cannot overwrite the current UI, and backend counts
  are recomputed from the selected organization's durable rows.
- Upload results with a mismatched event identity cannot mark records uploaded.
  Backend node-key lookups have a timeout so cached identity fallback also works
  on a network that reports online but does not deliver responses.

### Remaining acceptance blockers

The claim advertisement/receipt interface is now specified and implemented in
the shared codec, PWA and host-testable GATT router. The actual board shim must
expose both optional claim characteristics, bind the same NodeIdentityManager
used by Core and configure authorization from committed ownership on reconnect
as described in `protocol/ble/messages.md`. Physical Android/Chrome validation
(including Auth/receipt fragments on the actual board shim) remains required.
Host router tests and browser mocks cannot prove that physical integration.

### Review validation (2026-10-05)

- PWA: 152 Vitest tests passed; TypeScript/Vite production build passed.
- Generated API client: `api:check` passed without drift.
- Playwright: both browser integration tests passed against the local backend,
  including offline shell/outbox reload and restoration of cached account context.
- Firmware/simulator: all 54 CTest tests passed on the merged branch, including
  fragmented Auth handling and main's claim power-loss, node-isolation and expiry
  regressions. Simulator conflicts with main were resolved using its reviewed code.
- Claim tests cover persisted receipt delivery/retry, MEMBER rejection, physical
  mode rejection, atomic ownership refresh without a boot-counter increment,
  same-owner redelivery and foreign-owner rejection.
- Both codec implementations round-trip the same frozen claim advertisement
  vector from `protocol/fixtures/claim-advertisement-v1.hex`.
- Physical Android/BLE validation and actual board-shim wiring remain pending.

### Additional review fixes (2026-10-05)

- **P1: ACK inputs included unassigned/foreign cached rows.** A cached sequence
  could bridge a gap without belonging to the authenticated organization's
  upload queue. The production store now scopes watermark inputs to that
  organization. Regression cases seed unassigned/foreign sequence 2, receive
  sequences 1 and 3, and verify ACK stops at 1 with an incomplete result.
- **P2: Upload acknowledgements accepted missing event identities.** CREATED or
  DUPLICATE_IDENTICAL without nodeId/sequence could remove a row from retry.
  Both identifiers must now exactly match the submitted event. Three malformed
  success responses remain locally retryable; normal mocks use real identities.
- **P2: Credential resolution blocked public discovery.** An expired/missing
  credential prevented foreign owner display and discovering UNCLAIMED nodes for
  ADMIN provisioning. Public Hello/Owner now precedes credential and key work.
  Regressions verify both outcomes without any protected request or credential
  lookup; same-organization observations still require authorization.
- **P2: An in-flight upload swallowed a post-visit retry.** Its snapshot could
  omit freshly collected records while the completion effect returned early.
  Retry requests now queue one subsequent drain after the active upload finishes.
  A UI regression completes a BLE visit during an older upload and verifies the
  second upload runs without another visit, reconnect or manual retry.
- **P2: Collector HTTP prerequisites could hang indefinitely.** CSRF, device
  registration, credential issuance and claim reservation now abort after ten
  seconds. Ingest and claim deadlines start after CSRF resolves, preserving their
  full request window. Abort-aware tests stall each prerequisite and verify
  termination, retained outbox data and absence of a newly cached credential.
- **P2: Compaction failure undid an already successful visit in the UI.** After
  contiguous durable storage and confirmed ACK, a missing/invalid compact response
  now produces a maintenance warning alongside Fertig. Tests verify the durable
  watermark remains confirmed, the transport closes and the copy is not retried.

Validation: 152 Vitest tests, TypeScript/Vite production build, generated API
drift check, both Playwright tests against the local PostgreSQL-backed packaged
backend, and all 54 existing firmware/simulator CTest tests passed. CTest requires
access to the local LLVM runtime DLLs; running it outside the filesystem sandbox
resolved the initial Windows loader failures. This review changed no C++ sources.

### Second review fixes (2026-10-05)

- **P2: A timed-out connection attempt escaped cleanup.** The adapter now retains
  the GATT server before awaiting `connect()` and calls `disconnect()` even when
  `connected` is false, cancelling the browser's pending connection algorithm.
  A stalled-connect regression checks cancellation and listener cleanup.
- **P2: Discovery swallowed transport failures.** Characteristic discovery now
  stops immediately on network errors and timeouts, preserving the error kind.
  Only `NotFoundError` for the two optional claim characteristics is tolerated;
  missing required characteristics remain an incompatibility error. Tests use
  actual DOMException objects to cover browser error handling.
- **P2: Stale visits and claims continued after context changes or UI closure.**
  Each operation is bound to its initiating account, ACTIVE organization and
  UI cancellation signal; claims additionally require the ADMIN role throughout.
  Context changes disconnect the transport, prohibit subsequent authorization,
  ACK and receipt writes, and prevent reconnect retries. Returning to the same
  context cannot resurrect an old chooser or operation. Late results cannot
  overwrite the new view, clear its busy state or disconnect a replacement
  visit. A backend-issued receipt remains durably available for a later retry
  in the initiating organization. Regressions cover context changes, cancelled
  pending browser reads, cancellation after persistence but before ACK, receipt
  reservation races, stale UI completions and replacement-visit cleanup.
- **P2: Retry/failure results discarded durable progress.** Received counts now
  track unique persisted event identities across one visit's retries, so a lost
  ACK followed by an empty retry still reports the copied records without
  double-counting retransmissions. Failed visits retain partial durable counts
  and the last authenticated/confirmed watermark, including failed reconnects.
  A new visit resets both counters. Interrupted-transfer and lost-ACK regressions
  verify these results against the durable store.

Validation: 169 Vitest tests, TypeScript/Vite production build, generated API
drift check and both Playwright browser integration tests passed against the
local PostgreSQL-backed packaged backend. The previous firmware validation
(54 passing CTest tests) remains applicable; this review changed no C++ sources.
Physical Android/BLE validation and actual board-shim wiring remain pending.

### Final merge review fixes (2026-10-05)

- **P2: Returning to an organization resurrected obsolete upload UI results.**
  The upload completion check compared only the current account/organization
  identifiers. Switching away and back before completion made an obsolete result
  appear current and could skip the new context's outbox drain. Every context
  change now advances a generation; obsolete completions cannot update the UI
  and schedule a fresh drain when the collector is still mounted. Closing the
  collector also invalidates pending UI updates.
- **P2: Credential renewal did not update the collector header.** The visit
  reported its credential state but the header continued displaying its earlier
  cache result, including a missing/expired warning after successful renewal.
  Visit updates now refresh the header and invalidate older in-flight cache
  lookups. The regression resolves a pre-renewal lookup with no credential after
  renewal and verifies that it cannot restore the obsolete warning.

Both regressions failed before the corrections and pass afterward. Current main
(including PR #28) was merged without conflicts. Validation: 171 Vitest tests,
TypeScript/Vite production build, fresh generated API drift check, both Playwright
browser tests and all 54 firmware/simulator CTest tests passed. Playwright was
rerun outside the filesystem sandbox to allow Windows preview-process cleanup.
Physical Android/BLE validation and actual board-shim wiring remain pending;
merging this implementation does not complete issue #8's field acceptance.
