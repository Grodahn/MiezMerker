# Simulator — Deterministic Firmware-Core Simulator and Scenario Engine

This directory contains a hardware-independent simulator for the `firmware-core` library (issue #5). It provides deterministic fakes for all hardware ports and a human-readable scenario engine for end-to-end testing.

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
capacity: 4096               # optional, observation store capacity
debounce_ms: 2000            # optional, debounce interval
seed: 0xA5A5A5A5A5A5A5A5     # optional, random seed

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
| `advance_ms` | `<ms>` | Advance monotonic clock |
| `set_ms` | `<ms>` | Set monotonic clock to value |
| `rtc` | `<STATUS> [epoch]` | Set RTC: `SYNCED epoch`, `RTC_ONLY epoch`, or `UNKNOWN` |
| `rtc_invalid` | — | Set RTC to `UNKNOWN` |
| `rtc_correct_ms` | `<epoch>` | Correct RTC to `SYNCED` with epoch |
| `read` | `<chip_id>` | Queue chip and poll reader once |
| `read_absent` | — | Poll reader with no tag |
| `expect_read` | `<chip> <RESULT>` | Read and assert result |
| `fail_load_next` | — | Next identity load fails |
| `fail_identity_store` | `<n>` | Nth identity store fails |
| `crash_identity_store` | `<n>` | Nth identity store commits, then power loss |
| `fail_append` | `<n>` | Nth observation append fails |
| `crash_append` | `<n>` | Nth observation append commits, then power loss |
| `fail_clear_next` | — | Next `clear()` fails |
| `crash_clear_next` | — | Next `clear()` commits, then power loss |
| `fail_watermark_next` | — | Next `set_ack_watermark` fails |
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
assert ack_watermark <n>
assert ready
assert not_ready
assert node_id <uuid|unchanged|changed>
assert incarnation <uuid|unchanged|changed>
assert observation <idx> <field> <value>
assert sequences_unique
assert sequences_monotonic
```

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

The simulator supports deterministic fault injection at meaningful persistence boundaries:

| Event | Injection Point |
|-------|-----------------|
| `fail_load_next` | Identity load (boot/reboot/factory_reset) |
| `fail_identity_store N` / `crash_identity_store N` | Sequence reservation (N=1), factory-reset journal/completion |
| `fail_append N` / `crash_append N` | Observation append |
| `fail_clear_next` / `crash_clear_next` | Factory-reset observation log clear |
| `fail_watermark_next` | `set_ack_watermark` |

- `fail_*` — operation returns error, nothing committed.
- `crash_*` — operation commits, then `PowerLoss` thrown (simulating power loss after durable write).
- Faults arm until the next mutating operation (`boot`, `reboot`, `factory_reset`, `read`, `expect_read`) consumes them.
- An armed crash fault that is never consumed fails the scenario at the end.

Reboot semantics: volatile Core state (debounce cache, boot_counter increment) is lost; durable media (`SimObservationStore`, `SimDeviceIdentity`, `SimRtc`, `SimStorage`) survive. Power loss throws `PowerLoss` from the store; the runner destroys the Core and continues with the next event (typically `reboot`).

## Required Scenarios (Implemented)

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

Additional scenarios for fault injection validation:
- `018-factory-reset-power-loss.scenario` — Factory reset power loss resume
- `019-rtc-rtc-only-to-synced.scenario` — RTC_ONLY to SYNCED correction
- `020-identity-load-failure.scenario` — Identity load IO_ERROR
- `021-identity-store-failure.scenario` — Identity store failure
- `022-append-failure.scenario` — Append IO_ERROR
- `023-invalid-chip-id.scenario` — Empty/overlong chip rejection
- `024-ack-watermark.scenario` — Ack watermark persistence

## Adding a New Scenario

1. Create a new `.scenario` file in `simulator/scenarios/` (e.g., `025-new-feature.scenario`).
2. Follow the format above. Use `name`, `version: 1`, and appropriate events/assertions.
3. Re-run CTest — the new scenario will be automatically discovered and run.

## Intentionally Not Implemented (Deferred to Later Tickets)

The following BLE/Security functionality is **not** implemented in this simulator. The scenario engine is structured so these can be added as new event types later:

- BLE/GATT sync protocol (#6)
- ACK/retry semantics (#6)
- Offline credentials / challenge-response (#17)
- Organization claiming / authorization (#18)
- PWA behavior (#8)

No fake versions of these protocols exist. The simulator only covers firmware-core behavior from issue #5.

## Architecture Notes

- **firmware-core** owns all firmware/domain behavior (sequence allocation, debounce, persistence contracts).
- **simulator** owns time, hardware fakes, fault injection, and scenario orchestration.
- **scenario runner** owns deterministic event execution and assertions.
- No hidden global state; all state is explicit in the runner fixture.
- Reboot semantics: volatile state resets, persistent simulated state survives per #5 contracts.
- Factory reset is clearly distinct from reboot (new node_id, new incarnation, new sequence lifetime).
- Power-loss testing: deterministic fault injection around the persistence strategy from #5.

## Requirements

- CMake ≥ 3.22
- C++20 compiler (MSVC, GCC, or Clang)
- No ESP32 hardware or toolchain required
- No network dependency
- Deterministic execution