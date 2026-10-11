# 0017 — Boot-Epoch Time Reconstruction and Trusted-Time Bootstrap

Status: **Proposed for architecture/security review** — issue #127, epic #126  
Date: 2026-10-11  
Related: #3, #4, #5, #6, #7, #8, #10, #13, #17, #18, #76, #108, #121, #128–#134

## Decision

An external battery-backed RTC is **optional**, not a requirement for a low-cost MiezMerker Node. The firmware already retains unique per-node sequences, a persistent boot counter and monotonic uptime. For every read we retain these immutable facts. Separately persisted UTC **time anchors** can reconstruct observation time within the **same boot epoch**. Across unanchored boot epochs we know event ordering and intra-boot elapsed time, but **cannot reconstruct unobserved powered-off durations**. We expose conservative possible intervals rather than invented exact timestamps.

**Observation-time estimates and security-trusted UTC are separate trust domains.** Only a backend-signed, fresh, challenge-bound UTC proof, validated against an **independently pinned** issuer key, may initialize the time required for offline credential expiration and secure claiming. Phone time is untrusted. The current protected BLE TimeCorrect operation must remain protected. Without security-trusted UTC the node continues storing raw RFID events but does not authorize protected BLE reads, ACKs, time correction or claims.

This ADR is the **design contract for later implementation**, not a claim of completed device functionality, hardware validation or accepted security review.

## 1. Existing-code audit and compatibility constraints

- Firmware type RawObservation already contains node_id, incarnation, sequence, clock_status, optional observed_at_epoch_ms, monotonic_ms (u64), boot_counter (u32). See firmware-core/include/miezmerker/types.hpp and protocol/ble/messages.md.
- Core::initialize in firmware-core/src/core.cpp already persists a boot_counter increment before accepting reads after a normal reboot; fresh identities begin at 1. Reuse this rather than inventing a second unrelated counter. Fail closed at counter overflow. Keep the existing identity + sequence + reset journal.
- Current ESP32 BoardClock in firmware-esp32/idf/main/board.hpp has monotonic time but reports wall clock UNKNOWN; epoch_ms() is zero and time setting is disabled. Security bootstrap is **not yet implemented**.
- ESP32-C3 internal RTC time can survive some resets and sleep but **not a power-on reset**. The high-resolution esp_timer_get_time is since initialization and resets after restart/Deep Sleep. The reset reason is a diagnostic, not a power-off duration sensor. See [Espressif System Time](https://docs.espressif.com/projects/esp-idf/en/v6.0-rc1/esp32c3/api-reference/system/system_time.html), [ESP Timer](https://docs.espressif.com/projects/esp-idf/en/v5.4.2/esp32c3/api-reference/system/esp_timer.html), and [reset reasons](https://github.com/espressif/esp-idf/blob/master/components/esp_system/include/esp_system.h).
- RawObservation.java already persists bootCounter and monotonicMs with unique (node_id, sequence) identity; ObservationIngestService.samePayload rejects changes to an already-ingested raw payload.
- ObservationIngestService **intentionally leaves feeding_site_id/deployment_id null** for UNKNOWN timestamps. VisitAggregationService visit-gap-v1 excludes UNKNOWN timestamps and unattributed sites. Reconstructing a time must NOT silently attribute the event to today's FeedingSite.
- ADR 0012 (observations), ADR 0012 (offline credentials), ADR 0013 (node claiming), ADR 0015 (BLE) and ADR 0016 (tenant sharing) remain authoritative on identity, security and privacy.

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
  utcMs: signed 64-bit UTC epoch
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
- PHONE_ESTIMATE: untrusted convenience estimate, **never** permits credential/claim authorization. Defer ingestion into MVP if it cannot be safely bounded.
- RTC_HOLDOVER: an already initialized clock continued through a demonstrably continuous interval with conservative drift. Merely reading the ESP RTC after power-on is not evidence of safe continuity.
- UNKNOWN: no useful reliable UTC. Zero / 1970 placeholders are not timestamps.

The current BLE enum UNKNOWN / RTC_ONLY / SYNCED is *capture state*, **not** a standalone proof of security-trusted time. Temporal projection must check real provenance; preserve legacy visit-gap-v1 behavior separately instead of silently redefining it. For a new strict time-projection version, do not treat RTC_ONLY as cryptographically verified just because observed_at_ms is nonzero.

Both the Node's time proof acceptance and its boot/anchor metadata must be exported as durable, authenticated evidence. A token issued by the backend is **not by itself evidence** the Node installed that token. Keep a verifiable Node acknowledgment/binding record. Loss or partial writes may downgrade confidence, never manufacture trust.

## 4. Projection math and uncertainty

For an RFID event at uptime U_event and a verified anchor with uptime U_anchor, UTC T_anchor **from that SAME boot**, the nominal estimate is:

~~~text
T_event = T_anchor - (U_anchor - U_event)
elapsed = abs(U_anchor - U_event)
drift_budget_ms = ceil(elapsed * drift_ppm_bound / 1_000_000)
[earliest, latest] =
  [T_event - anchor_uncertainty_minus - drift_budget_ms,
   T_event + anchor_uncertainty_plus + drift_budget_ms]
~~~

All calculations use checked integer arithmetic and UTC; the UI localizes time. The uncertainty includes backend time-source error, the bounded **entire challenge/response delay**, local sampling latency, quantization and oscillator drift/operating-mode assumptions. Signed does not imply mathematically exact. A candidate drift bound is configurable/reviewed and requires real measurement for production calibration; the ADR makes **no unsupported physical ppm guarantee**.

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
| ESTIMATED | Untrusted phone/RTC estimate; display "Schätzung – nicht bestätigt"; cannot drive secure auth |
| UNKNOWN | No defensible absolute time; display "Zeitpunkt unbekannt" |
| CONFLICT | Contradictory evidence; do not select a favored timestamp |

These DTO qualities are independent of the existing immutable on-wire capture enum. No legacy observation may acquire exact dates merely because data is missing.

## 5. Trusted UTC bootstrap: wire handshake and threat model (#129/#130/#131)

**Absolute invariant:** Without trustworthy current UTC, no JWT expiration or future-iat validation, secure claiming, protected GATT operation, ACK or compaction. Existing valid per-AppDevice proof-of-possession and ACTIVE-organization membership remain mandatory **after** time bootstrap.

**Pre-auth bootstrap is a new narrowly scoped capability, NOT removal of protection on the existing TimeCorrect GATT characteristic.**

1. **Node challenge:** Device emits a new, single-use, 32-byte CSPRNG challenge, node ID, incarnation, boot counter, issuance monotonic uptime and protocol version. A new nonce supersedes the previous. Optionally domain-separate and sign a possession proof using its persistent P-256 device key so the backend can bind the request to a real private-key holder. Public bootstrap must not reveal chip numbers, pending counts or owner secrets.
2. **Authenticated PWA:** Browser-freigegebene PWA requests a time proof from the backend. For an owned node, authenticated ACTIVE member of that owner org; for a first unclaimed node, ACTIVE ADMIN of the selected organization, plus regular physical claim-mode checks during the actual claim. Rate limits, request bounds and abuse logging apply. Never trust a caller-supplied owner identifier over backend-verified ownership. No arbitrary-time-signing endpoint.
3. **Backend signing:** Server samples **its own authoritative UTC** and issues a domain-separated ES256 token, purpose "MM-TRUSTED-TIME-v1", carrying issuer/key ID, version, time in UTC milliseconds, node ID, incarnation, boot counter, fresh challenge, and node key fingerprint where available. The PWA cannot submit a UTC to be signed. Server must withhold issuance if its own time source is unhealthy. The public signing key must be provisioned independently on the node.
4. **Node validation:** ESP verifies signature under independently pinned public key (existing CONFIG_MM_ISSUER_PUBLIC_KEY_HEX or separately pinned time key), purpose, key ID, boot/node/nonce binding, and single-use state. **Never** bootstrap trust from the signed token's embedded public key, from an unverified claim receipt or from a public /issuer response alone.
5. **Freshness without any pre-existing clock:** A signed issuance timestamp **cannot prove its own freshness**. Freshness instead relies on nonce unpredictability plus a short **monotonic challenge-to-acceptance deadline** (provisional maximum 30 s, to be confirmed in tests). The accepted UTC interval includes full round-trip elapsed uncertainty. Old proofs after reboot or a stale nonce fail. No post-reset reuse of stored time proof.
6. **Persistence/activation:** Node atomically commits its verified time anchor before publishing a valid security clock, minting a fresh signed claim advertisement or authorizing a time-dependent credential. After a crash before commit, remain UNKNOWN. All attempts consume/retire the nonce, even invalid ones.
7. **During operation:** Maintain trusted UTC as monotonic elapsed since the verified anchor with a conservative growing error envelope and finite maximum age. When a JWT iat/exp boundary falls **inside the uncertainty interval**, deny rather than treat validity as certain. Recheck can_sync at every protected GATT operation. A new boot starts UNKNOWN under this MVP policy, even after a soft reset, unless a later explicit continuity design is proven. Detect rollback/clock jumps; invalidate and rebootstrap instead of silently moving the trusted clock backwards.

The physical claim gate from ADR 0013 remains required. Signing backend time does not itself register a node, issue BLE credentials, read observations, or authorize any session. Authenticated time initialization and authenticated observation transfer are **separate** transitions.

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

After a full power loss and **without internet access on the phone**, there is no fresh backend-signed UTC proof. The node can still capture and durably retain RFID observations, but cannot complete authenticated BLE sync until a legitimate client reaches the backend for a fresh proof. Do **not** hide this limitation or switch to unsigned phone time to bypass it. Optional external RTC support (#4) is a separate future enhancement with its own trust/rollback rules.

## 6. BLE, durability and ACK semantics (#131)

Use additive versioned TIME_BOOTSTRAP request/response opcodes and capability bits in protocol/ble; existing v1 authorized TimeCorrect, authorization and binary observation layout stay unchanged. Handle signed proof length with bounded, offset-validated BLE fragmentation as needed. No silent fallback to unauthenticated TimeCorrect. The Web Bluetooth chooser still requires a user gesture and getDevices() does not scan unknown hardware.

For evidence required to interpret a batch:
- Node persists the accepted anchor/boot evidence before treating any new security time as valid.
- PWA durably stores raw bytes and the associated boot/anchor evidence before Node ACK.
- Backend durably stores authorized raw observations **and** associated time evidence before the PWA declares them completely uploaded/reconstructable.
- A Node may compact/prune only when the necessary evidence has a verifiable durable downstream copy; a signed backend issuance alone does not prove device acceptance. Distinguish crash points/retries/idempotent retransmission from successful upload. Design an explicit evidence ACK if the existing observation watermark cannot express that condition.
- Original RawObservation payloads, sequence and samePayload must remain byte-stable for duplicate retries. Neither clock correction nor late anchor updates modify them.
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

**TV5 – latency/expiry.** A signed token received after monotonic challenge deadline is rejected. If JWT expires within the clock's uncertainty interval, authorization fails closed; tampered phone date never helps.

**TV6 – site ambiguity.** The node moved between X and Y while its absolute time was uncertain. No derived site unless the *entire* defensible time interval belongs to one deployment.

**TV7 – interrupted commit/ACK.** Reset at each durable boot-marker/anchor write, PWA persist, ACK, backend upload and compaction boundary. No duplicate Node/sequence; evidence needed for ACKed data remains durable.

**TV8 – backward compatibility/tenant security.** Old v1 observations, conflicting RTC times, unknown timestamps, untrusted uploads, wrong-org data, forged node proof, timer wrap, unknown boot ID and site changes cannot become fictitiously precise or bypass authorization.

## 10. Acceptance for issue #127

- [ ] Review validates existing boot counter semantics, core log, board clock, BLE auth, backend ingestion, visit aggregation, site attribution and trust contracts.
- [ ] Security-time/bootstrap is separated from observation-time reconstruction, including real anti-replay and error bounds.
- [ ] All 0/1/2-powerloss and reset scenarios have falsifiable test vectors; unknown downtime stays unknown.
- [ ] Storage/wire identity, crash consistency, backward compatibility, ACK evidence and no raw rewrite are explicit.
- [ ] No silent historical FeedingSite reassignment and no guessed exact DerivedVisit from uncertain intervals.
- [ ] **Independent architecture/security review** approves this proposed ADR before #128–#133 are considered unblocked.

**No physical Android/ESP32 testing has been performed as part of this ADR.** Hardware acceptance is tracked in #134.
