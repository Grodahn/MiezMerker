#pragma once

// MiezMerker BLE sync domain state machine (issue #6).
//
// Portable per-connection sync server. No BLE/NimBLE/ESP-IDF/mbed-TLS includes:
// authorization crypto stays behind the injected Authorizer (the ESP32 build
// delegates to OfflineAuthSession with identical semantics), time correction
// behind RtcControl, node session proof behind NodeSigner. The core owns the
// log, watermark, claim/owner view and clock status; this class owns gating,
// cursor math, monotonic ACK enforcement and explicit compaction.
//
// Foreign organizations receive only the public owner hint; pending counts,
// chip IDs and observations require an authorized same-org session.

#include <array>
#include <cstdint>
#include <string>
#include <vector>

#include "miezmerker/ble_codec.hpp"
#include "miezmerker/core.hpp"
#include "miezmerker/ports.hpp"
#include "miezmerker/types.hpp"

namespace miezmerker::ble {

// Trusted UTC seconds for expiry checks. 0 means unknown and fails closed.
// Never supply a PWA-provided clock here.
using TrustedEpochSeconds = std::int64_t;

// Authorizer abstracts OfflineAuthSession (firmware-esp32/components/offline-auth)
// so firmware-core stays crypto-free. Semantics must match exactly:
// - begin() generates a fresh 32 B challenge, clears authorization.
// - authorize() consumes the challenge (any outcome) and validates the
//   backend-signed credential + proof + org binding + scope + expiry.
// - can_sync() re-checks expiry on every protected op.
class SyncAuthorizer {
public:
    virtual ~SyncAuthorizer() = default;
    virtual bool begin(std::array<std::uint8_t, 32>& challenge) = 0;
    virtual bool authorize(const std::string& credential,
                           const std::array<std::uint8_t, 64>& proof,
                           TrustedEpochSeconds now_s) = 0;
    virtual bool can_sync(TrustedEpochSeconds now_s) const = 0;
    virtual void disconnect() = 0;
};

// RTC discipline for TimeCorrect. Implementations set the board RTC so future
// Core::record_chip_read calls observe the corrected wall clock. Past
// RawObservations are never rewritten (core invariant).
class RtcControl {
public:
    virtual ~RtcControl() = default;
    virtual bool set_corrected_epoch_ms(std::uint64_t epoch_ms) = 0;
};

// Node session proof (#18): signs
// "MM-NODE-SESSION-v1\n{node_id}\n{base64url_32B_nonce}" with the device key.
// Only available when claimed.
class NodeSigner {
public:
    virtual ~NodeSigner() = default;
    virtual bool sign_session(const std::array<std::uint8_t, 32>& challenge,
                              std::array<std::uint8_t, 64>& signature) = 0;
};

struct SyncConfig {
    std::string firmware_version{"miezmerker-esp32c3-1.0.0"};
    std::uint16_t max_batch_records{16};
    std::uint32_t max_credential_bytes{2048};
};

/// Per-connection BLE sync server over a Core + durable stores.
///
/// Lifecycle: construction binds stores/identity; each GATT connection calls
/// disconnect() first (fresh session), serves public hello/owner + challenge,
/// then authorize() once, then protected status/batch/ack/time/compact/proof.
/// Retry after disconnect starts over with a new challenge; old proofs replay
/// fail because the authorizer consumes nonces.
class SyncServer {
public:
    SyncServer(Core& core, SyncAuthorizer& authorizer, RtcControl& rtc, NodeSigner& signer,
               const SyncConfig& config = SyncConfig{});

    void disconnect();

    // --- Public (no authorization) ---
    HelloPublic public_hello() const;
    OwnerInfo public_owner() const;
    Advertisement advertisement(bool claim_mode_active) const;
    bool begin_challenge(std::array<std::uint8_t, 32>& challenge);
    bool authorize(const std::string& credential, const std::array<std::uint8_t, 64>& proof,
                   TrustedEpochSeconds now_s);

    // --- Protected (require can_sync) ---
    bool authorized(TrustedEpochSeconds now_s) const;
    std::optional<StatusResponse> status(TrustedEpochSeconds now_s) const;
    struct BatchResult {
        BatchResponse batch;
        SyncError error{SyncError::Ok};
    };
    BatchResult batch(std::uint64_t from_sequence, std::uint16_t max_records,
                      TrustedEpochSeconds now_s) const;
    struct AckResult {
        std::uint64_t new_watermark{0};
        SyncError error{SyncError::Ok};
    };
    AckResult ack(std::uint64_t watermark, TrustedEpochSeconds now_s);
    struct TimeResult {
        bool ok{false};
        SyncError error{SyncError::Ok};
        std::uint64_t applied_epoch_ms{0};
    };
    TimeResult correct_time(std::uint64_t epoch_ms, TrustedEpochSeconds now_s);
    struct CompactResult {
        std::uint32_t freed{0};
        std::uint32_t remaining{0};
        std::uint64_t ack_watermark{0};
        SyncError error{SyncError::Ok};
    };
    CompactResult compact(TrustedEpochSeconds now_s);
    struct ProofResult {
        std::array<std::uint8_t, 64> signature{};
        SyncError error{SyncError::Ok};
    };
    ProofResult node_proof(const std::array<std::uint8_t, 32>& pwa_nonce,
                           TrustedEpochSeconds now_s);

    // Introspection for tests/simulator.
    std::uint32_t pending_count() const;
    std::uint64_t ack_watermark() const;

private:
    bool claimed() const;
    Core* core_;
    SyncAuthorizer* authorizer_;
    RtcControl* rtc_;
    NodeSigner* signer_;
    SyncConfig config_;
};

/// PWA-side helper (mirrored in TypeScript): contiguous high-watermark over a
/// durable set. base = last acked watermark; stored = durably persisted
/// sequences > base for one (node_id, incarnation) lifetime.
/// Returns the highest contiguous persisted sequence (>= base).
std::uint64_t contiguous_watermark(std::uint64_t base, const std::vector<std::uint64_t>& stored);

}  // namespace miezmerker::ble
