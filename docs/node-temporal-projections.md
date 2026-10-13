# Backend boot evidence and temporal projections (#132)

Issue #132 and ADR 0017 are authoritative. V18 is an additive, forward-only
migration: `boot_epochs` and `time_anchors` are immutable JPA entities.
No raw observation, frozen deployment, observation ACK or visit-gap-v1 row is
updated. Down migration is unsupported; restore a database backup and matching
application release if necessary. Existing observations initially remain UNKNOWN.

## Ingestion and integration contract

All endpoints use existing session authentication, CSRF for POST, ACTIVE user,
organization and local membership. SYSADMIN and shared-care permissions do not
grant this owner-only evidence interface. All responses carry no-store.

- `POST /api/v1/nodes/boot-evidence`: strict JSON
  `{organizationId, boot}`.
- `POST /api/v1/nodes/time-evidence`: strict JSON
  `{organizationId, boot, challenge, proof, acceptance, challengeBytes, acceptanceBytes}`.
- `GET /api/v1/nodes/{nodeId}/temporal-projections?organizationId=...&afterSequence=0&limit=50`:
  cursor pagination, 1..100 items ordered by original Node sequence, no global
  observation/anchor lookup or foreign counts.

`boot` maps the finalized #128 BootEpochV1 fields without inventing identity:
`nodeId, incarnation, bootCounter, resetReason, startedUptimeMs,
firstPossibleSequence, formatVersion`. First possible sequence is a reservation
boundary, not evidence that a read occurred. Actual first/last observations and
uptime ranges are queried from immutable raw data. Counter gaps are allowed;
reset reason is diagnostic. Boot-only uploads are explicitly
OWNER_UPLOADED_BOOT_V1, not independently device-signed or UTC evidence.

`challenge`, exact compact `proof` and `acceptance` use #129's existing
NodeTimeProtocol and NodeTimeProofVerifier. Binary fields are canonical,
unpadded base64url. `challengeBytes` is the exact canonical TIME-CHALLENGE input
followed by its 64-byte P1363 signature; `acceptanceBytes` is the canonical
TIME-ACCEPT input followed by its 64-byte signature. The adapter checks both
against the typed fields and persists them unchanged, including arbitrary binary
bytes. This is a bounded backend envelope, not a new BLE framing choice.
#131 maps its opaque durable evidence to these existing signing inputs.

POST bodies are capped at 8192 bytes, including chunked bodies. Duplicate/unknown
fields, trailing JSON, numeric strings, fractional integers, invalid UUIDv4,
unsupported versions and out-of-range u32/u64 values are rejected. Uptime and
first possible sequence are lossless numeric uint64; clients must use BigInt or
lossless JSON parsing, never JavaScript Number. Original raw ingestion retains
its existing signed Long/Integer limits; this issue does not widen or reinterpret
its immutable contract. High unsigned anchor uptime is supported exactly.

Node public keys come from the independently registered owning Node. Backend
ES256 issuer/key/purpose, identity/incarnation/boot, nonce, fingerprint, original
signed challenge uptime, exact proof digest and device acceptance signature are
verified. The ADR's provisional challenge lifetime maximum is 30000 ms.
This backend verifies durable historical acceptance; it does not issue clock
authority or bypass firmware's pending-nonce/deadline checks.

Claims, same-Node observation ingest, deployment changes and evidence use existing
database locks. Unique boot identity and scoped anchor identity, nonce consumption,
proof ID/digest and composite anchor-to-boot ownership constraints protect retries.
Stable anchor IDs are scoped to organization/Node/incarnation/boot, so another
organization's UUID cannot become an enumeration oracle. Identical retry returns
DUPLICATE_IDENTICAL; differing boot/signature/proof/acceptance yields 409 without
overwriting anything. Ingest is atomic, including newly inserted boot metadata.
Denials expose no foreign owning organization, key, raw ID or anchor metadata.

Pre-claim proofs are rejected when signed issuance predates the Node's committed
claim. #18 has no durable incarnation/monotonic claim continuity receipt suitable
for independently validating pre-claim anchors. A future verified chain can add
support; a bare token or presented key never establishes ownership. Current
registered key and committed claim boundary are rechecked on projection reads.
Factory reset rotates Node identity in the existing protocol; cross-incarnation
correlation is never attempted.

## Uncertainty policy

Default configuration produces no certified absolute bounds. A signature alone
proves backend assertion and device acceptance, not physical clock accuracy.
An operator may configure all of:

```properties
miezmerker.time-projection.server-error-ms=...
miezmerker.time-projection.sampling-ms=...
miezmerker.time-projection.drift-ppm=...
miezmerker.time-projection.provenance=...
```

Error and sampling budgets must be strictly positive; drift must be 1..999999 ppm.
Provenance must identify the versioned operational evidence/attestation supporting
server clock accuracy at issuance, quantization/sampling bounds, and this Node
oscillator across its lifetime and all operating modes (including unanchored
boots). These settings do not measure NTP or hardware. Do not enable them without
that justification; server accuracy must apply to every accepted historical proof,
not merely the upload instant. Missing/invalid assumptions retain authenticated
evidence with no UTC bounds and produce UNKNOWN. Tests use explicitly named
synthetic budgets; they are not physical measurements.

Policy values and provenance are frozen with each anchor. Changing configuration
does not silently upgrade historical evidence or alter retry behavior.
Given issuance S, error E, symmetric sampling/quantization Q and signed
challenge-to-acceptance D:

```text
acceptance_lower = S - E - Q
acceptance_upper = S + E + Q + D
delta = accepted_uptime - event_uptime             (same boot only)
drift = ceil(abs(delta) * ppm / 1000000)
event_lower = acceptance_lower - delta - drift
event_upper = acceptance_upper - delta + drift
```

BigInteger preserves unsigned uptime and products; conversion of final UTC to
signed milliseconds is checked. Overflow yields CONFLICT or rejects
unrepresentable ingest; no floating point or zero UTC placeholder is used.
Earlier and later same-boot events have growing drift uncertainty.
All anchors are checked at common temporal references before intersection.
Contradiction produces CONFLICT, never whichever narrow interval looks convenient.

Across boots, preceding evidence supplies only a lower boundary and later evidence
only an upper boundary. No uptime is subtracted across boots and no power-off
duration is inferred. Within an unanchored boot, first/last raw uptime preserves
its relative duration: event lower is shifted from the first event by conservative
minimum elapsed time, and event upper is shifted back from the last event.
Minimum elapsed is `duration - ceil(duration * ppm / 1000000)`, clamped at zero.
The full calculation also subtracts twice the largest persisted sampling budget
to conservatively cover both capture samples. Forward/backward propagation sums
these minimum powered-on spans across consecutive unanchored boots; individually
plausible boots cannot evade a contradiction in their combined required duration.
The largest justified persisted Node oscillator bound is used conservatively.
An observed boot span too long for both outer bounds is CONFLICT. Missing endpoints
remain open and UNKNOWN; complete outer bounds yield BOUNDED.

## Projection and historical attribution

The read-only version `boot-interval-v1` is computed from the current immutable
evidence snapshot on read. This avoids stale materialized rows and makes late
anchors immediately reproject already-ingested/ACKed/compacted observations
without re-upload. The algorithm version, original observation identity/sequence/
boot/uptime, explicit earliest/latest milliseconds, evidence IDs and uncertainty
policy provenance are returned. Representative UTC is null. VERIFIED and ESTIMATED
remain reserved quality states; this version emits RECONSTRUCTED for proven
same-boot acceptance evidence, BOUNDED for complete outer intervals, UNKNOWN for
missing bounds, and CONFLICT for invalid chronology/contradictions. An original
SYNCED/RTC_ONLY/KNOWN flag alone is not independently authenticated timestamp
provenance and is not promoted to VERIFIED.

Frozen raw FeedingSite/Deployment always survives. Separate derived attribution
is available only when the entire closed UTC interval falls within exactly one
historical half-open deployment and overlaps no other deployment. Moves, open
time intervals and conflicts remain unresolved. No current-site fallback or
new DerivedVisit aggregation is added.

Queries batch lifetime chronology and boot summaries, fetch historical site
references once, and load raw records by sequence cursor with indexes. Memory
is bounded to 4096 anchors, boot summaries/markers and deployments per lifetime/
Node; at most 64 anchors per boot. Capacity excess returns 503, retaining all
evidence rather than truncating or certifying partial results. Boot boundary
summaries are precomputed once; projection avoids repeated quadratic foreign-boot
propagation. Complete lifetime window validation still scans that lifetime in the
database; immutable summary caching/materialization is a future scaling option.
A representative synthetic 4096-anchor/100-projection test is not a claim of
production throughput.

## Verification and remaining integrations

Focused arithmetic tests cover ADR TV1/TV2/TV3/TV5, multiple anchors, impossible
intermediate duration, open bounds, counter gaps, unsigned maxima, UTC overflow,
policy defaults and representative volume/capacity. Service/HTTP tests cover late
evidence, exact bytes/retries, concurrency, transaction interruption, second-anchor
conflicts, pagination boundaries, tenancy/identical chip IDs, disabled contexts,
SYSADMIN, pre-claim/key rotation, CSRF/schema/body limits, historical deployment
containment and frozen raw attribution. Existing #129 golden-vector tests retain
issuer/purpose/nonce/key/acceptance cryptographic negative coverage.

#130 supplies real pending-nonce verification and durable installation evidence;
#131 supplies BLE/PWA forwarding, lossless integers and independent durable evidence
ACK; #133 consumes these intervals for new visit/UI behavior. No firmware or PWA
transport is modified. Physical clock calibration and Android/ESP32 E2E remain
UNVERIFIED and belong to #134.

