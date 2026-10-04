# Simulator — Deterministic Firmware-Core + BLE/Security Simulator (Muse Spark 1.3)

This directory contains a hardware-independent simulator for the `firmware-core` library (issue #5),
extended with the real BLE synchronization, authorization and node-claiming behavior (issue #7).
It provides deterministic fakes for hardware ports, a simulated BLE transport with a
collector/session test double, and a human-readable scenario engine for end-to-end testing.

No second protocol/security implementation: the simulator exercises the production
`SyncServer`, `GattRouter`, `ble_codec`, `contiguous_watermark`, `Core` and
`OfflineAuthSession` through simulated transports and deterministic fixtures.
A scenario fails if production behavior changes incompatibly.

## Components

The simulator implements the following fakes around the real `firmware-core` interfaces:

| Component | Port | Purpose |
|-----------|------|---------|
| `SimRtc` | — | Simulated RTC hardware (survives reboot/factory-reset) |
| `SimClock` | `Clock` | Controllable monotonic + wall-clock time |
| `SimRfidReader` | `RfidReader` | Scripted RFID read queue |
| `SimStorage` | `Storage` | Controllable boot-gate availability |
| `SimRandomSource` | `RandomSource` | Deterministic seeded entropy |
| `SimObservationStore` | `ObservationStore` | In-memory log with fault injection |
| `SimDeviceIdentity` | `DeviceIdentityStore` | Identity cell with fault injection |

No firmware business logic is duplicated. The fakes wrap the `InMemoryObservationStore` and `InMemoryIdentityStore` from `firmware-core` (which model durable flash/NVS) and add scenario-level fault injection.

## Scenario Format

Scenarios are simple, human-readable, versioned text files (`.scenario`). The format is line-based:

```
# Comment lines start with #
name: scenario-name          # required
version: 1                   # required (format version)
description: optional text
capacity: 4096               # optional, observation store capacity (decimal or 0x hex)
debounce_ms: 2000            # optional, debounce interval in ms (decimal or 0x hex)
seed: 42                     # optional, random seed (decimal or 0x hex, e.g. 0xA5A5A5A5A5A5A5A5)

boot                         # events follow
rtc SYNCED 1700000000000
read CHIP-001
assert observations 1
```

### Supported Event Types

| Event | Arguments | Description |
|-------|-----------|-------------|
| `boot` | — | Create Core, call `initialize()` |
| `reboot` | — | Destroy Core, create new, call `initialize()` |
| `factory_reset` | — | Destroy Core, create new, call `factory_reset()` |
| `advance_ms` | `<ms>` | Advance monotonic clock (decimal or 0x hex) |
| `set_ms` | `<ms>` | Set monotonic clock to value (decimal or 0x hex) |
| `rtc` | `<STATUS> [epoch]` | Set RTC: `SYNCED epoch`, `RTC_ONLY epoch`, or `UNKNOWN` |
| `rtc_invalid` | — | Set RTC to `UNKNOWN` |
| `rtc_correct_ms` | `<epoch>` | Correct RTC to `SYNCED` with epoch |
| `read` | `<chip_id>` | Queue chip and poll reader once |
| `read_absent` | — | Poll reader with no tag |
| `expect_read` | `<chip> <RESULT>` | Read and assert result |
| `ack` | `<seq>` | Call `set_ack_watermark(seq)`, record true/false in `last_ack` |
| `expect_ack` | `<seq> <true\|false>` | Ack and assert result (`ok`/`fail` aliases) |
| `storage_available` | — | Set `Storage::open()` to true |
| `storage_unavailable` | — | Set `Storage::open()` to false |
| `fail_load_next` | — | Next identity load fails |
| `fail_identity_store` | `<n>` | Nth identity store fails |
| `crash_identity_store` | `<n>` | Nth identity store commits, then power loss |
| `fail_append` | `<n>` | Nth observation append fails |
| `crash_append` | `<n>` | Nth observation append commits, then power loss |
| `fail_clear_next` | — | Next `clear()` fails |
| `crash_clear_next` | — | Next `clear()` commits, then power loss |
| `fail_watermark_next` | — | Next `set_ack_watermark` fails |
| `fail_prune_next` | — | Next `prune_acked()` fails |
| `crash_prune_next` | — | Next `prune_acked()` commits, then power loss |
| `sync_batch` | `<from_seq> [max_records]` | Legacy direct SyncServer batch (kept for #22 scenarios 027–031) |
| `sync_ack` | `<watermark>` | Legacy direct SyncServer ACK (kept for #22) |
| `sync_compact` | — | Legacy direct compact (kept for #22) |
| `sync_status` | — | Legacy direct status (kept for #22) |
| `sync_claim` | — | Legacy CLAIMED setup for #22 (direct store + reboot; new scenarios use `claim_as`) |
| `phone_connect` / `ble_connect` | `[fixed\|alt]` | GATT connect with deterministic challenge (default `fixed` 00..1f; `alt` for replay tests) |
| `phone_disconnect` / `ble_disconnect` / `disconnect` | — | Drop BLE connection (session cleared, collector survives) |
| `reconnect` / `ble_reconnect` | `[fixed\|alt]` | Disconnect + connect with selected deterministic challenge |
| `hello` | `[client_ver]` | HelloRequest via real router (default ver 1) |
| `owner` | — | Public OwnerRequest via real router (no auth needed) |
| `provide_credential` / `auth` | `<member\|admin\|foreign\|expired\|vector\|manipulated\|wrongkey\|replay>` | AuthRequest with canonical fixture JWT + proof via real `OfflineAuthSession` |
| `claim_mode_enter` / `claim_mode_exit` | — | Physical claim-mode trigger port (#4 fake) |
| `claim_as` / `claim` | `<admin\|member\|foreign>` | Claim attempt: `admin` verifies the precomputed receipt with the host `SimClaimCrypto` (real ES256/JWT, trusted-time validity) through production `NodeIdentityManager`; `member` models backend issuance rejection (ADMIN-only); `foreign` models takeover rejection on CLAIMED nodes |
| `import_fixture_node` | `<sim-main\|vector>` | Test setup: import canonical fixture identity (node_id + P-256 public + issuer anchor), clear node ownership, retain collector, reboot |
| `collector_persist` | `[seq...]` | Idempotently persist last `ble_batch` records (no args = all; seq args = subset to model transfer gaps like missing 102) |
| `send_ack` / `collector_ack` | `[watermark]` | Collector ACK: no args = auto high-watermark via production `contiguous_watermark`; explicit watermark for invalid-jump tests |
| `drop_ack` | — | Model lost ACK (persisted but frame never reached the node; retry with `send_ack`) |
| `ble_status` | — | StatusRequest via real router (auth-gated) |
| `ble_batch` / `request_batch` | `<from> [max] [disconnect_after_bytes]` | BatchRequest via real router + codec (auth-gated, paginated) |
| `ble_ack` | `<watermark>` | Low-level explicit ACK via real router (for gap-rejection tests) |
| `ble_compact` | — | CompactRequest via real router (frees only the ACKed prefix) |
| `ble_time` | `<epoch_ms>` | TimeCorrectRequest via real router (future reads only) |
| `node_proof` | `[fixed\|fake]` | Node authenticity proof with fixed PWA nonce (`fixed` replays the fixture signature verified with the pinned node key; `fake` forces signer failure) |
| `ble_version` | `<ver>` | Raw frame with version `<ver>` (expects `UNKNOWN_VERSION` without protected data) |
| `ble_malformed` | — | Truncated frame (expects `INVALID_FRAME`) |
| `now` | `<epoch_s>` | Trusted UTC seconds for expiry checks (never a PWA clock; `0` fails closed) |
| `sync_start` | — | Marker asserting connected + authorized (fails otherwise) |
| `sync_complete` | — | Marker asserting connected + authorized + pending 0 |
| `assert` | `<type> [args...]` | Assertion (see below) |
| `repeat` | `<n>` | Start repeat block (no nesting) |
| `end` | — | End repeat block |

### Assertion Types

```
assert observations <n>
assert next_sequence <n>
assert boot_counter <n>
assert store_status <OK|NEARLY_FULL|FULL>
assert last_result <RECORDED|DEBOUNCED|REJECTED_FULL|REJECTED_INVALID|NOT_READY|IO_ERROR>
assert last_ack <true|false>
assert ack_watermark <n>
assert ready
assert not_ready
assert node_id <uuid|unchanged|changed>
assert incarnation <uuid|unchanged|changed>
assert observation <idx> <field> <value>
assert sequences_unique
assert sequences_monotonic
assert ble_connected
assert ble_disconnected
assert ble_authorized
assert ble_unauthorized
assert last_auth <ok|fail>
assert last_claim <ok|fail>
assert last_node_proof <ok|fail>
assert last_time <ok|fail>
assert claim_state <CLAIMED|UNCLAIMED>
assert claim_mode <on|off>
assert owner_org <uuid|empty>
assert last_owner_org <uuid>
assert collector_persisted <n>
assert collector_watermark <n>
assert collector_has <seq>
assert collector_missing <seq>
assert last_hello_error|last_batch_error|last_ack_error|last_status_error|last_compact_error|last_frame_error|last_owner_error <code>
```

Error codes are the v1 `SyncError` numbers (`0 OK`, `1 UNKNOWN_VERSION`, `2 UNAUTHORIZED`,
`4 INVALID_FRAME`, `5 INVALID_SEQUENCE`, ...). Failure output always identifies the
scenario, failing event/index, expected vs actual, and the protocol/session state
(node/organization/sequence/collector/claim-mode/trusted-now).

Observation fields: `sequence`, `chip_id`, `clock_status`, `epoch_ms`, `monotonic_ms`, `boot_counter`, `node_id`. Use `null` for absent epoch.

### Repeat Blocks

```
repeat 3
  read CHIP-001
  advance_ms 3000
end
```
Expands to 3 copies of the inner events. No nesting allowed.

## Running Scenarios

### Build

```bash
cmake -S . -B build -DCMAKE_BUILD_TYPE=Release
cmake --build build --config Release --parallel
```

### Run a Single Scenario

```bash
./build/simulator/scenario-runner ./simulator/scenarios/001-single-read.scenario
```

### Run All Scenarios (CTest)

```bash
ctest --test-dir build -C Release --output-on-failure
```

This runs:
- `simulator-scenarios` — unit tests for parser and runner
- `scenario-XXX` — each scenario file as a separate test

## Fault Injection

The simulator supports deterministic fault injection at meaningful persistence boundaries
and at controlled BLE points:

| Event | Injection Point |
|-------|-----------------|
| `fail_load_next` | Identity load (boot/reboot/factory_reset) |
| `fail_identity_store N` / `crash_identity_store N` | Sequence reservation (N=1), factory-reset journal/completion, claim commit |
| `fail_append N` / `crash_append N` | Observation append (creates real sequence gaps for high-watermark tests) |
| `fail_clear_next` / `crash_clear_next` | Factory-reset observation log clear |
| `fail_watermark_next` | `set_ack_watermark` |
| `fail_prune_next` / `crash_prune_next` | `prune_acked` / compaction |
| `phone_disconnect` mid-batch | Link loss between `ble_batch` pages (resume at `ack+1`, no loss) |
| `drop_ack` | Lost ACK after durable collector persist (node retransmits idempotently) |
| `claim_as` with `fail_identity_store` armed | Interrupted claim commit (no half-claimed state, deterministic retry) |

- `fail_*` — operation returns error, nothing committed.
- `crash_*` — operation commits, then `PowerLoss` thrown (simulating power loss after durable write). `crash_clear_next` clears the log before throwing; `clear()` is idempotent so the pending factory reset resumes.
- Faults arm until the consuming operation triggers them (`boot`/`reboot` for loads, `read`/`expect_read` for appends/stores, `factory_reset` for journal/clear, `ack`/`expect_ack` for watermarks, `sync_batch`/`sync_ack`/`sync_compact`/`ble_batch`/`ble_ack`/`ble_compact`/`claim_as`/`send_ack` for sync/claim). Faults persist across unrelated mutating ops; they are cleared on trigger or on PowerLoss resume.
- An armed fault (fail or crash) that never reaches its consuming operation fails the scenario at the end.
- BLE faults never duplicate protocol logic: disconnect/ACK-loss only orchestrate the real
  `GattRouter`/`SyncServer` transport; watermark math always uses the production
  `contiguous_watermark`.

Reboot semantics: volatile Core state (debounce cache, boot_counter increment) and the BLE
session/authorization plus the physical claim-mode flag are lost; durable media
(`SimObservationStore`, `SimDeviceIdentity`, `SimRtc`, `SimStorage`, `SimOwnerStore`,
`SimNodeKeys`) survive. The phone-side `SimCollector` outbox intentionally survives node
reboot and factory reset (keyed by `(node_id, sequence)` so old identities never satisfy
new watermarks). Power loss throws `PowerLoss` from the store; the runner destroys the Core
and continues with the next event (typically `reboot`).

## Required Scenarios (Implemented)

Core scenarios from #22 (unchanged prerequisites):

| # | Scenario File | Coverage |
|---|---------------|----------|
| 1 | `001-single-read.scenario` | Single RFID read |
| 2 | `002-many-reads-same-chip.scenario` | Many reads of same chip |
| 3 | `003-technical-dedup.scenario` | Technical deduplication |
| 4 | `004-multiple-chips.scenario` | Multiple chip IDs |
| 5 | `005-reboot-no-data-loss.scenario` | Reboot without data loss |
| 6 | `006-stable-node-id.scenario` | Stable node_id across reboot |
| 7 | `007-monotonic-sequence.scenario` | Monotonic sequence across reboot |
| 8 | `008-factory-reset.scenario` | Factory reset creates new identity |
| 9 | `009-power-loss-observation.scenario` | Power loss during observation persist |
| 10 | `010-power-loss-sequence.scenario` | Power loss around sequence allocation |
| 11 | `011-invalid-rtc.scenario` | Invalid/unknown RTC |
| 12 | `012-rtc-correction-future-only.scenario` | RTC correction affects future only |
| 13 | `013-rtc-correction-persisted-unchanged.scenario` | Past observations unchanged after RTC correction |
| 14 | `014-many-records-no-sync.scenario` | 5000 records without sync |
| 15 | `015-storage-nearly-full.scenario` | Storage nearly full |
| 16 | `016-storage-full.scenario` | Storage full |
| 17 | `017-no-silent-deletion.scenario` | No silent deletion of unacknowledged data |
| 18 | `027-sync-batch-request.scenario` | Sync batch request (legacy direct path) |
| 19 | `028-sync-ack-gap.scenario` | Sync ACK contiguous high-watermark (legacy) |
| 20 | `029-sync-compact.scenario` | Sync explicit compaction (legacy) |
| 21 | `030-sync-retry.scenario` | Sync retry after disconnect/power loss (legacy) |

BLE/security scenarios from #7 (real production path via `SimBleTransport`):

| # | Scenario File | Coverage |
|---|---------------|----------|
| 22 | `032-ble-smoke.scenario` | Minimal BLE path: import, claim, connect, auth, batch, persist, ACK |
| 23 | `033-normal-sync.scenario` | Authorized MEMBER sync; collector persistence before ACK; only ACKed freed |
| 24 | `034-ble-interruption.scenario` | Disconnect before transfer + mid-batch; reconnect resumes at `ack+1`, no loss |
| 25 | `035-lost-ack.scenario` | Dropped ACK after durable persist; idempotent retransmission; retry succeeds |
| 26 | `036-duplicate-transfer.scenario` | Same batch multiple times; no duplicate logical records; idempotent re-ACK |
| 27 | `037-protocol-compat.scenario` | Supported version, unknown version (`UNKNOWN_VERSION`), malformed (`INVALID_FRAME`), bad cursors |
| 28 | `038-claiming.scenario` | UNCLAIMED→CLAIMED by ADMIN; MEMBER rejected; outside mode fails; interrupted retry; foreign takeover rejected |
| 29 | `039-authz.scenario` | MEMBER/ADMIN same-org; foreign denied protected data but reads public owner; expired/manipulated/wrong-key/replay rejected; fake node proof fails |
| 30 | `040-identity-lifecycle.scenario` | Reboot retains identity/ownership; factory reset rotates identity with no org leak |
| 31 | `041-high-watermark.scenario` | Collector holds 100/101/103 without 102 → watermark 101; after 102 → 103; node gap rejects jumps (real #6) |
| 32 | `042-vector-interop.scenario` | Committed backend vector credential through the production verifier |

Additional scenarios for fault injection validation:
- `018-factory-reset-power-loss.scenario` — Factory reset power loss resume
- `019-rtc-rtc-only-to-synced.scenario` — RTC_ONLY to SYNCED correction
- `020-identity-load-failure.scenario` — Identity load IO_ERROR
- `021-identity-store-failure.scenario` — Identity store failure
- `022-append-failure.scenario` — Append IO_ERROR
- `023-invalid-chip-id.scenario` — Empty/overlong chip rejection
- `024-ack-watermark.scenario` — Ack watermark set/persist/fail paths
- `025-event-coverage.scenario` — `rtc_invalid`, `set_ms`, `read_absent`, `expect_read`, storage gate
- `026-clear-faults.scenario` — `fail_clear_next`/`crash_clear_next` resume paths
- `031-sync-prune-faults.scenario` — Failed compaction and power loss after pruning

## Adding a New Scenario

1. Create a new `.scenario` file in `simulator/scenarios/` (e.g., `043-my-feature.scenario`).
2. Follow the format above. Use `name`, `version: 1`, and appropriate events/assertions.
   For BLE scenarios start with:
   ```
   boot
   import_fixture_node sim-main
   claim_mode_enter
   claim_as admin
   rtc SYNCED 1790899200000
   read CHIP-001
   phone_connect
   provide_credential member
   ```
   then `ble_batch`/`collector_persist`/`send_ack` with fault injection
   (`phone_disconnect`, `drop_ack`, `reconnect`) as needed.
3. Run the single scenario locally:
   ```bash
   cmake --build build --parallel
   ./build/simulator/scenario-runner ./simulator/scenarios/043-my-feature.scenario
   ```
4. Re-run CTest — the new scenario is automatically discovered:
   ```bash
   ctest --test-dir build --output-on-failure
   ```

Additional review regressions run through CTest automatically:
- `043-claim-power-loss`: atomic claim and reset recovery, idempotent claim retry,
  usable same-org authorization and node proof after a committed claim crash.
- `044-collector-node-isolation`: old-node sequences cannot fill a new-node gap
  or ACK records that the phone has not persisted for that node.
- `045-claim-expiry`: signed claim receipts fail before issuance and at expiry.

## How a BLE Scenario Executes

1. `import_fixture_node sim-main` loads the canonical fixture identity
   (`protocol/fixtures/sim-ble-v1.json`) into the durable stores and reboots.
2. `claim_mode_enter` + `claim_as admin` verifies the precomputed claim receipt
   through production `NodeIdentityManager::apply_claim`. The manager checks
   ClaimMode, node/key binding and same-org retry; `SimDeviceIdentity` atomically
   stores keys, capture metadata, receipt and ownership. `SimClaimCrypto` verifies
   ES256 with the pinned issuer and checks validity against trusted `now`.
3. `phone_connect [fixed|alt]` creates a production `OfflineAuthSession` with the
   node's pinned issuer + claimed org and generates the deterministic fixture
   challenge through a wire `ChallengeRequest`/`ChallengeResponse`.
4. `provide_credential <kind>` sends a real `AuthRequest` frame through the real
   `GattRouter`/`SyncServer`; the real session verifies JWT signature, expiry
   against the scenario `trusted_now_s` (set via `now`), org binding, scope/role
   and proof-of-possession over the exact challenge bytes.
5. `ble_batch`/`ble_status`/`ble_ack`/`ble_compact`/`ble_time`/`node_proof` all go
   through `encode_*` → `GattRouter::handle_frame` → `decode_*` (never around it),
   so version gating, auth gating, cursor math, monotonic ACK and compaction are
   the production behaviors. `collector_persist` models durable PWA persistence
   (idempotent by `(node_id, sequence)`); `send_ack` computes the collector
   high-watermark for the current node only with production `contiguous_watermark`
   before sending. The outbox retains records for other nodes across reset/import.
   `ble_batch 1 16 20` cuts delivery after 20 response bytes, disconnects before
   decoding/persistence and leaves every unacknowledged record on the node.

## Production Components Reused (and Documented Gaps)

Reused without modification:
- `Core` (#5): capture, debounce, sequence, persistence contracts.
- `NodeIdentityManager` (#18): claim gates, binding, atomic ownership, retry,
  session-signing message construction and key rotation during Core reset.
- `SyncServer` (#6): gating, cursor math, monotonic ACK, explicit compaction.
- `GattRouter` (#6): version negotiation, frame routing, public-vs-protected gating.
- `ble_codec` (#6): every wire byte in both directions.
- `contiguous_watermark` (#6): collector high-watermark and node gap checks.
- `OfflineAuthSession` (#17): offline credential + proof verification (real ES256/JWT).

Host test doubles (no competing protocol logic; see `sim_ble.hpp`):
- `SimBleTransport` orchestration + `SimCollector` outbox (phone-side Dexie model
  without browser concepts) + deterministic challenge injector (board CSPRNG stand-in).
- `SimDeviceIdentity`: atomic simulated NodeIdentity media with fail-before-write
  and crash-after-commit injection; ownership and keys are in one record.
- `SimOwnerStore`/`SimNodeKeys`: public views rebuilt from NodeIdentity after boot
  and reset. `SimClaimMode` implements the volatile physical ClaimMode port.
- `SimClaimCrypto` (`NodeCrypto`): host JWT/ES256 verifier for claim receipts with
  the same strict JWS rules. No ESP32 `NodeCrypto` adapter exists yet (#4); this host
  verifier follows ADR-0012/0013 and is pinned by canonical fixtures. Genuine upstream
  limitation: board flash/key adapter + GATT observation gates still integrate in #4/#6/#8.
- `SimBackendIssuer`: ADMIN-only issuance stub mirroring backend `NodeClaimTest` role
  gates (node-side receipt/binding/mode checks stay real).
- Node/Collector ECDSA *verification* uses the same mbed-TLS P-256/SHA-256 primitive as
  production; valid signatures are precomputed in fixtures (no dynamic signing in the
  simulator, no fixture private keys committed). Reset generates deterministic
  test-only P-256 keys to exercise production key-rotation checks. Imported
  identities carry an opaque signing handle, not their original private scalar.

Legacy `sync_claim`/`sync_batch`/`sync_ack`/`sync_compact`/`sync_status` (scenarios
027–031) keep the original dummy-authorized `SyncServer` path for #22 compatibility;
new `ble_*`/`phone_*`/`claim_*`/`collector_*` events use the real session path above.

## Fixtures Shared with #8/#13

- Canonical: `protocol/fixtures/sim-ble-v1.json` (test-only keys; MEMBER/ADMIN/foreign/
  expired JWTs, fixed/alt challenges + proofs, orgA/orgB claim receipts, claim
  advertisement, node session proof, owner metadata) plus the existing
  `protocol/fixtures/offline-credential-v1.json` and `protocol/fixtures/ble-sync-v1.json`.
- Generated once via `protocol/fixtures/generate_sim_ble.py` (Python `cryptography`,
  real ES256; no private keys committed; signatures are single valid examples).
- The simulator loads them at runtime from `MIEZMERKER_FIXTURES_DIR` (compile definition
  pointing at `protocol/fixtures`) with the same cJSON strictness as the node, so PWA
  collector tests (#8) and end-to-end tests (#13) can load the identical JSON without
  copying blobs. No browser-specific concepts leak into the fixtures.

## Architecture Notes

- **firmware-core** owns all firmware/domain behavior (sequence allocation, debounce, persistence contracts).
- **simulator** owns time, hardware fakes, BLE orchestration, fault injection, and scenario orchestration.
- **scenario runner** owns deterministic event execution and assertions (now including
  protocol/session/collector/claim state in every failure diff).
- No hidden global state except the deterministic challenge injector (documented fixture
  source for `OfflineAuthSession::begin`; production uses hardware CSPRNG).
- Reboot semantics: volatile state (Core RAM, BLE session, claim-mode flag) resets;
  persistent simulated state survives per #5 contracts.
- Factory reset is clearly distinct from reboot (new node_id, new incarnation, new sequence lifetime; ownership cleared, keys rotated).
- Power-loss testing: deterministic fault injection around the persistence strategy from #5.

## Requirements

- CMake ≥ 3.22
- C++20 compiler (MSVC, GCC, or Clang)
- No ESP32 hardware or toolchain required
- No network dependency
- Deterministic execution
