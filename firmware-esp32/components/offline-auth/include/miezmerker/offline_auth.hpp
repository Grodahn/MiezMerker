#pragma once
#include <array>
#include <cstdint>
#include <span>
#include <string>
#include <string_view>

namespace miezmerker {
struct ClaimReceiptInfo {
    std::string node_id, organization_id, organization_name, public_contact;
    std::array<unsigned char, 65> public_key{};
};
bool verify_claim_receipt(std::string_view receipt, std::span<const unsigned char, 65> issuer_key,
                          std::int64_t trusted_epoch_seconds, ClaimReceiptInfo& out);
// Construct one instance per BLE connection from durably provisioned owner/trust data.
// Unknown wall time must be supplied as zero and fails closed. Never trust a PWA clock.
class OfflineAuthSession {
public:
    using RandomFill = bool (*)(std::span<unsigned char>);
    OfflineAuthSession(std::string organization_id, std::array<unsigned char, 65> issuer_key);
    bool begin(RandomFill random, std::span<unsigned char, 32> challenge);
    bool authorize(std::string_view credential, std::span<const unsigned char> proof,
                   std::int64_t trusted_epoch_seconds);
    bool can_sync(std::int64_t trusted_epoch_seconds) const;
    void disconnect();
private:
    std::string organization_id_;
    std::array<unsigned char, 65> issuer_key_;
    std::array<unsigned char, 32> challenge_{};
    bool pending_{false};
    std::int64_t expires_{0};
    std::int64_t issued_{0};
};
} // namespace miezmerker
