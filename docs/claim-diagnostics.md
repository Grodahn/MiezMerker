# Claim diagnostics (#196)

Claim diagnostics use the existing error catalog, session/CSRF protection,
tenant gates, bounded `NodeTimeRateLimiter`, and SLF4J/Logback output. They are
telemetry, never authority for ownership, security time, receipt delivery or ACK.
No BLE message or firmware implementation changes are involved.

## Enable and find an attempt

Set `CLAIM_DIAGNOSTICS_DETAILED=true` on the backend in development/staging.
Restart the backend. This enables positive milestones as well as errors at INFO;
`logging.level.org.miezmerker.backend.diagnostics.ClaimDiagnostics=OFF` disables
event output. The default is `false`: only unsuccessful events are logged.
Startup policy/freshness and UTC operator-attestation messages remain visible.
An attestation is configuration, not live NTP health or proof of phone UTC.

Use **Diagnose-ID kopieren** in the Sync screen after registration/preflight.
If clipboard access is unavailable, select the displayed ID. **Lokalen
Diagnoseexport anzeigen** provides a compact JSON fallback, explicitly marked
`delivery: not-confirmed`; HTTP acceptance never proves durable log retention.
The export contains no node, organization or account identifiers.

For a captured backend stdout log:

```powershell
rg 'claim_diagnostic.*attemptId=01234567-89ab-4cde-8123-456789abcdef' backend.log
```

Filter by `attemptId`, `source`, `phase`, `status`, `code`, `sequence` and
`durationMs`. Server `utc` is the time the event was logged. Client `elapsedMs`
is monotonic time since the attempt began; use client sequence for ordering
because preflight and completion batches can arrive after backend events.
Client policy, capabilities and protocol version are explicitly `untrusted=true`.
Protocol version is available even when firmware has no usable version label;
arbitrary firmware labels are deliberately excluded.

## Example (illustrative, not hardware evidence)

Every row below carries the same random UUIDv4 `attemptId`:

| Source | Phase | Status/code | Meaning |
| --- | --- | --- | --- |
| pwa | connection, hello | ok / OK | Existing BLE connection/public hello completed |
| pwa | policy | ok / OK | Local configured policy matches capabilities |
| pwa | alpha_clock | started, ok / OK | Existing Alpha exchange accepted |
| pwa | claim_advert | started, ok / OK | Existing advertisement exchange completed |
| pwa | local_signature | ok / OK | Preflight verified the node signature |
| pwa | preflight | ok / OK | No ownership reservation yet |
| pwa | backend_claim | started / OK | Claim POST carries `X-MM-Claim-Attempt` |
| backend | authorization_csrf, active_admin, organization | ok / OK | Server authorization gates completed |
| backend | claim_reservation, identity_time_policy, ownership_binding | ok / OK | Lock, proof/freshness and owner binding checked |
| backend | receipt_issuance, transaction_completion, complete | ok / OK | Receipt issued and transactional request completed |
| pwa | backend_claim, receipt_persisted | ok / OK | Receipt durably stored before delivery |
| pwa | receipt | ok / OK | Firmware receipt acknowledgement received |
| pwa | node_claimed, complete | ok / OK | Public committed owner/state verified |

The signed path instead observes `time_challenge` and `time_proof`; the time
API carries the same header. Server phases additionally identify membership,
challenge verification, ownership binding, issuer availability, UTC attestation
and proof issuance. `idempotent_retry` distinguishes same-owner/key redelivery.
The backend additionally records internal catalog codes for stale proofs
(`MM-BLE-013`) and invalid signatures (`MM-CLAIM-005`), while preserving the
neutral public 403 response and its public event code.

## Failure interpretation

| Event/code | Interpretation |
| --- | --- |
| policy / MM-CLOCK-005 | PWA/firmware policy mismatch; no fallback |
| alpha_clock / MM-CLOCK-007 | Alpha clock exchange rejected |
| time challenge/proof / MM-CLOCK-001..004 | Bootstrap unavailable, malformed, rejected or unconfirmed |
| claim_advert / MM-BLE-012 | Firmware reports ClockUnavailable |
| claim_advert / MM-BLE-017 | Firmware reports InvalidState |
| claim_advert / MM-BLE-002 | Malformed Error frame or unexpected protocol response |
| claim_advert / MM-CLAIM-004 | Advertisement response payload malformed |
| local_signature / MM-CLAIM-005 | Local signature verification failed |
| backend_claim / MM-BACKEND-403 | Neutral authorization/proof denial; server phase distinguishes the gate |
| backend_claim / MM-API-002, MM-API-003 | Claim conflict / unavailable; API registry code preserved in UI and telemetry |
| receipt or confirmation / MM-CLAIM-006 | Commit may have happened; retain receipt and recover authoritative state |
| connection/exchange / MM-BLE-001, MM-BLE-006 | BLE disconnect/connection failure or timeout |

The current firmware's ClaimAdvertisement handler uses **InvalidState** for
both an unready identity and unavailable trusted time. Telemetry preserves that
status; it cannot distinguish causes the unchanged wire protocol does not expose.
An exchange `ok` confirms framing/opcode; later decoder/signature/state failures
remain separate events and take precedence over an intermediate success.

## Bounds and security

* `POST /api/v1/nodes/claim-diagnostics`: authenticated ACTIVE ADMIN of the
  submitted active organization, ordinary CSRF token, JSON only, at most
  12 KiB including chunked bodies, 1..32 events. No node ID is accepted, so
  client telemetry cannot assert an authoritative node/tenant binding.
* Exact field/code/phase/status whitelist, bounded integers, duplicate JSON
  keys and trailing tokens rejected. No free text, URLs, proof bytes, secrets,
  RFID, contact details or raw identifiers in log events. Invalid batches are
  completely rejected before logging. Client assertions remain untrusted even
  when their author is authorized. Correlation IDs are not authentication.
* Reuses per-process limits: 12 requests/user/minute and 60/org/minute, charged
  for invalid attempts too (organization budget after authorization). The
  existing limiter retains at most 10,000 windows. A gateway must enforce
  aggregate/unauthenticated request limits across replicas.
* One current in-memory attempt, 64 events, available for at most 15 monotonic minutes
  when accessed. New preflight/retry replaces it. No IndexedDB/offline outbox.
  Organization/account changes and unverified cross-tab authentication-epoch
  changes discard queued data and abort in-flight uploads. A confirmed
  same-account/organization session refresh preserves correlation despite
  rotating the existing auth epoch. The server independently checks membership.
* Upload is scheduled after returning from orchestration, never awaited by
  claiming. At most 32 events per POST, four POSTs total per attempt, one retry
  per failed flush, 2-second upload deadline. No periodic/background retry loop.
  Exhaustion/offline leaves the local export available. A CSRF lookup may
  outlive that deadline, but its late result cannot initiate an expired POST.
* Backend diagnostic log I/O uses Logback's native AsyncAppender: 256 entries per output,
  `neverBlock=true`, no caller stack capture. Full queues drop diagnostic
  events instead of stalling claiming. There is no new diagnostic database or
  reader API. Access is limited to existing operator log access. Apply the
  existing stdout collector's short dev/staging retention (recommended 24h),
  and disable detailed mode after diagnosis; this change creates no retention
  service or independent tracking storage. Existing `logging.file.name`/
  `logging.file.path` output is preserved; diagnostics use a separate bounded,
  nonblocking queue for that file. No default file is created when unset.
  Diagnostic request bodies are size-limited and read before opening the short
  read-only transaction used for membership checks, so slow uploads cannot hold
  a claim database connection.

Actual ESP32 and Android hardware validation: **UNVERIFIED**. Automated browser
tests use a protocol-faithful GATT mock; they cannot establish radio behavior.
