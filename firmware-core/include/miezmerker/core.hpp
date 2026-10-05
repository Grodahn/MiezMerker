#pragma once

// MiezMerker firmware-core orchestration (issue #5).
//
// NodeCore accepts RFID reads, applies short technical debounce, allocates a
// strictly monotonic per-node sequence and persists immutable RawObservations.
// It performs NO visit aggregation and NO business interpretation.
//
// Persistence/sequence strategy (correctness-critical):
//   reserve-then-append with reboot reconciliation.
//   1. Read clocks (monotonic + RTC) at read time.
//   2. Check the observation store has room (avoids needless gaps).
//   3. Reserve the sequence: persist identity.next_sequence+1 BEFORE appending.
//      A crash here produces at most a skipped sequence (gap), never a reuse.
//   4. Append the observation built with the reserved sequence.
//   5. On every boot, reconcile: next_sequence = max(persisted next_sequence,
//      max persisted observation sequence + 1) and persist when advanced.
// Uniqueness of (node_id, sequence) is preferred over gaplessness in every
// crash path.

#include <cstddef>
#include <cstdint>
#include <map>
#include <string>
#include <string_view>
#include <vector>

#include "miezmerker/ports.hpp"
#include "miezmerker/types.hpp"

namespace miezmerker {

/// Tunables for the capture pipeline. All hardware-independent.
struct CoreConfig {
    /// Suppress repeats of the identical chip_id within this monotonic window
    /// after a recorded observation. 0 disables deduplication.
    /// Default 2000 ms: short enough to keep distinct passes, long enough to
    /// collapse radio repeats of one presentation.
    std::uint64_t debounce_interval_ms{2000};
};

/// Outcome of a single RFID read offered to the core.
enum class RecordResult : std::uint8_t {
    /// A new RawObservation was durably persisted.
    RECORDED = 0,
    /// Suppressed as a technical repeat of the same chip within the debounce
    /// window. No observation was created and nothing was deleted.
    DEBOUNCED = 1,
    /// Rejected: store has no room. Nothing was overwritten or deleted.
    REJECTED_FULL = 2,
    /// Rejected: empty/overlong chip identifier. Nothing was persisted.
    REJECTED_INVALID = 3,
    /// Core not initialized (storage/identity unavailable).
    NOT_READY = 4,
    /// Durable write failed. Prior records are intact; the read was not
    /// counted as captured.
    IO_ERROR = 5,
};

inline std::string_view to_string(RecordResult result) {
    switch (result) {
        case RecordResult::RECORDED:
            return "RECORDED";
        case RecordResult::DEBOUNCED:
            return "DEBOUNCED";
        case RecordResult::REJECTED_FULL:
            return "REJECTED_FULL";
        case RecordResult::REJECTED_INVALID:
            return "REJECTED_INVALID";
        case RecordResult::NOT_READY:
            return "NOT_READY";
        case RecordResult::IO_ERROR:
            return "IO_ERROR";
    }
    return "IO_ERROR";
}

/// Hardware-independent capture core. Single-writer by construction: all
/// mutating calls must come from one firmware loop (no internal locking).
class Core {
public:
    Core(Clock& clock, Storage& storage, ObservationStore& observations,
         DeviceIdentityStore& identity, RandomSource& random,
         const CoreConfig& config = CoreConfig{});

    // Legacy #2 constructor: boot gate only. Identity/observation stores are
    // absent, so the core reports ready_ from Storage::open() but every read
    // returns NOT_READY. Kept so the #2 composition boundary keeps building;
    // new firmware must use the full constructor above.
    Core(Clock& clock, Storage& storage);

    /// Loads or provisions identity, reconciles the sequence counter and marks
    /// the core ready. Increments and persists the boot counter on normal
    /// reboots. Returns false when durable state is unavailable.
    bool initialize();
    // Reload a claim committed through the shared NodeIdentityManager without
    // simulating a reboot or changing capture counters.
    bool refresh_identity();

    bool ready() const { return ready_; }
    std::uint64_t started_at_ms() const { return started_at_ms_; }

    /// Offers one RFID read. Captured only after durable persistence succeeds.
    RecordResult record_chip_read(const std::string& chip_id);

    /// Convenience for the firmware loop: polls the reader once and records
    /// when a tag is present. Returns the record outcome, or nullopt when the
    /// reader reports no tag.
    std::optional<RecordResult> poll_reader(RfidReader& reader);

    /// Distinct lifecycle operation: journals a fresh identity before erasing
    /// observations and ack state. Boot resumes an interrupted reset before
    /// accepting reads. Never reuses the old identity with a reset counter.
    bool factory_reset();

    // --- Introspection for #6/#7, simulator and tests ---
    const NodeId& node_id() const { return identity_.node_id; }
    const IncarnationId& incarnation() const { return identity_.incarnation; }
    Sequence next_sequence() const { return identity_.next_sequence; }
    std::uint32_t boot_counter() const { return identity_.boot_counter; }
    ClaimState claim_state() const { return identity_.claim_state; }
    std::size_t observation_count() const;
    StoreStatus store_status() const;
    WallClockReading wall_clock() const;
    std::uint64_t ack_watermark() const;
    bool set_ack_watermark(std::uint64_t sequence);
    /// Frees the acked prefix (sequence <= watermark) after a valid ACK (#6).
    /// Never touches unacked records, never moves the watermark, idempotent.
    /// Returns false when not ready or the store fails.
    bool compact_acked();
    std::vector<RawObservation> load_observations();
    std::uint64_t debounce_interval_ms() const { return config_.debounce_interval_ms; }

private:
    bool reconcile_after_load();
    bool provision_fresh_identity(bool is_factory_reset);
    bool complete_pending_reset();

    Clock* clock_{nullptr};
    Storage* storage_{nullptr};
    ObservationStore* observations_{nullptr};
    DeviceIdentityStore* identity_store_{nullptr};
    RandomSource* random_{nullptr};
    CoreConfig config_{};

    bool legacy_mode_{false};
    bool ready_{false};
    std::uint64_t started_at_ms_{0};
    IdentityRecord identity_{};
    /// Last RECORDED monotonic time per chip (RAM only, lost on reboot).
    std::map<std::string, std::uint64_t> last_recorded_ms_{};
};

}  // namespace miezmerker
