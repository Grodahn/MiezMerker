#pragma once
// Secure RTC-free time bootstrap (issue #130, ADR 0017).
//
// Portable, crypto-library-free part of the time bootstrap. This header owns:
// - NodeTimeChallengeV1 binary contract and canonical signing bytes.
// - Conservative UTC interval math with checked arithmetic.
// - Strict credential interval guard (L >= iat && U < exp).
// - Challenge lifecycle + security-clock state model.
//
// ECDSA signature verification, SHA-256 over public inputs and device
// signing live behind injected adapters (offline-auth component on ESP32,
// mbed-TLS on host). This file provides a portable SHA-256 and base64url so
// fingerprints/digests are identical across languages without extra deps.
//
// Provisional transport assumption (ADR 0017, documented not silently
// assumed): a challenge is valid for 30 s of monotonic uptime. The bound
// covers BLE/PWA forwarding delay; future transports must re-measure it.
// Challenge lifetime, server error bound, drift bound, trust age and sampling
// uncertainty are explicit NodeTimeConfig fields so tests can vary them.

#include <array>
#include <cstddef>
#include <cstdint>
#include <optional>
#include <span>
#include <string>
#include <string_view>
#include <vector>

#include "miezmerker/types.hpp"
#include "miezmerker/ports.hpp"
#include "miezmerker/node_identity.hpp"
#include "miezmerker/time_evidence.hpp"

namespace miezmerker::node_time {

// --- Fixed protocol constants (ADR 0017, #129) ---------------------------
inline constexpr std::uint16_t kVersion = 1;
inline constexpr const char* kIssuer = "miezmerker";
inline constexpr const char* kKeyId = "miezmerker-issuer-v1";
inline constexpr const char* kKind = "node-time-bootstrap";
inline constexpr const char* kChallengeDomain = "MM-NODE-TIME-CHALLENGE-v1\n";
inline constexpr const char* kAcceptDomain = "MM-NODE-TIME-ACCEPT-v1\n";

// Provisional 30 s monotonic challenge lifetime. Covers the untrusted
// PWA/BLE round trip; must be re-measured for future transports.
inline constexpr std::uint64_t kChallengeLifetimeMs = 30000ULL;
// Upper bound on proof transport: backend verifier caps at 2048 bytes.
inline constexpr std::size_t kMaxProofBytes = 2048ULL;
// Backend freshness window for claim advertisements (5 min, #18).
inline constexpr std::uint64_t kClaimFreshnessMs = 5ULL * 60ULL * 1000ULL;

struct NodeTimeConfig {
    // Monotonic challenge-to-acceptance deadline.
    std::uint64_t challenge_lifetime_ms = kChallengeLifetimeMs;
    // Configured upper bound on backend clock error (E_server). Provisional
    // installation assumption; production must calibrate monitoring.
    std::int64_t server_error_bound_ms = 1000;
    // Conservative oscillator drift bound. Provisional; no unmeasured
    // hardware accuracy is claimed. Larger values fail closed (deny more).
    std::uint32_t drift_ppm_bound = 5000;
    // Finite trust lifetime after acceptance.
    std::uint64_t max_trust_age_ms = 24ULL * 3600ULL * 1000ULL;
    // Sampling/quantization uncertainty added to both interval sides.
    std::uint64_t sampling_uncertainty_ms = 2;
};

enum class SecurityTimeState : std::uint8_t {
    UNKNOWN = 0,           // Fresh boot / reboot / power loss: no trust.
    CHALLENGE_PENDING = 1, // Fresh single-use challenge, no trust yet.
    VERIFYING = 2,         // Transient: pending consumed, anchor not active.
    BOUNDED = 3,           // Verified anchor active: bounded UTC available.
    INVALIDATED = 4        // Failure latch (timeout/replay/rollback/expiry).
};

inline std::string_view to_string(SecurityTimeState state) {
    switch (state) {
        case SecurityTimeState::UNKNOWN: return "SECURITY_TIME_UNKNOWN";
        case SecurityTimeState::CHALLENGE_PENDING: return "CHALLENGE_PENDING";
        case SecurityTimeState::VERIFYING: return "VERIFYING";
        case SecurityTimeState::BOUNDED: return "SECURITY_TIME_BOUNDED";
        case SecurityTimeState::INVALIDATED: return "INVALIDATED";
    }
    return "SECURITY_TIME_UNKNOWN";
}

// --- Binary challenge ----------------------------------------------------
struct NodeTimeChallenge {
    NodeId node_id{};
    IncarnationId incarnation{};
    std::uint32_t boot_counter{0};
    std::array<std::uint8_t, 32> nonce{};
    std::uint64_t challenge_uptime_ms{0};
    std::array<std::uint8_t, 65> device_public_key{};
    std::array<std::uint8_t, 32> fingerprint{};
    std::array<std::uint8_t, 64> signature{};
    std::uint16_t version{kVersion};

    bool valid_shape() const;
};

// Canonical domain-separated signing inputs (ADR 0017, fixed widths,
// little-endian integers, raw RFC 4122 UUID bytes).
std::vector<std::uint8_t> challenge_signing_bytes(const NodeTimeChallenge& challenge);
std::vector<std::uint8_t> acceptance_signing_bytes(
    const NodeId& node_id, const IncarnationId& incarnation, std::uint32_t boot_counter,
    std::span<const std::uint8_t, 32> nonce_digest,
    std::span<const std::uint8_t, 32> proof_digest,
    std::uint64_t accepted_uptime_ms, const NodeId& anchor_id);

// --- Portable SHA-256 / base64url (identical across languages) -----------
std::array<std::uint8_t, 32> sha256(std::span<const std::uint8_t> input);
std::string base64url_encode(std::span<const std::uint8_t> input);
// Strict unpadded base64url: rejects padding, aliases, wrong alphabet and
// nonzero trailing bits. Returns false on any violation.
bool base64url_decode(std::string_view text, std::vector<std::uint8_t>& out);
bool base64url_decode_fixed(std::string_view text, std::span<std::uint8_t> out);
// True when text is the canonical encoding of exactly expected_bytes.
bool is_canonical_base64url(std::string_view text, std::size_t expected_bytes);

// --- Conservative interval math (checked, overflow-safe) -----------------
struct AcceptanceInterval {
    std::int64_t lower_ms{0};
    std::int64_t upper_ms{0};
    std::int64_t server_utc_ms{0};
    std::uint64_t challenge_uptime_ms{0};
    std::uint64_t accepted_uptime_ms{0};
};

// acceptance interval [S-E-sampling, S+E+D+sampling], D = U_accept-U_challenge.
// Returns false (fail closed) on invalid inputs or any overflow.
bool compute_acceptance_interval(std::int64_t server_utc_ms, std::int64_t server_error_ms,
                                 std::uint64_t challenge_uptime_ms,
                                 std::uint64_t accepted_uptime_ms,
                                 std::uint64_t sampling_ms,
                                 AcceptanceInterval& out);

// Current [L,U] at now_uptime: anchor bounds advanced by elapsed monotonic
// time plus growing drift. Returns false when expired, rolled back,
// discontinuous or overflowed (caller must invalidate).
bool advance_interval(const AcceptanceInterval& anchor, std::uint64_t now_uptime_ms,
                      std::uint32_t drift_ppm, std::uint64_t max_age_ms,
                      std::int64_t& lower_ms, std::int64_t& upper_ms);

// Strict credential guard: security decision valid only for the entire
// possible UTC interval. No grace period, no midpoint fallback.
bool credential_covers(std::int64_t iat_seconds, std::int64_t exp_seconds,
                       std::int64_t lower_ms, std::int64_t upper_ms);

// Nominal display point (midpoint) for non-security uses only. Never feed
// this into credential/claim authorization; use credential_covers instead.
std::optional<std::int64_t> nominal_midpoint(std::int64_t lower_ms, std::int64_t upper_ms);

// --- Security clock (portable state + interval, no crypto, no store) -----
class SecurityClock {
public:
    explicit SecurityClock(NodeTimeConfig config = NodeTimeConfig{});

    void on_boot(const NodeId& node_id, const IncarnationId& incarnation,
                 std::uint32_t boot_counter);
    void invalidate();
    SecurityTimeState state() const { return state_; }
    const NodeTimeConfig& config() const { return config_; }

    // Boot binding for verification.
    const NodeId& node_id() const { return node_id_; }
    const IncarnationId& incarnation() const { return incarnation_; }
    std::uint32_t boot_counter() const { return boot_counter_; }

    // Single-use pending challenge. A new challenge supersedes the previous
    // one; take_pending consumes it even on failure.
    bool store_pending(const NodeTimeChallenge& challenge);
    bool has_pending() const { return has_pending_; }
    const NodeTimeChallenge& pending() const { return pending_; }
    // Consumes pending, checks boot binding + monotonic deadline.
    // On success leaves state VERIFYING and returns the challenge.
    // On failure clears pending, latches INVALIDATED and returns false.
    bool take_pending_for_verify(std::uint64_t now_uptime_ms,
                                 NodeTimeChallenge& out);
    // Timeout check without consuming (for diagnostics/tests).
    bool pending_expired(std::uint64_t now_uptime_ms) const;

    // Activates trust after the caller durably committed the verified anchor.
    // Fails closed on invalid interval or boot mismatch.
    bool activate(const AcceptanceInterval& interval, std::uint32_t drift_ppm);

    // Current bounded UTC at now_uptime_ms. Advances the interval, accumulates
    // drift, enforces max age and detects rollback/discontinuity. On any
    // failure latches INVALIDATED and returns false (no trust).
    // monotonic_valid=false (timer discontinuity) invalidates immediately.
    bool current_interval(std::uint64_t now_uptime_ms, bool monotonic_valid,
                          std::int64_t& lower_ms, std::int64_t& upper_ms);

    // Strict credential check at now_uptime_ms (revalidated per operation).
    bool credential_valid(std::int64_t iat_seconds, std::int64_t exp_seconds,
                          std::uint64_t now_uptime_ms, bool monotonic_valid);

    bool has_trust() const { return has_anchor_; }

private:
    NodeTimeConfig config_{};
    SecurityTimeState state_{SecurityTimeState::UNKNOWN};
    NodeId node_id_{};
    IncarnationId incarnation_{};
    std::uint32_t boot_counter_{0};
    bool boot_set_{false};
    NodeTimeChallenge pending_{};
    bool has_pending_{false};
    AcceptanceInterval anchor_{};
    std::uint32_t anchor_drift_ppm_{0};
    bool has_anchor_{false};
    std::uint64_t last_seen_uptime_{0};
    bool last_seen_valid_{false};
};

// --- Verified backend claims (filled by the JWS verifier) ----------------
// Portable subset of BackendTimeProofV1 needed for binding. The cryptographic
// JWS verification itself lives in the offline-auth component; this struct
// carries the verified output for the bootstrap manager.
struct TimeProofClaims {
    std::string node_id;
    std::string incarnation;
    std::string nonce_b64u;
    std::string dpf_b64u;
    std::string jti;
    std::uint32_t boot{0};
    std::int64_t utc_ms{0};
    int version{0};
    std::vector<std::uint8_t> signed_input;
    std::array<std::uint8_t, 64> signature{};
};

// Pre-auth bootstrap manager: minimal secure operation for #131 transport.
// Generates single-use challenges, verifies backend proofs via an injected
// JWS verifier, persists verifier-owned anchors and activates the security
// clock. Never trusts PWA time and never grants observation/org access.
class NodeTimeBootstrapManager {
public:
    using VerifierFn = bool (*)(std::string_view proof,
                                std::span<const unsigned char, 65> issuer_key,
                                TimeProofClaims& out);

    NodeTimeBootstrapManager(Clock& clock, RandomSource& random, NodeCrypto& crypto,
                             TimeEvidenceStore& evidence, VerifierFn verifier,
                             std::span<const unsigned char, 65> issuer_key,
                             NodeTimeConfig config = NodeTimeConfig{});

    // Binds to the current boot after Core::initialize. Reboot/power loss
    // clears pending challenges and trust (fail closed).
    bool on_boot(const NodeId& node_id, const IncarnationId& incarnation,
                 std::uint32_t boot_counter);

    // Generates a fresh single-use challenge signed with the existing device
    // key. A new challenge supersedes the previous one. Pre-auth: discloses
    // only node/incarnation/boot/nonce/pubkey/fingerprint/signature/version.
    bool create_challenge(const std::array<std::byte, 65>& device_public_key,
                          const std::array<std::byte, 32>& device_private_key,
                          NodeTimeChallenge& out);

    // Backend JSON for PWA transport (canonical UUID strings + canonical
    // base64url). No observation/chip/pending/org data.
    static std::string challenge_to_json(const NodeTimeChallenge& challenge);

    // Submits the exact backend compact JWS received via untrusted transport.
    // Consumes the pending challenge even on failure. On success durably
    // commits the verified anchor before activating the clock, then returns
    // the device-signed acceptance JSON for #131 transport.
    bool submit_proof(std::string_view jws,
                      const std::array<std::byte, 65>& device_public_key,
                      const std::array<std::byte, 32>& device_private_key,
                      std::string& acceptance_json_out);

    SecurityTimeState state() const;
    bool current_interval(std::int64_t& lower_ms, std::int64_t& upper_ms);
    bool credential_valid(std::int64_t iat_seconds, std::int64_t exp_seconds);
    const TimeAnchorV1* last_anchor() const;
    const std::string& last_acceptance_json() const { return last_acceptance_json_; }
    const std::string& last_proof() const { return last_proof_; }
    const NodeTimeChallenge* pending_challenge() const;

private:
    Clock* clock_{nullptr};
    RandomSource* random_{nullptr};
    NodeCrypto* crypto_{nullptr};
    TimeEvidenceStore* evidence_{nullptr};
    VerifierFn verifier_{nullptr};
    std::array<unsigned char, 65> issuer_key_{};
    bool issuer_pinned_{false};
    NodeTimeConfig config_{};
    SecurityClock security_{};
    std::string last_acceptance_json_;
    std::string last_proof_;
    TimeAnchorV1* last_anchor_{nullptr};
    // Owned anchor storage (avoids exposing internal SecurityClock anchor).
    // Persisted copy retained for #131 transport; never mutated after commit.
    std::optional<TimeAnchorV1> stored_anchor_;
};

}  // namespace miezmerker::node_time
