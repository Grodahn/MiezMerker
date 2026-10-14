# 0017 — Boot-Epoch Time Reconstruction and Trusted-Time Bootstrap

Status: **Accepted architectural contract** — issue #127, epic #126; focused adversarial self-review completed at maintainer request (2026-10-11). External security assessment remains recommended before real-world deployment.  
Date: 2026-10-11  
Related: #3, #4, #5, #6, #7, #8, #10, #13, #17, #18, #76, #108, #121, #128–#134

## Decision

An external battery-backed RTC is **optional**, not a requirement for a low-cost MiezMerker Node. The firmware already retains unique per-node sequences, a persistent boot counter and monotonic uptime. **Only a successfully CLAIMED Node records RFID visits.** For every accepted read after claiming, we retain these immutable facts. Separately persisted UTC **time anchors** can reconstruct observation time within the **same boot epoch**. Across unanchored boot epochs we know event ordering and intra-boot elapsed time, but **cannot reconstruct unobserved powered-off durations**. We expose conservative possible intervals rather than invented exact timestamps.

**Observation-time estimates and security-trusted UTC are separate trust domains.** Only a backend-signed, fresh, challenge-bound UTC proof, validated against an **independently pinned** issuer key, may initialize the time required for offline credential expiration and secure claiming. Phone time is untrusted. The current protected BLE TimeCorrect operation must remain protected. Without security-trusted UTC, a **CLAIMED** Node continues storing raw RFID events with UNKNOWN clock status, but does not authorize protected BLE reads, ACKs, time correction or new claims. An **UNCLAIMED** Node records no RFID visits regardless of its clock status.

This ADR is the **reviewed design contract for later implementation**, not a claim of completed device functionality, independent third-party security certification or physical hardware validation.

## 1. Existing-code audit and compatibility constraints

- Firmware type RawObservation already contains node_id, incarnation, sequence, clock_status, optional observed_at_epoch_ms, monotonic_ms (u64), boot_counter (u32). See firmware-core/include/miezmerker/types.hpp and protocol/ble/messages.md.
- Core::initialize in firmware-core/src/core.cpp already persists a boot_counter increment before accepting reads after a normal reboot; fresh identities begin at 1. Reuse this rather than inventing a second unrelated counter. Fail closed at counter overflow. Keep the existing identity + sequence + reset journal.
- Current ESP32 BoardClock in firmware-esp32/idf/main/board.hpp has monotonic time but reports wall clock UNKNOWN; epoch_ms() is zero and time setting is disabled. Security bootstrap is **not yet implemented**.
- ESP32-C3 internal RTC time can survive some resets and sleep but **not a power-on reset**. The high-resolution esp_timer_get_time is since initialization and resets after restart/Deep Sleep. The reset reason is a diagnostic, not a power-off duration sensor. See [Espressif System Time](https://docs.espressif.com/projects/esp-idf/en/v6.0-rc1/esp32c3/api-reference/system/system_time.html), [ESP Timer](https://docs.espressif.com/projects/esp-idf/en/v5.4.2/esp32c3/api-reference/system/esp_timer.html), and [reset reasons](https://github.com/espressif/esp-idf/blob/master/components/esp_system/include/esp_system.h).
- RawObservation.java already persists bootCounter and monotonicMs with unique (node_id, sequence) identity; ObservationIngestService.samePayload rejects changes to an already-ingested raw payload.
- ObservationIngestService **intentionally leaves feeding_site_id/deployment_id null** for UNKNOWN timestamps. VisitAggregationService visit-gap-v1 excludes UNKNOWN timestamps and unattributed sites. Reconstructing a time must NOT silently attribute the event to today's FeedingSite.
- ADR 0012 (observations), ADR 0012 (offline credentials), ADR 0013 (node claiming), ADR 0015 (BLE) and ADR 0016 (tenant sharing) remain authoritative on identity, security and privacy. **This ADR explicitly strengthens the capture precondition:** original capture paths that previously allowed UNCLAIMED local test observations must be gated in #128; accepted #112/#108 implementation history is not evidence that production behavior already enforces it.

### Claim-state gate for observation capture (amendment, 2026-10-11)

**Non-negotiable product rule: a Node that is not registered to an organization does not collect or persist cat visits.**

| Persisted Node claim state | RFID input and capture | Clock/provisioning behavior |
| --- | --- | --- |
| `UNCLAIMED` | **Drop/ignore RFID input without producing any RawObservation, assigning a sequence, filling the observation log, or emitting chip IDs in normal diagnostics.** | Boot markers, monotonic timer, independently pinned issuer key, signed time bootstrap, public identity and direct ADMIN registration may operate solely to enable secure registration. |
| `CLAIMED` (owner committed atomically on the Node) | Allow ordinary RFID capture under existing Core persistence and deduplication rules, even if UTC is `UNKNOWN`. | Protected BLE sync still needs a valid offline credential, issuer trust and security UTC. |
| Backend reserved ownership but Node claim receipt not yet committed | Still **UNCLAIMED** locally: **no** capture. | Deliver/retry the existing claim receipt; never infer successful registration from a request or browser permission. |
| Factory reset / renewed unclaimed state | Stop capture immediately; existing reset procedure already rotates Node identity and clears the old observation log securely. | Allow direct ACTIVE ADMIN claiming through #154; no old owner/data reuse. |

**The state check belongs to the shared firmware-core capture path**, not
merely the ESP32 GPIO3, UART or hardware adapter. Enforce it on
`record_chip_read`, `poll_reader` and every development/synthetic entry
point, preventing bypasses by a different source. Do not reserve a
sequence, append storage, emit a chip-bearing event or queue a future
observation for a rejected UNCLAIMED read. Keep diagnostics aggregate
and non-identifying. Firmware re-reads the persisted claim state after
the atomic #18 claim commit / power cycle; no BLE session grants capture
rights by itself.

**Claimed does not mean assigned to a FeedingSite.** A registered Node
may be in the already supported intermediate `CLAIMED but setup
incomplete` state from #53/#121; it may capture truthful raw reads, but
the backend must not invent a site or `DerivedVisit` while attribution
is unresolved. No additional online check is required for ongoing
local capture: it must also work when there is no internet or trusted
UTC after a power loss.

Time-bootstrap evidence and boot markers created **before** claiming
are technical registration artifacts only, not cat visits. If a valid
pre-claim time anchor shares a boot epoch with post-claim observations,
it may provide a time bound only if the independent security proof and
device-acceptance evidence validate; it must never imply that RFID
observations existed before the claim.

**Implementation/acceptance** belongs to #128 and #134. In particular,
development GPIO3 and synthetic reader paths from #112/#108 currently
require an explicit regression test for this gate; this ADR change
does not assert that those existing builds already comply.

## 2. Clocks and identity

| Concept | Definition |
| --- | --- |
| Node lifetime | (node_id, incarnation); changes on deliberate factory reset, not on reboot |
| Boot epoch | (node_id, incarnation, boot_counter); one uninterrupted monotonic timeline |
| Uptime | 64-bit monotonic milliseconds, valid **only within that boot epoch** |
| Original capture time | Immutable UTC and clock-status fields sampled at RFID read time; may be null / UNKNOWN |
| Time anchor | Separately recorded assertion linking one boot uptime to UTC, with provenance and uncertainty |
| Temporal projection | Rebuildable possible UTC interval for an event, distinct from the original raw observation |
| Security clock | UTC valid for sensitive authorization only after verified backend-signed bootstrap |

Firmware must durably reserve the boot epoch **before** accepting reads; crash/partial-write recovery must not reuse an epoch for unrelated monotonic timelines. Existing boot_counter is sufficient if its uniqueness and rollover guarantees are preserved. Store esp_reset_reason() as diagnostics, but not as elapsed-time evidence. A timer discontinuity (including Deep Sleep where esp_timer resets) requires a fresh epoch or a separately proven continuous monotonic adapter. Never correlate different epochs by subtracting uptime values.

The raw event identity remains (node_id, incarnation, sequence); boot_counter is metadata, not a replacement unique event key. Reserving sequence before append, ACK watermark ordering, immutable payload retries and no-overwrite-full-store behavior all remain unchanged.

## 3. Durable time evidence and wire schema (#128/#131/#132)

The following **logical v1 contract** is shared by firmware, BLE/collector and backend. Implementation issues choose exact binary framing and database types, preserving every meaning:

~~~text
TimeAnchorV1:
  anchorId: UUID or digest                    // stable idempotency
  nodeId: UUID
  incarnation: UUID
  bootCounter: u32
  uptimeMs: u64                               // sampled at node acceptance
  utcMs: signed 64-bit UTC epoch              // signed backend issuance instant,
                                              // NOT assumed exact acceptance instant
  source: BACKEND_SIGNED | PHONE_ESTIMATE | RTC_HOLDOVER
  verifiedSecurityTime: bool                  // derived by verifier, never trusted from PWA
  uncertaintyMinusMs: u64
  uncertaintyPlusMs: u64
  driftPpmBound: u32
  proofIdOrDigest: optional bytes/string
  formatVersion: u16 (=1)
~~~

BootEpochV1 also stores its durable identity, reset reason, first/last node sequence where available, order/continuity diagnostics, and anchor references. Unique identity: (nodeId, incarnation, bootCounter, anchorId). Reject conflicting retries; append-only or audit-revisioned corrections, **never overwrite raw observations**. Do not rewrite stored original capture UTC or clock status after obtaining a better anchor.

**Trust states are deliberately distinct:**
- BACKEND_SIGNED: token and Node acceptance independently validated; can support security UTC within a bounded confidence interval.
- PHONE_ESTIMATE: pragmatic smartphone visit-time estimate, **never** permits credential/claim authorization. See the #169 amendment below; no accuracy or drift budget is required for observation estimates.
- RTC_HOLDOVER: an already initialized clock continued through a demonstrably continuous interval with conservative drift. Merely reading the ESP RTC after power-on is not evidence of safe continuity.
- UNKNOWN: no useful reliable UTC. Zero / 1970 placeholders are not timestamps.

The current BLE enum UNKNOWN / RTC_ONLY / SYNCED is *capture state*, **not** a standalone proof of security-trusted time. Temporal projection must check real provenance; preserve legacy visit-gap-v1 behavior separately instead of silently redefining it. For a new strict time-projection version, do not treat RTC_ONLY as cryptographically verified just because observed_at_ms is nonzero.

Both the Node's time proof acceptance and its boot/anchor metadata must be exported as durable, authenticated evidence. A token issued by the backend is **not by itself evidence** the Node installed that token. Keep a verifiable Node acknowledgment/binding record. Loss or partial writes may downgrade confidence, never manufacture trust.

## 4. Projection math and uncertainty

For an RFID event at uptime U_event and a **verified installed anchor** at
uptime U_anchor from that **same boot**, with backend signed issuance instant
S and an independently bounded backend-clock error E_server:

~~~text
D = U_anchor - U_challenge                  // measured, nonnegative,
                                          // cannot exceed challenge lifetime
acceptance_utc_lower = S - E_server
acceptance_utc_upper = S + E_server + D    // server issued after challenge
                                          // and before node acceptance
delta = U_anchor - U_event                  // signed, same-boot only
drift = ceil(abs(delta) * drift_ppm_bound / 1_000_000)
event_utc_lower = acceptance_utc_lower - delta - drift
event_utc_upper = acceptance_utc_upper - delta + drift
~~~

Here D deliberately **overestimates** the elapsed time between server issuance
and device acceptance. It is a defensible conservative bound using a
monotonic challenge-start sample and the cryptographically tied acceptance
sample. Add quantization, record sampling, scheduling and clock-source
uncertainty to the appropriate side of the interval. The Node must not
assign `utcMs = S` to its *acceptance uptime* as if transmission delay were
zero. A nominal time for display is optional; the normative fact is the
closed interval `[event_utc_lower, event_utc_upper]`.

**Security clock and JWT guard:** at every protected operation, propagate
this bound and accumulated drift to a current interval `[L, U]`.
Only if **L >= credential.iat AND U < credential.exp** (with all other
claim/scope/PoP checks passing) is its validity unambiguous. If either
boundary intersects the possible-time interval, or any bound is unknown,
deny. This uses the existing no-expiry-grace policy. For node-signed claim
advertisements, ensure the current interval is tight enough to meet the
backend's freshness rule; never sign a knowingly ambiguous "fresh" point.

All calculations use checked integer arithmetic and UTC; the UI localizes
time. The uncertainty includes backend time-source error, the bounded
**entire challenge/response delay**, local sampling latency, quantization
and oscillator drift/operating-mode assumptions. Signed does not imply
mathematically exact. The selected drift bound is a documented, verified
configuration assumption requiring real measurement for production
calibration; **no unmeasured physical ppm guarantee is claimed**.

Several anchors in one boot can reduce uncertainty only if their bounded constraints are consistent; contradiction produces CONFLICT/UNKNOWN plus diagnostics, not optimistic selection. Do not use an anchor from another boot. A clock correction only influences **subsequent** original capture timestamps; earlier originals remain frozen.

### 0 or 1 power interruption

Example: Boot 42 has raw UNKNOWN reads at uptime 00:20 and 02:30, then receives a verified UTC anchor at uptime 04:00 on 2026-10-11T20:00:00Z. Nominal timestamps project to **2026-10-11 16:20Z** and **18:30Z**, each shown with a nonzero bounded uncertainty. The raw records remain UNKNOWN and retain their original null capture epoch.

### Two or more power interruptions

Example: Boot A records a reliably timestamped visit at 2026-10-01T20:00:00Z. Boot B records chip #1 at uptime 00:20 and chip #2 at uptime 02:30 (130 minutes apart), then loses power. Boot C records a visit at uptime 01:00, later receives a signed anchor at uptime 04:00 on 2026-10-11T20:00:00Z. Boot C's visit projects to **2026-10-11T17:00Z** (+/- uncertainty). Boot B's visits cannot be assigned exact dates: only **between proven outer limits**, constrained by the known 130-minute intra-boot separation and the boot order. The missing durations between A, B, and C cannot be recovered.

When a lower or upper bound is unavailable, the projection remains half-open or UNKNOWN; do not invent a closed interval. If timers did not remain monotonic within an epoch, even the within-boot elapsed constraint must be discarded.

### Proposed DTO qualities

| Quality | Meaning / display |
| --- | --- |
| VERIFIED | Originally recorded from verified security-time provenance; display approximate UTC + interval |
| RECONSTRUCTED | Back-calculated from signed anchor in the same boot; display "Rekonstruiert: ca. ..." and bounds |
| BOUNDED | Only a justified interval crossing boots; display "Zwischen ... und ..." |
| ESTIMATED | Smartphone visit estimate; display "ca. ... Uhr" or an explicit estimated interval; cannot drive secure auth |
| UNKNOWN | No defensible absolute time; display "Zeitpunkt unbekannt" |
| CONFLICT | Contradictory evidence; do not select a favored timestamp |

These DTO qualities are independent of the existing immutable on-wire capture enum. No legacy observation may acquire exact dates merely because data is missing.

### Observation-time amendment (#169, 2026-10-14)

Cat visits are approximate operational data. Internal ESP32 drift has no
practical relevance for this purpose: **no target accuracy, drift threshold,
minimum precision, correction window or cryptographic timestamp certification**
is required. All signed security-clock rules in section 5 remain unchanged.

After ordinary credential/PoP and pinned Node authentication, each sync reads
the versioned, read-only `ClockSnapshotV1` over the protected Batch characteristic.
Its Node ID, incarnation, boot counter and unsigned 64-bit uptime are sampled
together. The PWA samples `Date.now()` immediately after the first response and
records that request's elapsed BLE duration as a diagnostic, without an accuracy
SLA. A second snapshot must retain the same identity/boot and nondecreasing uptime;
interruption or reboot produces no anchor. No UTC is transmitted to the Node.

The PWA durably saves a UUID-keyed `PHONE_ESTIMATE` in the existing time-evidence
outbox **before raw ACK**. The owner-authorized, CSRF-protected
`/api/v1/nodes/phone-time-evidence` ingest validates the complete schema, identity
and exact retry payload. Estimates live in an additive immutable evidence table;
the stricter signed-anchor table, signatures, trust budgets and ingest are untouched.
Phone anchors are never Node-owned evidence and never receive a Node evidence
receipt. They survive raw ACK/COMPACT and upload independently when online.

Within a boot, `T_visit = T_phone - (U_sync - U_visit)`. Phone anchors are the
business time authority for that boot; otherwise the existing signed projection
still applies. Multiple phone offsets produce the enclosing span of **all**
estimates, preserving relative ordering and exposing disagreement without selecting
an anchor or introducing a tolerance. Reversed chronological phone timestamps,
impossible cross-boot ordering or integer overflow produce `CONFLICT` with no
favored point. A single consistent estimate is `ESTIMATED` even when its stored
bounds coincide: that equality is not a certified precision claim.

Unanchored boots retain existing chronological bounds and within-boot elapsed
constraints. Never subtract uptime across boots or invent powered-off duration.
One missing boundary stays open; neither boundary yields `UNKNOWN`. RFID rows
are retained in every case. The existing read-time projection rebuilds after
late evidence upload without rewriting originals. Historical FeedingSite
attribution still requires the entire possible interval in exactly one deployment;
sharing and tenant isolation retain their existing gates. Projection/visit
versions advance to `boot-interval-v2` / `visit-interval-v2`.

After confirmed claim receipt commitment, snapshot-capable Nodes use the same
ordinary authenticated sync flow for the initial anchor. Claim completion itself
does not grant a protected session. If that follow-up fails, registration remains
committed and the UI requests another read. Older firmware retains its existing
flow and gains phone anchors only after a firmware update. No security-clock
bootstrap or claim authorization is changed. Offline sync after a reboot still
needs the existing signed security-clock bootstrap; changing that policy belongs
to #171. Credential invalidation belongs to #170; physical E2E acceptance to #134.

## 5. Trusted UTC bootstrap: wire handshake and threat model (#129/#130/#131)

**Absolute invariant:** Without trustworthy current UTC, no JWT expiration or future-iat validation, secure claiming, protected GATT operation, ACK or compaction. Existing valid per-AppDevice proof-of-possession and ACTIVE-organization membership remain mandatory **after** time bootstrap.

**Pre-auth bootstrap is a new narrowly scoped capability, NOT removal of protection on the existing TimeCorrect GATT characteristic.**

1. **Node challenge:** Device emits a new, single-use, 32-byte CSPRNG challenge, node ID, incarnation, boot counter, issuance monotonic uptime and protocol version. A new nonce supersedes the previous. Optionally domain-separate and sign a possession proof using its persistent P-256 device key so the backend can bind the request to a real private-key holder. Public bootstrap must not reveal chip numbers, pending counts or owner secrets.
2. **Authenticated PWA:** Browser-freigegebene PWA requests a time proof from the backend. For an owned node, authenticated ACTIVE member of that owner org; for a first unclaimed node, ACTIVE ADMIN of the selected organization, plus signed device identity and receipt checks during direct registration (#154). Rate limits, request bounds and abuse logging apply. Never trust a caller-supplied owner identifier over backend-verified ownership. No arbitrary-time-signing endpoint.
3. **Backend signing:** Server samples **its own authoritative UTC** and issues a domain-separated ES256 token, purpose "MM-TRUSTED-TIME-v1", carrying issuer/key ID, version, time in UTC milliseconds, node ID, incarnation, boot counter, fresh challenge, and node key fingerprint where available. The PWA cannot submit a UTC to be signed. Server must withhold issuance if its own time source is unhealthy. The public signing key must be provisioned independently on the node.
4. **Node validation:** ESP verifies signature under independently pinned public key (existing CONFIG_MM_ISSUER_PUBLIC_KEY_HEX or separately pinned time key), purpose, key ID, boot/node/nonce binding, and single-use state. **Never** bootstrap trust from the signed token's embedded public key, from an unverified claim receipt or from a public /issuer response alone.
5. **Freshness without any pre-existing clock:** A signed issuance timestamp **cannot prove its own freshness**. Freshness instead relies on nonce unpredictability plus a short **monotonic challenge-to-acceptance deadline** (provisional maximum 30 s, to be confirmed in tests). The signed server issuance is bounded between Node challenge and acceptance. At Node acceptance, the security clock starts from the **conservative interval** `[S-E_server, S+E_server+D]`, where `D = U_accept - U_challenge`; it MUST NOT start from the point value S alone. After the deadline, or after any nonce consumption/reset, reject. No post-reset reuse of stored time proof.
6. **Persistence/activation:** Node atomically commits its verified time anchor and the exact challenge/acceptance uptime and signed-proof digest before publishing a valid security clock, minting a fresh signed claim advertisement or authorizing a time-dependent credential. A trusted clock means a bounded interval, not a magic precise point; validity is checked with the strict `[L,U]` guard above. After a crash before commit, remain UNKNOWN. All attempts consume/retire the nonce, even invalid ones.
7. **During operation:** Maintain trusted UTC as monotonic elapsed since the verified anchor with a conservative growing error envelope and finite maximum age. When a JWT iat/exp boundary falls **inside the uncertainty interval**, deny rather than treat validity as certain. Recheck can_sync at every protected GATT operation. A new boot starts UNKNOWN under this MVP policy, even after a soft reset, unless a later explicit continuity design is proven. Detect rollback/clock jumps; invalidate and rebootstrap instead of silently moving the trusted clock backwards.

**Startup usability/iat ordering:** a just-issued offline JWT can have an
`iat` later than the clock interval's conservative lower bound immediately
after bootstrap, even though both are legitimate. The PWA should refresh
or obtain an offline credential **before** initiating the signed time
handshake where practical. Otherwise clearly report a temporary
time-confidence blocker and retry after the monotonic lower bound safely
passes `iat`; do not add an expiry/future-iat grace period to hide it.

**State machine:**

~~~text
BOOT or timer discontinuity -> SECURITY_TIME_UNKNOWN
  -> NEW_NONCE_PENDING (public, time-only; expiry measured monotonically)
     -> INVALID / TIMEOUT / REBOOT -> SECURITY_TIME_UNKNOWN
     -> SIGNATURE + BINDING + DEADLINE VALID
        -> ATOMIC_ANCHOR_COMMIT
           -> failure -> SECURITY_TIME_UNKNOWN
           -> success -> SECURITY_TIME_BOUNDED
              -> secure JWT PoP / claim checks, only if [L,U] wholly valid
              -> confidence expired / rollback / reboot -> SECURITY_TIME_UNKNOWN
~~~

Direct registration under ADR 0013 / #154 requires ACTIVE ADMIN and signed device/receipt checks; no physical gate. Signing backend time does not itself register a node, issue BLE credentials, read observations, or authorize any session. Authenticated time initialization and authenticated observation transfer are **separate** transitions.

### Proposed interop payloads and installation evidence

The exact BLE opcode numbers are assigned in #131, but the following
**typed/signature-covered fields are normative** for #129–#131. No field may
be silently omitted by one platform:

~~~text
NodeTimeChallengeV1:
  nodeId: 16 bytes
  incarnation: 16 bytes
  bootCounter: u32
  challengeNonce: 32 bytes from board CSPRNG
  challengeUptimeMs: u64
  devicePublicKey: 65-byte uncompressed P-256 public key (0x04 || x || y)
  devicePublicKeyFingerprint: SHA-256(devicePublicKey)
  deviceChallengeSignature: 64-byte ES256/P1363
  version: 1

BackendTimeProofV1 (JWS compact ES256, purpose-separated):
  iss: configured issuer
  kid: pinned signer key ID
  kind: "node-time-bootstrap"
  ver: 1
  node: NodeTimeChallengeV1.nodeId
  incarnation: NodeTimeChallengeV1.incarnation
  boot: NodeTimeChallengeV1.bootCounter
  nonce: base64url(challengeNonce)
  dpf: base64url(devicePublicKeyFingerprint)
  utc_ms: server-issued current UTC, signed 64-bit
  jti: unique proof ID

NodeTimeAcceptanceV1 (device-signed separate from token):
  nodeId, incarnation, bootCounter, proofIdOrDigest,
  challengeNonceDigest, acceptedUptimeMs, anchorId
  signature: device P-256 signature with TIME-ACCEPT domain separation
~~~

The device challenge signature uses a **dedicated domain** different from
normal offline-auth, claim and session signatures. To prevent cross-language
ambiguity, define the canonical **signed message bytes** below; signature
encoding is 64-byte IEEE P1363 `r||s` for ECDSA P-256/SHA-256:

~~~text
NodeChallengeSigningInput =
  UTF8("MM-NODE-TIME-CHALLENGE-v1\n")
  || u16le(version) || raw16(nodeId) || raw16(incarnation)
  || u32le(bootCounter) || u64le(challengeUptimeMs)
  || raw32(challengeNonce) || raw65(devicePublicKey)

NodeAcceptanceSigningInput =
  UTF8("MM-NODE-TIME-ACCEPT-v1\n")
  || u16le(version) || raw16(nodeId) || raw16(incarnation)
  || u32le(bootCounter) || SHA256(raw32(challengeNonce))
  || SHA256(exact UTF8 compact signed backend JWS)
  || u64le(acceptedUptimeMs) || raw16(anchorId)
~~~

No lengths are variable in these signable messages. UUIDs are **binary RFC
4122 bytes**, never UTF-8 UUID spellings; unsigned integers are explicitly
little-endian. Validate that the provided uncompressed P-256 point is
well-formed and calculate its fingerprint from the **same** point covered
by NodeChallengeSigningInput. For claimed Nodes it must equal the backend
registered key. For unclaimed Nodes the presented public key establishes
**only** possession, not ownership; require direct ACTIVE ADMIN / signed receipt
claim (#154) to bind it to an organization.

This does **not** expose a generic pre-auth signing oracle: add explicit
rate-limited, purpose-scoped Node sign operations, retaining UNCLAIMED and trusted-time
restrictions for the existing claim advertisement and disallowing attacker
chosen arbitrary messages.

BackendTimeProofV1 is standard signed compact JWS ES256 with a restricted
`alg=ES256` protected header and `kid` matching the independently pinned
key. Check a single allowed issuer, fixed `kind`, `ver`, nonce, device
fingerprint, Node IDs and signed UTC. Reject unsupported `alg`, unknown
key, noncanonical/duplicate JSON fields, malformed base64url and inflated
payloads. Format all `utc_ms` as integer UTC milliseconds, not local
time strings. The compact JWS bytes are retained unchanged for the
acceptance signature, so the backend can verify the precise proof the
Node says it installed.

The actual **BLE opcode numbers**, capability bit, GATT characteristic and
bounded fragmentation are selected in #131, while this v1 message signing
contract is fixed by #127. Add checked-in cross-language golden vectors
for canonical message bytes, JWS and signatures **before** #129/#130
are merged. No generic signature of free text or accidental
cross-protocol reuse.

For a **claimed** device the backend verifies the challenge signature
against its registered Node public key and verifies ACTIVE membership in
the owning organization. For an **unclaimed** device it can verify proof of
possession against the presented device key, but this **does not establish
ownership**; only a subsequent ACTIVE ADMIN signed claim with #154 may do that.
The backend must never create ownership merely by issuing time.

The ESP32 validates the backend proof, persists its anchor, and generates
the device-signed acceptance record. The backend validates that acceptance
against the registered Node key (or the subsequently verified claiming key)
and the **same** issued proof, then stores immutable evidence. A PWA cannot
declare its own arbitrary anchor VERIFIED by replaying an issued proof that
was never installed on the Node.

For unknown security time, the firmware can bound **how long after challenge
creation** a proof was accepted, but cannot independently check token
`utc_ms` against current UTC. This is intentional: the signed server time
is authoritative, while challenge lifetime bounds possible transmission
delay. Any proof expiry field is supplemental, not a replacement for this
bootstrap freshness mechanism.

### Threat controls

| Threat | Defense |
| --- | --- |
| Malicious phone clock / locale / old Date.now() | Phone value never grants security trust or determines the server-signed UTC |
| Replayed signed time after powerloss | New boot binding + unpredictable nonce + single use + monotonic deadline |
| Delayed but otherwise valid token | Short monotonic deadline, include worst-case latency in error bound, fail closed at ambiguous credential expiry |
| Spoofed node / foreign organization | Domain binding, P-256 possession where appropriate, backend authenticated owner/admin check, no protected pre-auth data |
| Compromised DNS / different backend | Independently pinned signer, issuer/purpose/key-id checks |
| Corrupted NVS / interrupted boot / partial anchor write | Atomic journal, fail closed before capture or authorization as appropriate |
| Timer reset, sleep or overflow | Distinct boot epoch or validated continuity; no cross-epoch uptime subtraction |
| Unsynced backend clock | Disable issuance until monitored server clock healthy |
| Drift or long-lived offline run | Configured growing uncertainty, maximum trust age, reject uncertain expiry decisions |
| Replay of offline credential | Preserve current PWA key PoP, challenge and per-operation expiration checks |

### Explicit offline tradeoff

After a full power loss and **without internet access on the phone**, there is no fresh backend-signed UTC proof. A **CLAIMED** node can still capture and durably retain RFID observations with UNKNOWN time, but cannot complete authenticated BLE sync until a legitimate client reaches the backend for a fresh proof. An UNCLAIMED node never records RFID visits. Do **not** hide this limitation or switch to unsigned phone time to bypass it. Optional external RTC support (#4) is a separate future enhancement with its own trust/rollback rules.

## 6. BLE, durability and ACK semantics (#131)

Use additive versioned TIME_BOOTSTRAP request/response opcodes and capability bits in protocol/ble; existing v1 authorized TimeCorrect, authorization and binary observation layout stay unchanged. Handle signed proof length with bounded, offset-validated BLE fragmentation as needed. No silent fallback to unauthenticated TimeCorrect. The Web Bluetooth chooser still requires a user gesture and getDevices() does not scan unknown hardware.

**Separate ACK lifetimes are mandatory:**
- Node persists an accepted anchor/boot evidence before treating new security time as valid, and retains each anchor until safely handed downstream.
- Each raw record already contains `(boot_counter, monotonic_ms)`; a future anchor **need not exist yet**. The PWA may durably store/ACK an UNKNOWN raw record under the **existing #6 contiguous watermark contract**, and the Node may subsequently compact that record. Do **not** retain an ever-growing raw log while waiting for a future time anchor.
- Boot/anchor evidence has a **separate immutable, replayable transfer and acknowledgement lifecycle**, keyed by Node/incarnation/boot/anchor identity; it MUST NOT be deleted by raw-record `Compact`. If an anchor arrives after related raw records were already uploaded and compacted, upload it separately and rebuild their backend temporal projections.
- Before acknowledging *any available evidence*, the PWA durably stores that evidence and its binding. Before the Node discards **anchor evidence**, the PWA must have a durable copy; ideally retain it until the backend acknowledges it, or else guarantee retry in a durable outbox and documented recovery.
- Backend marks observation uploads as received independently of whether a time anchor is already available. "Zeit rekonstruiert" is a **separate, later status**. Never label observation upload as fully time-attributed merely because backend signed an uninstalled token.
- Existing observation high-watermark remains unchanged. For anchor evidence, add an independent ACK/cursor (or equivalent idempotent evidence receipt) if the current observation watermark cannot express that condition; distinguish the two statuses in tests and UI.
- Original RawObservation payloads, sequence and samePayload remain byte-stable for duplicate retries. Neither clock correction nor late anchor updates modify them.
- PWA context switches, logout and pending asynchronous callbacks cannot attach anchors to the wrong organization or Node.

## 7. Backend projection, historical FeedingSites and visits (#132/#133)

Persist boot/anchor evidence in separate organization/Node-owned tables, not in the raw observation row. Verify signed proofs independently; reject unauthenticated or mismatched Node/org evidence. Produce read-only, versioned temporal projections; do not rewrite raw observations or their frozen historical metadata.

**FeedingSite attribution is an independent uncertainty problem.** UNKNOWN observations currently arrive with NULL feeding_site_id and deployment_id because historical site cannot be assumed. A derived attribution may name a site only when the **entire possible event-time interval is contained within exactly one historically valid deployment** for that Node. If it overlaps a move, predates first deployment or has an open/unbounded endpoint, leave site UNKNOWN unless other independently durable deployment/sequence evidence proves ownership. No current-site fallback and no mutation of original RawObservation.

Preserve visit-gap-v1 as implemented. Introduce a new algorithm version using verified/projected times only where uncertainty proves both (a) a common historically valid site and (b) a gap decision. At a 60-second threshold, if gap uncertainty straddles the boundary, do not report either a guaranteed merge or a guaranteed split. Keep uncertain read sequences/visit candidates in separate projections with explicit interval and provenance; never force a multi-day uncertainty into exact DerivedVisit.startAt/endAt fields.

Existing org-local data ownership, history and sharing scopes CARE/VISITS/SITE_LABEL and no-store foreign data remain unchanged.

## 8. Dependency and implementation boundaries

| Ticket | Responsibility / prerequisite |
| --- | --- |
| #127 | This ADR, compatibility audit, threat analysis and test vectors; review before implementation |
| #128 | Safe boot journal/monotonic capture/anchor metadata; after #127 |
| #129 | Authenticated backend signed UTC proof service; after #127; can run parallel with #128 |
| #130 | ESP32 proof verification and trust clock; after #128 + #129 format |
| #131 | BLE protocol + PWA proof delivery/evidence transport; after #129 + #130 |
| #132 | Backend anchor persistence and time-interval reconstruction; after ADR/schema and boot evidence shape |
| #133 | Versioned visit aggregation and PWA/Admin interval UX; after #132 |
| #134 | Complete simulator + CI + hardware E2E; after all implementation tickets |

Hardware RTC (#4) is optional. Reader/antenna validation (#3) and powerbank runtime (#12) are separate. The existing development GPIO3 reader (#112) is adequate for mocked chip reads, but **not** evidence of real RFID performance.

## 9. Deterministic test vectors / mandatory review matrix

**TV1 – no powerloss, late anchor.** In boot 42, reads at uptime 1,200,000 and 9,000,000 ms; signed anchor uptime 14,400,000 ms = 2026-10-11T20:00:00Z. Nominal projections 16:20Z and 18:30Z, with calculated uncertainty. Immutable originals remain UNKNOWN/null.

**TV2 – two powerloss events.** Boot A verified read 2026-10-01T20:00Z; boot B reads at uptime 20 and 150 minutes; boot C read at uptime 60 minutes followed by anchor at uptime 240 minutes = 2026-10-11T20:00Z. Boot C read projects to 17:00Z; boot B reads are BOUNDED only, 130 minutes apart, never assigned exact dates.

**TV3 – unbounded.** Two raw reads in a boot with no valid anchor and no later verified upper boundary retain only relative duration and unknown absolute time.

**TV4 – replay/reset.** Proof signed for boot 11/nonce N cannot initialize boot 12, an already consumed nonce, another Node, or a new session with mismatching key/issuer.

**TV5 – latency/expiry.** With Node challenge uptime 1,000 ms,
acceptance uptime 1,300 ms, backend signed issuance
S=2026-10-11T20:00:00.000Z and server uncertainty 100 ms, D=300 ms
gives **acceptance UTC interval [19:59:59.900Z, 20:00:00.400Z]**.
For a read at uptime 900 ms and zero illustrative drift, the projected
read interval is **[19:59:59.500Z, 20:00:00.000Z]**. (Production tests add
measured drift/sampling budgets.) A credential with exp=20:00:00.000Z
is rejected because U >= exp; one with iat=19:59:59.000Z and
exp=20:00:01.000Z can be accepted, assuming all other security checks.
A signed token received after the monotonic challenge deadline is
rejected. Modified phone date has no effect. A newly issued JWT with
iat > clock lower bound waits/retries, never uses an iat grace window.

**TV6 – site ambiguity.** The node moved between X and Y while its absolute time was uncertain. No derived site unless the *entire* defensible time interval belongs to one deployment.

**TV7 – interrupted commit/ACK.** Reset at each durable boot-marker/anchor write, PWA persist, ACK, backend upload and compaction boundary. No duplicate Node/sequence; evidence needed for ACKed data remains durable.

**TV8 – backward compatibility/tenant security.** Old v1 observations, conflicting RTC times, unknown timestamps, untrusted uploads, wrong-org data, forged node proof, timer wrap, unknown boot ID and site changes cannot become fictitiously precise or bypass authorization.

**TV9 – claim-state capture gate.** Boot an UNCLAIMED ESP32,
press GPIO3 / run synthetic capture and inject a reader-tag: observation
count, reserved sequence and pending BLE data remain unchanged; no
chip-bearing log is emitted, regardless of whether trusted UTC exists.
Perform direct ACTIVE ADMIN signed claiming, confirm the Node has durably
committed `CLAIMED` before the next input, then capture a read with
`clock=UNKNOWN`: exactly one immutable raw observation is stored.
Interrupt registration after backend reservation but before Node receipt
commit: still zero captured observations. Retry setup without reverting
CLAIMED. Factory reset returns to zero new captures and clears previous
identity/log without reusing event sequence IDs. All software, simulator
and GPIO3 reader paths must honor the same core gate.

## 10. Acceptance for issue #127

- [x] Review inspected existing boot counter semantics, core log, board clock, BLE auth, backend ingestion, visit aggregation, site attribution and trust contracts.
- [x] Security-time/bootstrap is separated from observation-time reconstruction, including anti-replay and conservative asymmetric error bounds.
- [x] All 0/1/2-powerloss and reset scenarios have falsifiable test vectors; unknown downtime stays unknown.
- [x] Storage/wire identity, crash consistency, backward compatibility, separate raw/anchor ACK evidence and no raw rewrite are explicit.
- [x] No silent historical FeedingSite reassignment and no guessed exact DerivedVisit from uncertain intervals.
- [x] Product gate specified: only durably CLAIMED Nodes may persist RFID observations; no capture in UNCLAIMED state, regardless of whether UTC is initialized. Firmware/tests must implement this in #128/#134.
- [x] Dedicated adversarial architecture/security **self-review requested by the maintainer**, followed by fixes and a separate final consistency pass. A third-party security audit remains recommended **before production deployment**; it is not claimed as completed.

### Review findings and resolutions (2026-10-11)

- **MAJOR — bootstrap time bias / credential expiry:** treating server issuance UTC as exact Node acceptance UTC was incorrect. Define asymmetric interval `[S-E_server, S+E_server+D]`, propagate drift, and require `L >= iat && U < exp` on every security-sensitive operation.
- **MAJOR — conflated observation and anchor ACK:** previously worded cleanup could retain raw records indefinitely pending a *future* anchor. Raw records may follow the existing durable PWA high-watermark ACK; evidence receives its **own** durable transfer, receipt and retention lifetime. Late anchors rebuild projections of already-uploaded raw data.
- **MAJOR — insufficiently bound pre-claim device key:** an unclaimed device's challenge now carries its actual 65-byte public key plus fingerprint; the signed challenge binds both. This proves possession only, **not** organization ownership.
- **MAJOR — ambiguous cross-platform signatures:** exact domain-separated, fixed-length canonical Node challenge and acceptance signing bytes, compact JWS binding and verification responsibilities specified. Golden vectors required before implementing sign/verify.
- **MINOR — absent explicit interval/iat test:** add a numerical server-delay test vector, JWT near-boundary rule, temporary newly issued JWT `iat` race handling and a fail-closed clock-bootstrap state diagram.

This review is limited to the ADR's design, signatures/schema contract and current-source compatibility. **No new backend/firmware code was implemented or compiled by this PR**; #128–#134 must prove the design through tests.

**No physical Android/ESP32 testing has been performed as part of this ADR.** Hardware acceptance is tracked in #134.
