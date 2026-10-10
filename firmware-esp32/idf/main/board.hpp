#pragma once
#include "miezmerker/persistent_state.hpp"
#include "miezmerker/ble_sync.hpp"
#include "miezmerker/offline_auth.hpp"
#include "nvs.h"
#include <memory>

namespace miezmerker::esp32 {
bool crypto_selftest(class BoardCrypto& crypto, const NodeIdentity& identity);
class NvsBlob final : public AtomicBlob {
public:
    explicit NvsBlob(const char* partition) : partition_(partition) {}
    bool open();
    BlobResult read(Bytes& out) override;
    bool replace(const Bytes& bytes) override;
private:
    const char* partition_;
    nvs_handle_t handle_{};
    bool ready_{false};
};
class BoardClock final : public Clock, public ClaimClock, public ble::RtcControl {
public:
    std::uint64_t monotonic_ms() const override;
    WallClockReading wall_clock() const override { return {}; }
    std::uint64_t epoch_ms() const override { return 0; }
    bool set_corrected_epoch_ms(std::uint64_t) override { return false; }
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
private:
    BoardClock& clock_;
    std::array<unsigned char, 65> issuer_{};
};
class BoardAuthorizer final : public ble::SyncAuthorizer {
public:
    BoardAuthorizer(NodeIdentityManager& identity, BoardCrypto& crypto) : identity_(identity), crypto_(crypto) {}
    bool begin(std::array<std::uint8_t, 32>& challenge) override;
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
