# Observation compaction reliability (#163)

## Contract and verified implementation

The existing path remains `Batch -> durable PWA observations/evidence -> contiguous
ACK -> Compact`. Backend upload is independent; a node ACK proves local custody,
not server persistence. Existing evidence receipt/pruning requires backend
confirmation and is separate from observation compaction. No evidence, claim,
authentication or time format changes are required. No open parallel PRs were
present at implementation start; #131 is already integrated in the main baseline.

The confirmed defect was the PWA's early return on a sequence gap, before
compaction. It stranded the safely ACKed prefix on every subsequent visit while
the gap persisted. The engine now compacts that prefix, still reports incomplete
sync and retains every later, unACKed observation. An empty authorized visit
already retried compaction; this behavior is retained and covered explicitly.

The existing CompactResponse supplies `freed` and `remaining` occupied slots;
`before = freed + remaining` describes that atomic operation, not a pre-sync
snapshot. Status.pending counts only unACKed records and cannot measure occupied
storage. The PWA now exposes the slot measurements and reads status after Compact
to warn about FULL/NEARLY_FULL, including new captures during the visit. Lost or
failed maintenance responses retain local success and a next-visit retry warning.
An incomplete visit also preserves these measurements/warnings.

## Capacity and flash writes

The current ESP32 PersistentObservations capacity is **128**, not 2000:
`CoreConfig{2000, ...}` configures the capture debounce interval in milliseconds.
The observations NVS partition is 0x1b0000 bytes; the board blob limit is 20000
bytes. The serialized log is 26 bytes plus 63 bytes and the chip length per
record (at most 127 bytes): at capacity, at most 16282 bytes. Full compaction
returns it to a 26-byte durable header, retaining the high sequence and ACK.
Host tests assert blob shrinkage and refill all 128 slots for three cycles.

Append and advancing ACK rewrite the complete blob; compaction writes one smaller
blob only when records are removed. Repeated empty Compact/idempotent ACK perform
no writes. NVS performs its own page reclamation/wear levelling; byte shrinkage
and reusable logical capacity do not measure physical erase cycles or endurance.
No automatic erase is introduced. A failed/ambiguous commit latches writes until
reboot/reload, preventing stale memory from overwriting a possibly committed log.
Tests cover both old/new durable outcomes and safe retry after reboot.

## Review, tests and limits

Integrated review covered the entire issue diff, direct protocol/store/upload/UI
consumers, verified node/org/incarnation binding, durable evidence before raw ACK,
gap boundaries, authorization and failed commits. Compaction stays behind all
existing authentication and persistence gates. No new deletion authority or
security/temporal schema was added.

Follow-up PR review found that the maintenance catch also swallowed terminal
user/context cancellation during Compact or the new Status round trip. This
could report an obsolete visit as successful. Cancellation is now rethrown to
the existing terminal sync path, without reconnecting; durable observations and
the confirmed ACK stay intact. Both boundaries are covered with the production
operation guard and retained-outbox assertions. Ordinary BLE maintenance failures
still preserve local success with a warning.

Regression coverage includes firmware gap/prune/re-ACK, persisted adapter
ACK/compaction faults before and after commit, reboot, full/near-full/refill,
no-op writes, PWA lost responses/empty visits/status failures, retained gap
suffixes, and offline IndexedDB reopen followed by CREATED/DUPLICATE_IDENTICAL
backend confirmations. Existing evidence failure and upload isolation tests
remain required. Full development/production firmware and PostgreSQL/browser
coverage run in the existing final-head GitHub CI.

Physical ESP32 power-cut/NVS garbage-collection/endurance and Android BLE tests:
**UNVERIFIED**. A permanently absent sequence still requires a separately designed
protocol extension; it must never be skipped by ACK or deleted to recover space.
