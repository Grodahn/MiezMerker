#pragma once
#include <array>
#include <cstdint>
#include <span>
#include <string>
#include <string_view>

namespace miezmerker {
// Shared with backend CredentialIssuerService; see docs/issue146-time-hardening.md.
inline constexpr std::size_t kMaxCredentialBytes = 4096;
inline constexpr std::size_t kMaxCredentialPayloadBytes = 3072;
struct ClaimReceiptInfo {
    std::string node_id, organization_id, organization_name, public_contact;
    std::array<unsigned char, 65> public_key{};
    std::uint32_t bowl_number{0};
    std::string display_name{};
};
bool verify_claim_receipt(std::string_view receipt, std::span<const unsigned char, 65> issuer_key,
                          std::int64_t trusted_epoch_seconds, ClaimReceiptInfo& out);
// Strict interval variant for #130: the credential/receipt is valid only when
// the entire possible UTC interval is inside [iat, exp). No grace period, no
// midpoint fallback. Bounds are UTC milliseconds from the security clock;
// unknown bounds (lower > upper or non-positive) fail closed.
bool verify_claim_receipt_with_bounds(std::string_view receipt,
                                      std::span<const unsigned char, 65> issuer_key,
                                      std::int64_t lower_utc_ms,
                                      std::int64_t upper_utc_ms,
                                      ClaimReceiptInfo& out);
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
    // Strict interval authorization for #130 (see above). Production BLE auth
    // must use these bounds from the security clock, not the point-time
    // overloads. Every call revalidates expiry against the current interval,
    // including during long-running connections.
    bool authorize_with_bounds(std::string_view credential,
                               std::span<const unsigned char> proof,
                               std::int64_t lower_utc_ms, std::int64_t upper_utc_ms);
    bool can_sync_with_bounds(std::int64_t lower_utc_ms,
                              std::int64_t upper_utc_ms) const;
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
