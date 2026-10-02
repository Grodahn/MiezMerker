#pragma once

// MiezMerker firmware-core domain types (issue #5).
//
// Hardware-independent (no ESP32/ESP-IDF includes). All persisted values use
// fixed-width integers; variable-length identifiers are validated before use.

#include <array>
#include <cstddef>
#include <cstdint>
#include <optional>
#include <string>
#include <string_view>

namespace miezmerker {

/// Monotonic per-node record sequence. 0 is reserved as invalid; the first
/// allocated observation of a node identity uses 1.
using Sequence = std::uint64_t;
inline constexpr Sequence kInvalidSequence = 0;
inline constexpr Sequence kFirstSequence = 1;

/// Wall-clock trust attached to every persisted observation.
///
/// - SYNCED:   RTC was disciplined by a trusted external source (e.g. App-Sync)
///             and the stored epoch value is the trustworthy wall-clock reading.
/// - RTC_ONLY: RTC is running but has not been externally synced (or sync was
///             lost). The stored epoch value is the raw RTC reading; consumers
///             must NOT treat it as accurate wall-clock time.
/// - UNKNOWN:  RTC is invalid/unset/unreliable. No epoch value is stored
///             (observed_at_epoch_ms is nullopt); the timestamp must not be
///             interpreted as wall-clock time at all.
///
/// Sequence always provides deterministic per-node ordering, even when the
/// clock is bad, but never implies real elapsed time.
enum class ClockStatus : std::uint8_t { UNKNOWN = 0, RTC_ONLY = 1, SYNCED = 2 };

/// Returns a stable lowercase name for logs and debugging.
inline std::string_view to_string(ClockStatus status) {
    switch (status) {
        case ClockStatus::SYNCED:
            return "SYNCED";
        case ClockStatus::RTC_ONLY:
            return "RTC_ONLY";
        case ClockStatus::UNKNOWN:
            return "UNKNOWN";
    }
    return "UNKNOWN";
}

/// 128-bit persistent application identity (UUIDv4, RFC 4122).
/// Never a BLE/ESP MAC address and never a database primary key.
struct NodeId {
    std::array<std::uint8_t, 16> bytes{};

    bool operator==(const NodeId& other) const { return bytes == other.bytes; }
    bool operator!=(const NodeId& other) const { return !(*this == other); }
    bool operator<(const NodeId& other) const { return bytes < other.bytes; }

    /// Canonical lowercase UUID string "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx".
    std::string to_string() const;
    /// Parses a canonical UUID string; returns nullopt on malformed input.
    static std::optional<NodeId> parse(std::string_view text);
    /// True when at least one bit is set (i.e. not the all-zero placeholder).
    bool is_set() const;
};

/// Reset/incarnation epoch. Regenerated together with NodeId on every factory
/// reset so that (node_id, incarnation, sequence) can never be reused, even if
/// a future provisioning flow ever retained a NodeId. Opaque 128-bit value
/// formatted like NodeId for transport.
struct IncarnationId {
    std::array<std::uint8_t, 16> bytes{};

    bool operator==(const IncarnationId& other) const { return bytes == other.bytes; }
    bool operator!=(const IncarnationId& other) const { return !(*this == other); }
    bool operator<(const IncarnationId& other) const { return bytes < other.bytes; }

    std::string to_string() const;
    static std::optional<IncarnationId> parse(std::string_view text);
    bool is_set() const;
};

/// Canonical RFID chip identifier as emitted by the reader adapter.
/// The core treats it as opaque; adapters own reader-specific normalization.
/// Validation only rejects empty/overlong values so corrupt reads can never
/// create an observation.
struct ChipId {
    static constexpr std::size_t kMaxLength = 64;
    std::string value;

    bool valid() const { return !value.empty() && value.size() <= kMaxLength; }
    bool operator==(const ChipId& other) const { return value == other.value; }
    bool operator!=(const ChipId& other) const { return !(*this == other); }
    bool operator<(const ChipId& other) const { return value < other.value; }
};

/// Immutable primary data captured from a single RFID read.
/// Once persisted, timestamp and clock_status are never rewritten: later RTC
/// corrections apply to future reads only.
struct RawObservation {
    NodeId node_id{};
    IncarnationId incarnation{};
    Sequence sequence{kInvalidSequence};
    ChipId chip_id{};
    /// Wall-clock RTC value observed at read time. Present for SYNCED and
    /// RTC_ONLY; nullopt for UNKNOWN (no trustworthy time available).
    std::optional<std::uint64_t> observed_at_epoch_ms;
    ClockStatus clock_status{ClockStatus::UNKNOWN};
    /// Device monotonic clock at read time (ms). Always present; used for
    /// technical debounce and ordering, never as wall-clock time.
    std::uint64_t monotonic_ms{0};
    /// Boot epoch that recorded this observation (technical metadata).
    std::uint32_t boot_counter{0};

    /// Invariant check used before persistence and by tests.
    bool valid() const;
};

/// Minimal claim-state placeholder for #18. The core persists the state so a
/// later provisioning ticket can build on it, but performs no cryptographic
/// claiming itself.
enum class ClaimState : std::uint8_t { UNCLAIMED = 0, CLAIMED = 1 };

/// Durable identity record: node identity plus the next sequence to allocate.
struct IdentityRecord {
    NodeId node_id{};
    IncarnationId incarnation{};
    Sequence next_sequence{kFirstSequence};
    std::uint32_t boot_counter{0};
    ClaimState claim_state{ClaimState::UNCLAIMED};

    bool valid() const;
};

/// Generates UUIDv4 material. Kept in types so ports.hpp stays focused.
inline void apply_uuidv4_variant(std::array<std::uint8_t, 16>& bytes) {
    bytes[6] = static_cast<std::uint8_t>((bytes[6] & 0x0Fu) | 0x40u);
    bytes[8] = static_cast<std::uint8_t>((bytes[8] & 0x3Fu) | 0x80u);
}

}  // namespace miezmerker
