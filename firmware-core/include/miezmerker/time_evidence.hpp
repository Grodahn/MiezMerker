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
    // Added by #131; empty on legacy evidence, never reconstructed from phone data.
    std::string challenge, challenge_bytes, acceptance_bytes;
    bool valid() const {
        return format_version == 1 && anchor_id.is_set() && node_id.is_set()
            && incarnation.is_set() && boot_counter != 0 && utc_ms > 0
            && static_cast<unsigned>(source) <= 2 && !verified_security_time
            && challenge.size() <= 1024 && challenge_bytes.size() <= 512 && acceptance_bytes.size() <= 512
            && proof_id_or_digest.size() <= 128 && proof.size() <= 4096 && acceptance.size() <= 512;
    }
    // Verified anchors additionally require BACKEND_SIGNED provenance plus the
    // exact persisted proof/acceptance bytes. Only the firmware verifier path
    // (#130) may construct one via VerifiedAnchor; direct append_anchor calls
    // still reject verified_security_time=true.
    bool valid_verified() const {
        return format_version == 1 && anchor_id.is_set() && node_id.is_set()
            && incarnation.is_set() && boot_counter != 0 && utc_ms > 0
            && source == AnchorSource::BACKEND_SIGNED && verified_security_time
            && challenge.size() <= 1024 && challenge_bytes.size() <= 512 && acceptance_bytes.size() <= 512
            && proof_id_or_digest.size() <= 128 && !proof_id_or_digest.empty()
            && !proof.empty() && proof.size() <= 4096
            && !acceptance.empty() && acceptance.size() <= 512;
    }
    bool operator==(const TimeAnchorV1&) const = default;
};
// Verifier-owned wrapper: only the successful firmware verifier path (#130)
// can create verified time-anchor evidence. There is no public setter that
// marks arbitrary timestamps as trusted; arbitrary callers keep using
// append_anchor (which rejects verified_security_time=true).
// Declared friend of the portable bootstrap manager (see node_time.hpp and
// firmware-esp32 time_bootstrap.hpp); store implementations accept it via
// append_verified without re-exposing a generic verified setter.
namespace node_time {
class NodeTimeBootstrapManager;
}
struct VerifiedAnchorTestAccess;
struct VerifiedAnchor {
    friend class node_time::NodeTimeBootstrapManager;
#ifdef MIEZMERKER_TEST_ANCHOR_ACCESS
    friend struct VerifiedAnchorTestAccess;
#endif
    const TimeAnchorV1& anchor() const { return anchor_; }
   private:
    explicit VerifiedAnchor(TimeAnchorV1 anchor) : anchor_(std::move(anchor)) {}
    TimeAnchorV1 anchor_;
};
// Test-only factory for store-level idempotency/conflict tests. Only visible
// when MIEZMERKER_TEST_ANCHOR_ACCESS is defined (set solely by the
// node-time persistence test target); production translation units cannot
// mint verified anchors without the verifier path. Wrapping alone grants no
// trust: trust requires SecurityClock activation, not just storage.
#ifdef MIEZMERKER_TEST_ANCHOR_ACCESS
struct VerifiedAnchorTestAccess {
    static VerifiedAnchor wrap(TimeAnchorV1 anchor) { return VerifiedAnchor(std::move(anchor)); }
};
#endif
// This is persistence only, with no path into Clock/ClaimClock or authorization.
// #130 verifier-owned acceptance uses append_verified; append_anchor still
// rejects verified_security_time=true so arbitrary callers cannot declare
// unverifiable time trusted.
// Atomic append: exact retries OK without writes; conflicting identities fail.
// Failure may have committed: adapter latches failure until open/reboot.
// ACK/compaction of raw observations NEVER deletes this evidence. #131 owns
// evidence export/receipt; no evidence pruning before durable downstream ACK.
enum class EvidenceReceiptResult { Rejected, Retained, Removed };
struct TimeEvidenceStore {
    virtual ~TimeEvidenceStore() = default;
    virtual bool open() = 0;
    virtual PutResult append_boot(const BootEpochV1&) = 0;
    virtual PutResult append_anchor(const TimeAnchorV1&) = 0;
    virtual PutResult append_verified(const VerifiedAnchor& verified) = 0;
    virtual std::vector<BootEpochV1> boots() const = 0;
    virtual std::vector<TimeAnchorV1> anchors() const = 0;
    // Receipt is separate from raw ACK. Caller enforces same-org auth; the
    // collector sends it only for independently backend-confirmed evidence.
    virtual EvidenceReceiptResult acknowledge(bool, const NodeId&, const IncarnationId&,
            std::uint32_t, const NodeId&) { return EvidenceReceiptResult::Rejected; }
    virtual bool clear_for_factory_reset() = 0;
};
} // namespace miezmerker
