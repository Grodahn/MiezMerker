# Issue #128 — boot epochs and CLAIMED-only capture

Contract: [#128](https://github.com/Grodahn/MiezMerker/issues/128) and
[ADR 0017](adr/0017-boot-epoch-time-reconstruction.md). No external RTC, new
BLE opcode, backend signer, temporal projection or security bootstrap is added.

## Capture and clocks

`Core::record_chip_read` reloads and validates durable ownership before chip
validation, debounce, reservation or append. Node/incarnation, sequence, boot
counter and reset journal must agree with the active Core. UNCLAIMED returns
`REJECTED_UNCLAIMED` without storing a chip, sequence or deferred observation.
Only the existing verified #18 receipt's atomic CLAIMED commit enables capture.
Backend reservation, valid UTC and BLE sessions cannot grant capture. Claimed
nodes capture UNKNOWN/null UTC even with incomplete FeedingSite setup.

Reader polling consumes denied inputs. GPIO3 retains no selected chip and
diagnostics omit chip IDs; synthetic commands use the same Core gate.
Production builds still forbid development mocks. Factory reset disables
capture immediately and clears RAM debounce state before rotating identity.

The existing `(node_id, incarnation, boot_counter)` identifies the sole boot
epoch. Counter reservation commits before ready; failed/ambiguous commits
disable capture. Reinitialization reloads durable counters and reserves a new
epoch; gaps are safe. Zero, exhaustion and observed rollback fail closed.
NodeIdentityManager rejects persisted counter regression. Boot evidence also
prevents reuse of its existing tail after identity rollback.

ESP32 converts the signed 64-bit `esp_timer_get_time()` microsecond sample to
64-bit milliseconds only after checking negative/decreasing samples. Invalid
samples latch the BoardClock until a real restart. Core also detects decreases
across different chips with debounce off. A new initialization reserves a new
epoch. Deep Sleep, power-on, software, brownout and watchdog restarts all run
app initialization; `esp_reset_reason()` is an opaque diagnostic, never an
estimate of powered-off duration. No wall-clock rollback heuristic or
cross-epoch uptime subtraction is introduced.

Raw observation identity, wire layout, original clock status/UTC and monotonic
samples remain immutable. Evidence neither mutates records nor activates clocks.

## Separate evidence persistence and recovery

`TimeEvidenceStore` provides versioned boot and anchor records. Boot metadata
contains node/incarnation/boot, reset reason, startup uptime and first possible
sequence reservation; this is diagnostic, not a claim that a read occurred.
Actual first/last reads remain identifiable from raw sequences. Anchors refer
to retained boots without rewriting them.

Anchors retain stable ID, node/incarnation/boot, acceptance uptime, signed
64-bit UTC issuance value, source, asymmetric uncertainty, drift bound, optional
proof ID/digest and exact proof/acceptance bytes, including binary NUL bytes.
Equal retries do not write; conflicting retries, malformed records and absent
boot associations fail. No anchors are fabricated by firmware. Deterministic
unverified fixtures exercise persistence only.

`PersistentTimeEvidence` reuses AtomicBlob, bounded serialization and CRC. The
board uses key `time_v1` in existing `node_state`, separately from `state_v1`.
Partition geometry, identity/key serialization and observation format stay
unchanged. Atomic replacement recovers the complete old or new blob; ambiguous
failure latches the adapter until reopen/reboot. Corruption never causes erase.
The boot counter is committed before evidence, so interrupted initialization
can leave gaps/markers without observations, never authorize epoch reuse.

Each lifetime is bounded to **128 boots, 16 anchors and 20,000 serialized bytes**.
Proof/acceptance bounds are 4096/512 bytes; proof ID/digest is at most 128 bytes.
Large proofs can exhaust the byte budget before the count limit. Full evidence
storage fails closed without deleting data. Until #131 adds independent durable
receipts/pruning, reaching this ceiling stops new boot initialization/capture.
Writes occur at boot, evidence append and intentional reset, never timer ticks;
idempotent retries perform no writes. Physical wear/drift remains unmeasured.

UNKNOWN observations retain existing persist-before-ACK watermarks and may be
compacted before an anchor exists. Raw compaction never touches evidence. A
late anchor for a retained boot can therefore be exported for already uploaded
records. Intentional factory reset clears old log and evidence under the fresh
identity reset journal; interruption keeps capture disabled until cleanup
resumes. Failed storage operations never silently erase old evidence.

## Validation and independent review

The affected host regression passes **59 CTests**, including existing BLE
codec, identity/claim, ACK/retry/compaction and recovery checks. New tests cover
direct/reader/synthetic/GPIO3 pre-claim denial, no sequence or queued replay,
failed claim commit, stale ownership, reset denial, 64-bit uptime/deltas, two
restarts, invalid timer/rollback, counter exhaustion and corruption. ESP32
adapter tests inject boot/anchor writes before/after commit, binary evidence
retry/reopen, reset purge interruption, bounded capacity and independent
evidence retention after raw ACK/compaction. Simulator 046 uses production
claim logic with signed fixture receipts and UNKNOWN reads across two resets.
Legacy capture scenarios explicitly mark host-only claimed fixtures; these
cannot alter attached signed-claim identities.

Independent adversarial code/security review against #128/#126/ADR found:

- **MAJOR, fixed:** binary acceptance/proof serialization rejected embedded
  NUL. Bounded opaque serialization and byte-exact retry/reopen tests fix it.
- **MINOR, fixed:** missing invalid-timer latch coverage. Added invalid-at-init,
  post-capture invalidity, failed reinit and evidence rollback regressions.

The reviewer rechecked both fixes and the boot-evidence reuse guard; no known
unresolved in-scope BLOCKER or MAJOR remains. ESP32 development and production
build outcomes and GitHub CI status are reported in the PR.

## Remaining dependencies

#129 supplies signed backend UTC. #130 supplies verification, bounded security
time and verifier-owned acceptance. This storage API currently **rejects
`verified_security_time=true`**; no caller/test can declare unverifiable time
trusted. #130 must extend that boundary before enabling verified storage.
#131 supplies separate evidence transfer/receipts/pruning; #132 temporal
projection; #133 visit/UI integration; #134 end-to-end acceptance. Existing
claim expiry/credential checks remain fail-closed with board UTC UNKNOWN.

**Hardware acceptance: UNVERIFIED.** Host fault injection/cross-compilation
does not prove real ESP32-C3, Android, RFID or physical power-loss behavior.
No hardware flash/test was performed by this change.
