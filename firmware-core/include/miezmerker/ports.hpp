#pragma once

// MiezMerker firmware-core ports (issue #5).
//
// Dependency-injection boundaries. The core owns orchestration and invariants;
// adapters (ESP32-C3 flash/NVS/RTC/RFID in firmware-esp32, simulated fakes in
// tests/simulator) implement these interfaces. No ESP32, OS or browser API may
// leak into firmware-core: only standard C++ headers are included here.

#include <cstddef>
#include <cstdint>
#include <optional>
#include <span>
#include <string>
#include <vector>

#include "miezmerker/types.hpp"

namespace miezmerker {

/// RTC reading observed at a single instant.
struct WallClockReading {
    ClockStatus status{ClockStatus::UNKNOWN};
    /// Epoch milliseconds when status is SYNCED or RTC_ONLY; nullopt when
    /// UNKNOWN. The raw RTC value is never synthesized when unreliable.
    std::optional<std::uint64_t> epoch_ms;
};

/// Time source. monotonic_ms is always available; wall_clock reports the RTC
/// value plus the trust status that applied at that instant.
struct Clock {
    virtual ~Clock() = default;
    virtual std::uint64_t monotonic_ms() const = 0;
    virtual WallClockReading wall_clock() const = 0;
};

/// Entropy for UUIDv4 node/incarnation generation. Injected so tests stay
/// deterministic and ESP32-C3 can later use its hardware RNG.
/// Production adapters must provide fresh random bytes across reboots/resets;
/// a deterministic host source is suitable only for tests and the simulator.
struct RandomSource {
    virtual ~RandomSource() = default;
    virtual void fill_random(std::span<std::uint8_t> out) = 0;
};

/// Durable node identity + sequence-counter persistence.
///
/// Implementations must provide atomic record updates: a power loss during
/// store() leaves either the previous record or the new record readable, never
/// a torn half-record. load() distinguishes first boot from an unreadable
/// record: an IO_ERROR must never cause automatic reprovisioning.
enum class IdentityLoadResult : std::uint8_t { OK = 0, NOT_FOUND = 1, IO_ERROR = 2 };

struct DeviceIdentityStore {
    virtual ~DeviceIdentityStore() = default;
    virtual IdentityLoadResult load(IdentityRecord& out) = 0;
    virtual bool store(const IdentityRecord& record) = 0;
    virtual bool erase() = 0;
};

/// Outcome of a single append. FULL must never delete or overwrite existing
/// records to make room; IO_ERROR must leave prior records intact.
enum class PutResult : std::uint8_t { OK = 0, FULL = 1, IO_ERROR = 2 };

/// Capacity signal for UI/LED and tests. Thresholds are defined by the store
/// implementation; the core treats NEARLY_FULL as a warning and FULL as a
/// hard rejection of new observations.
enum class StoreStatus : std::uint8_t { OK = 0, NEARLY_FULL = 1, FULL = 2 };

/// Append-only durable observation log with a separate acknowledgement cursor.
///
/// Contract for every implementation (flash, NVS, file or memory):
/// - append() is atomic: power loss yields either the fully persisted record
///   or nothing, never a torn record and never corruption of earlier records.
/// - load_all() after a reboot returns every successfully appended record in
///   sequence order, including unacknowledged ones.
/// - capacity() is fixed; append() returns FULL instead of overwriting.
/// - The ack watermark (for BLE sync in #6) is persisted separately from raw
///   records and survives reboot. The core never deletes records in #5;
///   compaction of acknowledged records belongs to #6.
/// - clear() atomically erases records and the ack cursor, or preserves both.
///   It is idempotent so an interrupted factory reset can resume on boot.
struct ObservationStore {
    virtual ~ObservationStore() = default;
    virtual PutResult append(const RawObservation& observation) = 0;
    virtual std::vector<RawObservation> load_all() = 0;
    virtual std::size_t size() const = 0;
    virtual std::size_t capacity() const = 0;
    virtual StoreStatus status() const = 0;
    virtual std::uint64_t ack_watermark() const = 0;
    virtual bool set_ack_watermark(std::uint64_t sequence) = 0;
    virtual bool clear() = 0;
};

/// RFID reader input. Returns the canonical chip identifier of one discrete
/// read, or nullopt when no tag is present. Reader-specific normalization
/// (case, framing, parity) belongs to the adapter; the core validates.
struct RfidReader {
    virtual ~RfidReader() = default;
    virtual std::optional<std::string> poll() = 0;
};

// ---------------------------------------------------------------------------
// Legacy / future-transport placeholders
//
// Storage was the #2 boot gate. It is retained so the #2 composition boundary
// keeps compiling, but new code must use DeviceIdentityStore/ObservationStore.
// BleTransport is the #6 transfer boundary and intentionally unused by #5.
// ---------------------------------------------------------------------------
struct Storage {
    virtual ~Storage() = default;
    virtual bool open() = 0;
};

struct BleTransport {
    virtual ~BleTransport() = default;
    virtual bool send(std::span<const std::byte> message) = 0;
};

}  // namespace miezmerker
