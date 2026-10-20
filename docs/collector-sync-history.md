# Collector-reported successful Node syncs (#85)

Purpose: only selecting a small recipient set for future feeding-site missing-cat
notices (#84/#86). No employee tracking, performance reports, GPS, cat/chip data,
photos, Bluetooth addresses or external registers. No push delivery is implemented.
Bluetooth proximity and a collector report do **not** prove physical attendance.
A modified authorized client can fabricate a report; there is no Node-signed
success/attendance certificate. This feature must never be described as one.

## Collection and migration

The existing #76 batch invokes `runFieldSync` once per Node. After the shared
SyncEngine has authenticated the Node/user, durably persisted observations and
required time evidence, and completed its ACK contract, the orchestrator records
one random event ID in the additive Dexie v5 `syncEvents` table, including for
zero observations. It commits before any complete/fertig display. No failed,
cancelled, partial, claiming or upload operation creates a report. There is no
new BLE request or change to time, claiming, firmware or ACK semantics.

On event-write failure, previously persisted observations/evidence and ACK are
preserved. The UI explicitly reports that the data copy succeeded but event
capture failed; it does not retry the engine or ACK. An actual process/storage
failure between ACK and event commit cannot be made atomic across Node and
IndexedDB. It produces no fabricated success; there is no recovery by guessing
that an old ACK was this person's completed visit. Browser storage eviction can
lose locally captured data, as with the existing collector outbox.

Event fields: random eventId, organizationId, ownerUserId, authenticated nodeId,
incarnation UUID, untrusted occurredAt in milliseconds, PHONE_ESTIMATE, OFFLINE_ESTIMATE or UNKNOWN.
App-device identity is deliberately omitted: it is optional, unnecessary for this
purpose, and the current key API does not supply a durable server device ID here.
`expiresAt` is local-only. No observation or evidence payload is copied. The small
`sync-event.ts` domain contract and clock-continuity function have no web/BLE
imports and can be reused during #177; Dexie and HTTP are adapters.

## Independent delivery and authorization

The existing collector open/online/after-batch drain uploads this queue separately
before observation/evidence ingest, even when there are no observations. Requests
are CSRF-protected session POSTs to `/api/v1/collector-sync-events`. Events remain
bound to their original user and organization across restarts, logout and retries.
Account/org changes latch cancellation, including switching away and back.
The body owner is checked against the authenticated session (not used as authority),
closing the shared-cookie race where abort arrives after the server receives a
request. The server independently checks ACTIVE user, membership, organization
and claimed Node ownership. There is no SYSADMIN bypass. Incarnation is reported
metadata from the authenticated Node, not independently verifiable current
incarnation: NodeDevice has no such authoritative field. It is immutable in the
idempotency comparison; no new incarnation registry is invented.

A matching event ID/payload is DUPLICATE_IDENTICAL without changing receipt time or
attribution; any changed field conflicts. Node-row locking serializes ordinary
retries with deployment edits. A cross-node event-ID collision returns conflict.
Responses must echo ID and recognized status before local deletion. Network errors,
401, 403 and conflicts retain the original row, never reassign it. After a known
account ban local rows are purged; otherwise inaccessible rows expire after 30 days.
Local maintenance runs on app start, auth/storage events and hourly even while
logged out. A closed PWA cleans on reopening, before delivery. Server rejection
never deletes observations/time evidence.

## Conservative historical attribution and ordering

`occurredAt` is client-reported/untrusted; `received_at` is server receipt, a
separate fact. Clock jumps within a visit mark time UNKNOWN. UNKNOWN, impossible
future phone time (>5 minutes ahead), pre-claim estimates or missing history do
not acquire a feeding site. A plausible recent report is bounded by server receipt
minus 5 minutes through receipt, clipped to the Node's server-side claim time.
This is an explicit plausibility policy for collector reports, **not verified
sync/attendance time**. Initial phone error or a dishonest client cannot be ruled
out; the API always exposes this provenance.

For reports captured offline (even with misleading phone UTC), or uploads delayed by more than 5 minutes, phone UTC is insufficient to choose a
side of a move: the window widens to the entire retained ownership interval
(max(receipt minus retention, claimedAt) through receipt). Thus delayed reports
resolve only when one historical deployment covers the whole window. No match
is UNRESOLVED, multiple intersecting deployments are AMBIGUOUS; neither is eligible
for recipient selection. A deployment ending exactly at the upper endpoint does
not cover it (half-open intervals). No current-site fallback and no client site ID.
Attribution is frozen at ingest; query eligibility rechecks that history still
covers the entire original window, excluding retrospective corrections without
reassigning a report. No RawObservation/DerivedVisit writes occur.

GET `/api/v1/organizations/{org}/feeding-sites/{site}/sync-recipients?limit=5`
requires ACTIVE tenant ADMIN; foreign/missing sites are hidden. The limit is 1..20.
The SQL filters currently ACTIVE users and memberships **before** deduplicating
across all Nodes and applying the limit. For each person, maxima of conservative
lower/upper bounds bracket their latest eligible report. Order is descending lower
bound, UUID for deterministic ties. Overlapping windows are chronologically
unordered; top-N is a conservative deterministic selection, not an assertion of
exact physical visit order. Returns only user IDs and bounds, no contact details,
raw event stream, general personnel history or global directory.

## Retention and operational limits

Server `SYNC_EVENT_RETENTION_DAYS` defaults to **30**, configurable to 1..30;
`SYNC_EVENT_PURGE_DELAY_MS` defaults to hourly. Expired reports are excluded from
queries immediately and physically purged on startup/hourly, including unresolvable
reports. Late uploads older than retention get 410. Idempotency is guaranteed
within the retention period; deleted expired payloads are not kept as permanent
identity tombstones. Local reports have a maximum 30-day lifetime; a shorter
server policy rejects older rows without transferring them to another account.
Configure backup expiry/deletion consistently before production; the application
cannot remove copies held in external backups. Purpose, admin access and this
retention default must be communicated to staff before deployment.

Physical BLE, real-device offline/reboot and Android validation: **UNVERIFIED**.
Automated fake IndexedDB, orchestration and HTTP/PostgreSQL CI cover the contracts
without claiming physical proof.
