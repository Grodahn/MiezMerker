#pragma once
#include "miezmerker/persistent_state.hpp"
#include "miezmerker/ble_sync.hpp"
#include "miezmerker/offline_auth.hpp"
#include "miezmerker/node_time.hpp"
#include "nvs.h"
#include <memory>

namespace miezmerker::esp32 {
bool crypto_selftest(class BoardCrypto& crypto, const NodeIdentity& identity);
class NvsBlob final : public AtomicBlob {
public:
    explicit NvsBlob(const char* partition, const char* key = "state_v1") : partition_(partition), key_(key) {}
    bool open();
    BlobResult read(Bytes& out) override;
    bool replace(const Bytes& bytes) override;
private:
    const char* partition_;
    const char* key_;
    nvs_handle_t handle_{};
    bool ready_{false};
};
// Secure RTC-free board clock (#130, ADR 0017).
//
// Observation capture stays truthfully UNKNOWN (wall_clock); security-trusted
// UTC is a separate bounded interval [lower, upper] established only by a
// backend-signed, challenge-bound proof verified against the independently
// pinned issuer key. Fresh boot/reboot/power loss starts UNKNOWN and fails
// closed; monotonic discontinuity, rollback, expiry or overflow invalidates.
// Protected TimeCorrect never becomes a pre-auth arbitrary-time setter.
class BoardClock final : public Clock, public ClaimClock, public ble::RtcControl {
public:
    std::uint64_t monotonic_ms() const override;
    bool monotonic_valid() const override { return timer_valid_; }
    std::uint32_t reset_reason() const override;
    WallClockReading wall_clock() const override { return {}; }
    // Claim timestamp: nominal midpoint only when bounded trust is tight
    // enough for the 5-minute backend freshness rule; 0 otherwise
    // (fail closed). Security authorization never uses this point; it uses
    // trusted_interval_ms with the strict [L,U] guard.
    std::uint64_t epoch_ms() const override;
    bool set_corrected_epoch_ms(std::uint64_t) override { return false; }
    // Security-clock state for diagnostics and #131 transport.
    node_time::SecurityTimeState security_state() const;
    // Current bounded UTC milliseconds. Advances with monotonic time plus
    // drift, enforces max age and detects rollback/discontinuity. Returns
    // false when UNKNOWN/INVALIDATED (no security-trusted time).
    bool trusted_interval_ms(std::int64_t& lower_ms, std::int64_t& upper_ms) const;
    // Strict credential guard (L >= iat && U < exp, no grace period).
    bool credential_covers(std::int64_t iat_s, std::int64_t exp_s) const;
    // Attached by app_main after Core/identity/evidence are ready. The
    // bootstrap manager owns the SecurityClock; this clock delegates interval
    // queries to it. Null means no trust (fail closed).
    void attach_bootstrap(node_time::NodeTimeBootstrapManager* manager) {
        bootstrap_ = manager;
    }
private:
    mutable bool timer_valid_{true};
    mutable std::int64_t last_timer_us_{0};
    node_time::NodeTimeBootstrapManager* bootstrap_{nullptr};
};
class BoardRandom final : public RandomSource {
public:
    void fill_random(std::span<std::uint8_t> out) override;
    static bool fill(std::span<unsigned char> out);
};
class PhysicalClaimMode final : public ClaimMode {
public:
    explicit PhysicalClaimMode(BoardClock& clock) : clock_(clock) {}
    bool active() const override { return clock_.monotonic_ms() < until_; }
    void activate() { until_ = clock_.monotonic_ms() + 120000; }
    void cancel() { until_ = 0; }
private:
    BoardClock& clock_;
    std::uint64_t until_{0};
};
class BoardCrypto final : public NodeCrypto {
public:
    explicit BoardCrypto(BoardClock& clock);
    bool generate_keypair(std::span<std::byte, 65> pub, std::span<std::byte, 32> priv) override;
    bool sign(std::span<const std::byte, 32> priv, std::span<const std::byte> message,
              std::span<std::byte, 64> signature) override;
    bool verify_claim(const std::string& receipt, VerifiedClaim& claim) override;
    bool keypair_valid(const NodeIdentity& identity);
    const std::array<unsigned char, 65>& issuer() const { return issuer_; }
    bool pinned() const { return issuer_[0] == 4; }
    // Bounded UTC for strict interval authorization (fail closed when unknown).
    bool trusted_interval_ms(std::int64_t& lower_ms, std::int64_t& upper_ms) const;
private:
    BoardClock& clock_;
    std::array<unsigned char, 65> issuer_{};
};
class BoardAuthorizer final : public ble::SyncAuthorizer {
public:
    BoardAuthorizer(NodeIdentityManager& identity, BoardCrypto& crypto) : identity_(identity), crypto_(crypto) {}
    bool begin(std::array<std::uint8_t, 32>& challenge) override;
    // Strict interval authorization: ignores the legacy point-time argument
    // and re-derives [L,U] from the security clock on every call, including
    // during long-running connections. Unknown bounds deny.
    bool authorize(const std::string& credential, const std::array<std::uint8_t, 64>& proof,
                   ble::TrustedEpochSeconds now) override;
    bool can_sync(ble::TrustedEpochSeconds now) const override;
    void disconnect() override;
private:
    NodeIdentityManager& identity_;
    BoardCrypto& crypto_;
    std::unique_ptr<OfflineAuthSession> session_;
};
class BoardSigner final : public ble::NodeSigner {
public:
    explicit BoardSigner(NodeIdentityManager& identity) : identity_(identity) {}
    bool sign_session(const std::array<std::uint8_t, 32>& challenge, std::array<std::uint8_t, 64>& signature) override;
private:
    NodeIdentityManager& identity_;
};
} // namespace miezmerker::esp32
