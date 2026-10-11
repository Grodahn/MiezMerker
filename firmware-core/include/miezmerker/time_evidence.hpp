#pragma once
#include "miezmerker/ports.hpp"

namespace miezmerker {
// Separate, versioned evidence. RawObservation and BLE v1 remain unchanged.
struct BootEpochV1 {
    std::uint16_t format_version{1};
    NodeId node_id;
    IncarnationId incarnation;
    std::uint32_t boot_counter{0}, reset_reason{0};
    std::uint64_t started_uptime_ms{0};
    Sequence first_possible_sequence{kFirstSequence}; // reservation boundary, not an observed read
    bool valid() const {
        return format_version == 1 && node_id.is_set() && incarnation.is_set()
            && boot_counter != 0 && first_possible_sequence != 0;
    }
    bool operator==(const BootEpochV1&) const = default;
};
enum class AnchorSource : std::uint8_t { BACKEND_SIGNED = 0, PHONE_ESTIMATE = 1, RTC_HOLDOVER = 2 };
struct TimeAnchorV1 {
    std::uint16_t format_version{1};
    NodeId anchor_id, node_id;
    IncarnationId incarnation;
    std::uint32_t boot_counter{0};
    std::uint64_t uptime_ms{0};
    std::int64_t utc_ms{0};
    AnchorSource source{AnchorSource::BACKEND_SIGNED};
    bool verified_security_time{false};
    std::uint64_t uncertainty_minus_ms{0}, uncertainty_plus_ms{0};
    std::uint32_t drift_ppm_bound{0};
    std::string proof_id_or_digest;
    // Opaque exact proof/acceptance bytes for later #130/#131; never an auth input here.
    std::string proof, acceptance;
    bool valid() const {
        return format_version == 1 && anchor_id.is_set() && node_id.is_set()
            && incarnation.is_set() && boot_counter != 0 && utc_ms > 0
            && static_cast<unsigned>(source) <= 2 && !verified_security_time
            && proof_id_or_digest.size() <= 128 && proof.size() <= 4096 && acceptance.size() <= 512;
    }
    bool operator==(const TimeAnchorV1&) const = default;
};
// This is persistence only, with no path into Clock/ClaimClock or authorization.
// #130 must add a verifier-owned acceptance boundary before verified anchors
// can be stored. Until then verified_security_time=true is explicitly rejected.
// Atomic append: exact retries OK without writes; conflicting identities fail.
// Failure may have committed: adapter latches failure until open/reboot.
// ACK/compaction of raw observations NEVER deletes this evidence. #131 owns
// evidence export/receipt; no evidence pruning before durable downstream ACK.
struct TimeEvidenceStore {
    virtual ~TimeEvidenceStore() = default;
    virtual bool open() = 0;
    virtual PutResult append_boot(const BootEpochV1&) = 0;
    virtual PutResult append_anchor(const TimeAnchorV1&) = 0;
    virtual std::vector<BootEpochV1> boots() const = 0;
    virtual std::vector<TimeAnchorV1> anchors() const = 0;
    virtual bool clear_for_factory_reset() = 0;
};
} // namespace miezmerker
