# Issue #169 — pragmatic smartphone visit time

`PHONE_ESTIMATE` uses ordinary authenticated BLE sync, the existing durable
IndexedDB evidence outbox and `TimeEvidenceService` / temporal projections.
It cannot initialize, grant or prolong security UTC or BLE authorization.
See [ADR 0017](adr/0017-boot-epoch-time-reconstruction.md) and
[ClockSnapshotV1](../protocol/ble/messages.md) for the normative contract.

## Behavior and compatibility

- Each snapshot-capable authenticated sync creates a fresh UUID anchor, bound
  to organization, Node, incarnation, boot and exact uint64 uptime. Two protected
  reads detect interruption/reboot; the phone timestamp follows the first read.
  BLE duration is a diagnostic, never a confidence or acceptance threshold.
- Phone rows are immutable/idempotent owner-reported business evidence in
  `phone_time_anchors`; strict signed-anchor schema/verification stays unchanged.
  IndexedDB uses the existing evidence table/outbox, with additive kind `2`.
  Phone rows have no Node evidence receipt or dependency on raw compaction.
- Same-boot estimates use phone UTC minus elapsed uptime. Differing offsets
  retain their full enclosing span. Reversed chronological timestamps or
  impossible chronology produce `CONFLICT`; no arbitrary correction threshold
  or favored anchor is used. Relative boot ordering and elapsed constraints
  retain conservative open/closed bounds across unanchored boots.
- Late uploads rebuild already-uploaded observations at read time. Original
  raw records, visit-gap-v1 history and historical attribution stay immutable.
  Estimated projections may contribute interval visits only under the existing
  whole-interval historical deployment and unambiguous gap rules.
- Older firmware keeps its existing flow. After confirmed claim commitment,
  snapshot-capable firmware gets its initial anchor through ordinary authenticated
  field sync. Failure retains the committed claim and asks for another read.
- Observation/visit projection versions are `boot-interval-v2` /
  `visit-interval-v2`. The UI shows `ca.` or an explicit estimated span, plus
  open boundaries/unknown/conflict states in the user's local timezone.

## Acceptance evidence

| Requirement | Automated coverage |
| --- | --- |
| Single boot, several reads, 1–3 days, later/multiple syncs, no precision/drift window | `TemporalProjectionEngineTest` phone cases |
| One reboot, multiple intermediate boots, relative ordering, one-sided/no absolute bounds | `TemporalProjectionEngineTest` phone and signed boot cases |
| Contradictory phone chronology and visible offset disagreement | `TemporalProjectionEngineTest` reversed chronology and full-span cases |
| BLE interruption, reboot/timer reset, wrong identity, full uint64, context switch | `phone-time.test.ts`, `sync-engine.test.ts`, `esp32-ble-gatt` |
| Offline reopen, later upload, lost response/retry, duplicate identity/payload | `time-evidence.test.ts`, `TimeEvidenceTest` |
| Claim completion precedes ordinary authenticated anchor capture; failed follow-up keeps claim | `provision-node.test.ts`, existing claim regression |
| Tenant/Node/incarnation/boot binding, strict schema/CSRF/session, concurrency | `TimeEvidenceTest` phone ingest cases |
| Late projection, historical move ambiguity, unchanged raw rows/quality | `TimeEvidenceTest` phone historical-visit case |
| Existing signed anchors and sharing gates | Existing `TimeEvidenceTest`, affected node/visits regression (including SharedCare/CrossOrg tests) |
| Persist before ACK, interrupted ACK retry, no phone Node receipt, no compaction loss | `sync-engine.test.ts`, `time-evidence.test.ts`, firmware BLE/auth/storage regression |
| Phone cannot set security UTC or bypass expiry/PoP | No UTC in snapshot request; protected Batch gates; `esp32-ble-gatt`, existing signed/bootstrap/authorization regression |
| Simple estimates, broad intervals, open bounds, timezone localization | `TemporalHistory.test.tsx`, temporal history API/Admin tests |

## Integrated review and repairs

Reviewed changed code, direct security/transport/persistence contracts and tests
for checked arithmetic, cross-boot inference, ownership/history, outbox retry
and ACK boundaries, signed security-time separation and ticket scope.

- Recheck monotonic validity after sampling: the ESP adapter can latch a timer
  discontinuity during `monotonic_ms()` itself. Added a firmware regression.
- Cache the enclosing phone offset span so thousands of syncs in one boot do
  not create repeated quadratic projection scans. Keep the signed-anchor capacity
  behavior and cover the existing total 4096-evidence read capacity.
- Explicitly reject Node receipts for phone-owned evidence and exclude signed
  budgets superseded by phone business time from projection provenance.
- Guard estimated display against absent time bounds. No confirmed security
  authorization changes are needed. A failed initial post-claim time capture
  returns an explicit pending warning while continuing setup of the committed
  claim, avoiding a misleading registration-failure/reclaim loop. No confirmed
  authorization, raw mutation, tenant/sharing or historical-attribution blocker
  remains after focused repair verification.

## Boundaries and physical acceptance

No account/session/credential invalidation (#170), FIELD_ALPHA security-clock
policy or offline authorization after reboot (#171) is changed. After reboot,
the existing signed security-clock bootstrap remains necessary before protected
sync. Phone capture does not weaken claim receipt, JWT/PoP, issuer pinning,
TimeCorrect or GATT permissions.

**Hardware validation: UNVERIFIED.** No physical Android/ESP32 BLE/power-loss
run or flash is claimed. #134/#13 own final device E2E acceptance; host firmware,
PWA and backend tests are software evidence. Real BLE latency and physical
claim/reboot/ACK/COMPACT behavior remain to be validated on hardware.
