# Bowl numbers and BLE names (#178)

## Contract and architecture review (0)

UUID, device key, incarnation, observation keys, foreign keys and tenant access
remain the technical identity. A factory-new device first exists in the backend
when `NodeController.claim` commits its CLAIMED row; pre-registered UNCLAIMED
rows can already exist. The existing claim lock serializes ownership decisions.
Same-owner/key retries return receipts without allocating another number. Backend
commit and device receipt acceptance are separate: the receipt is retryable and
the device commits ownership, receipt and bowl metadata in one identity blob.

The current main includes #169 (phone observation anchors), #170 (cooperative
revocation), and #171 (explicit field-alpha phone security clock). None of those
clock, authentication, outbox or persist-before-ACK contracts changes here. The
remaining open #73 concerns admin naming/assignment; this change adds number
display to existing views without changing assignments or editing permissions.

## Database

V21 backfills by `(created_at, node_id)` including deterministic timestamp ties.
Its window rank is computed once and applied by MERGE, avoiding a per-node
full-table count (quadratic work during an upgrade).
The positive INTEGER sequence consumes the backfilled range and supplies new
numbers through a column default. Allocation is global, concurrency safe and
nontransactional on PostgreSQL. Rollbacks/deletion leave gaps; the sequence never
cycles. INT32_MAX exhaustion fails the insert instead of wrapping or reusing a
number. UUID is still the primary key. NOT NULL, UNIQUE and positive CHECK
constraints apply. There is no API setter for the number; Hibernate never writes
it. V22 additionally rejects number changes with a PostgreSQL trigger. H2 tests
exercise the portable schema/JPA path; PostgreSQL CI exercises the production
trigger. Operators must not reset the sequence or manually insert old numbers.

JPA saves of a new UUID entity use merge: the returned managed entity must be
used after flush so the database-generated number reaches the signed receipt.
Authorized NodeView responses include `bowlNumber`; the public owner hint does
not gain a new backend identifier field.

## Transport and trust

Claim receipts add optional signed `bowl_number` and `display_name` claims to the
existing ES256 claim envelope. Independently pinned issuer verification, node
key/UUID, organization and validity checks still precede atomic claim acceptance.
Old receipts without these claims remain readable and have no invented number.
If the optional name would exceed the existing 4096-byte receipt budget, it is
omitted from that receipt and delivered in full by the first authenticated online
sync. The assigned number remains in the receipt.
If owner metadata still makes the final receipt exceed 4096 bytes, claiming
returns HTTP 422 and rolls back the transaction. Shorten the organization's
public name/contact and retry; no undeliverable new ownership is committed.

BLE v1 adds capability `0x0100`, MetadataRequest `0x27`, MetadataResponse `0x28`.
The request payload is node UUID (16 bytes), organization UUID (16 bytes),
positive int32 number (4 little-endian bytes), then the complete UTF-8 display
name (0..400 bytes, no NUL, strict UTF-8). The ordinary frame version is still 1.
Success payload is one zero byte. Invalid/binding/number/storage failures return
the existing error frame. A nonzero previously stored number cannot change.

The request uses the existing protected Batch characteristic. MTU-23 writes use
the existing bounded transport envelope with marker `0x50`, second byte `0x4d`,
version 1, start flag, uint16 total and offset, and up to 12 data bytes per write.
The total frame is 40..440 bytes. Authorization is checked for every fragment
and again on dispatch. Disconnect/new challenge/timeout or an invalid fragment
cannot commit partial metadata. Restart with a start fragment; duplicate complete
delivery is idempotent and avoids redundant flash writes.

The PWA sends only metadata from the current tenant-authorized node response,
after credential/PoP authorization and verification of the node's key proof. Both
ends check node/organization binding. No advertising label is trusted as an ID.
There is deliberately no new metadata cache: backend-unreachable/offline sync
sends no metadata and preserves the last confirmed device name. Existing pinned
key caching remains organization bound. Older firmware lacks the capability and
continues ordinary sync without a name update. A rejected metadata update yields
a maintenance warning; observations still require their ordinary authorization,
durable local persistence and ACK. BLE loss follows the existing reconnect/retry.
Authorized sync clients can update display metadata; it is not a cryptographic
statement of identity or ownership and cannot grant access to observations.

## Persistence and advertising

The existing CRC-protected, atomically replaced NVS identity blob gains a v2
trailer containing the number and complete accepted name. Old blobs without the
trailer load with missing metadata; a later authorized online sync fills it.
No separate keys or partially committed number/name pair are used. Failed writes
leave an old/new whole blob; retries reload durable state, including an ambiguous
commit result. Production NVS latches ambiguous I/O failures until reboot/recovery;
no stale mutation is allowed after such a failure. Capture sequence/ownership writes preserve metadata. Factory reset
clears it while rotating the existing technical identity. Firmware downgrade
across this storage extension fails closed on the unknown trailer; do not perform
an in-place downgrade without a deliberate migration/reset and data backup.

Actual chooser names:

* UNCLAIMED: `MM-unregistriert` (no factory suffix is used).
* CLAIMED without delivered legacy metadata: `MM-registriert`.
* CLAIMED, numbered: `MM-4711`.
* CLAIMED, named: `MM-4711-der-gelbe` for display name `der gelbe`.

ASCII whitespace/control characters become hyphens in the advertising suffix;
other Unicode and case are retained. Only the suffix is shortened, at a complete
UTF-8 code point, to a maximum of 29 bytes total. The full decimal number always
fits, including INT32_MAX. The complete name remains in NVS.

Legacy primary advertising is flags (3 bytes) plus the unchanged complete 128-bit
service UUID (18 bytes): **21/31 bytes**. Scan response is a complete local-name
AD (2 bytes plus up to 29 bytes): **31/31 maximum**. Redundant 128-bit service data
is omitted; version/claim flags remain available through Hello. Service-UUID
filters, characteristic UUIDs and discovery remain unchanged. The next advertising
start after disconnect uses the latest name and updates GAP name without reboot.

BLE names are publicly visible, spoofable and may be cached by Android. Backend
renames reach the device at the next authenticated online sync, not immediately.
Avoid personal, location-sensitive or other confidential labels.

## Integrated review (R)

Review the final diff against sequence/backfill/overflow, merge/claim transaction
boundaries, explicit node/org binding, pre-auth and expired-session denial,
fragment bounds/retry, whole-blob consistency, UTF-8, advertising budgets,
legacy capability gating, backend outage and #169/#170/#171 preservation. Confirmed findings repaired: use the flushed managed JPA entity for receipt
issuance; preserve observation sync on metadata rejection; reject malformed UTF-8
and changing a previously assigned number. Added the PostgreSQL immutability
trigger and tests for pre-auth/foreign bindings, fragmented interruption,
ambiguous atomic writes, Unicode boundaries and legacy capability gating. No
remaining in-scope blocker was identified. A single MSVC type correction in the
simulator and two crypto tests keep their existing random-byte fills warning-clean.
Partial metadata is also explicitly discarded on every new challenge/auth attempt.
The isolated migration-test schema is dropped in a finally block so later
INFORMATION_SCHEMA assertions see only their own application schema. Focused tests cover
these paths; affected regressions follow after repairs stabilize.

## Physical acceptance: UNVERIFIED

Host tests and CI crossbuilds are not ESP32-C3 + Android Chrome validation.
Reproducible manual gate:

1. Build/flash with the independently pinned issuer and chosen documented clock
   policy. Scan an unclaimed ESP32-C3 using Android Chrome. Verify service-filter
   discovery and `MM-unregistriert`, and inspect raw packet lengths with a scanner.
2. Claim as ACTIVE ADMIN. Verify UUID/key continuity, backend number and
   `MM-<number>` after disconnect; cut power and verify the same name on reboot.
3. Rename in management, online sync, disconnect/reselect and check the suffix.
   Repeat with an empty name, spaces, 100 Unicode characters and a long name;
   verify both packets <=31 bytes, valid UTF-8 and the complete decimal number.
4. Interrupt metadata fragments and power during NVS commit. Reboot/retry: either
   complete old or complete new metadata survives, with unchanged observations,
   UUID/key, sequence and ACK watermark. Retry a complete update twice.
5. Attempt writes before auth, with foreign-org credentials and wrong node/org
   payloads, and after session expiry. All must be denied without changing names,
   observations or allowing READ/ACK/COMPACT.
6. Disconnect internet, reboot device/PWA and sync with the existing valid
   credential under #171's explicit alpha policy. Verify stored name, durable
   observations and ACK; reconnect and verify separate backend upload success.
7. Sync old firmware with the new PWA: normal observation sync succeeds and no
   metadata opcode is sent. Test multiple nodes and organization switching to
   confirm that names are never transferred to a different node/organization.
