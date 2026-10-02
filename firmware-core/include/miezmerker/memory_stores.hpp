#pragma once

// In-memory firmware-core stores (issue #5).
//
// Hardware-independent reference implementations of the persistence ports for
// host tests and the simulator. The ESP32-C3 flash/NVS/filesystem adapter in a
// later ticket implements the same interfaces; the core never depends on which
// backing store is used.
//
// Crash model: every mutating operation is atomic from the core's perspective.
// Power loss is modelled by destroying the Core while keeping these store
// objects alive (they represent flash, not RAM). Fault-injection flags let
// tests simulate write failures without corrupting prior records.

#include <cstddef>
#include <cstdint>
#include <span>
#include <vector>

#include "miezmerker/ports.hpp"
#include "miezmerker/types.hpp"

namespace miezmerker {

/// Fixed-capacity append-only log. Never overwrites: append() returns FULL
/// instead. NEARLY_FULL threshold is 90% of capacity.
class InMemoryObservationStore final : public ObservationStore {
public:
    explicit InMemoryObservationStore(std::size_t capacity = 4096);

    PutResult append(const RawObservation& observation) override;
    std::vector<RawObservation> load_all() override;
    std::size_t size() const override;
    std::size_t capacity() const override;
    StoreStatus status() const override;
    std::uint64_t ack_watermark() const override;
    bool set_ack_watermark(std::uint64_t sequence) override;
    bool clear() override;

    // --- Fault injection for deterministic power-loss tests ---
    void set_fail_next_append(bool fail) { fail_next_append_ = fail; }
    void set_fail_next_watermark(bool fail) { fail_next_watermark_ = fail; }
    void set_fail_next_clear(bool fail) { fail_next_clear_ = fail; }
    std::size_t append_attempts() const { return append_attempts_; }

private:
    std::vector<RawObservation> records_;
    std::size_t capacity_;
    std::uint64_t ack_watermark_{0};
    bool fail_next_append_{false};
    bool fail_next_watermark_{false};
    bool fail_next_clear_{false};
    std::size_t append_attempts_{0};
};

/// Atomic identity record cell. store() validates before committing so a torn
/// write can never leave a half-record behind.
class InMemoryIdentityStore final : public DeviceIdentityStore {
public:
    InMemoryIdentityStore() = default;

    IdentityLoadResult load(IdentityRecord& out) override;
    bool store(const IdentityRecord& record) override;
    bool erase() override;

    // --- Fault injection ---
    void set_fail_next_store(bool fail) { fail_next_store_ = fail; }
    void set_fail_next_load(bool fail) { fail_next_load_ = fail; }
    void set_has_record(bool has) { has_record_ = has; }
    bool has_record() const { return has_record_; }
    std::size_t store_attempts() const { return store_attempts_; }

private:
    IdentityRecord record_{};
    bool has_record_{false};
    bool fail_next_store_{false};
    bool fail_next_load_{false};
    std::size_t store_attempts_{0};
};

/// Deterministic entropy for tests: cycles a fixed byte pattern.
class FixedRandomSource final : public RandomSource {
public:
    explicit FixedRandomSource(std::uint8_t seed = 0x5A);
    void fill_random(std::span<std::uint8_t> out) override;

private:
    std::uint64_t counter_{0};
    std::uint8_t seed_{0};
};

/// Controllable clock for tests: monotonic time advances manually, wall-clock
/// value and trust status are set explicitly (including UNKNOWN).
class ManualClock final : public Clock {
public:
    ManualClock();
    std::uint64_t monotonic_ms() const override;
    WallClockReading wall_clock() const override;

    void set_monotonic_ms(std::uint64_t value) { monotonic_ms_ = value; }
    void advance_monotonic_ms(std::uint64_t delta) { monotonic_ms_ += delta; }
    void set_wall_clock(ClockStatus status, std::optional<std::uint64_t> epoch_ms);

private:
    std::uint64_t monotonic_ms_{0};
    WallClockReading wall_{};
};

}  // namespace miezmerker
