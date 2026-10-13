#include "board.hpp"
#include "sdkconfig.h"
#include "esp_log.h"
#include "esp_timer.h"
#include "esp_system.h"
#include "esp_random.h"
#include "nvs_flash.h"
#include "psa/crypto.h"
#include <algorithm>
#include <cstring>

namespace miezmerker::esp32 {
bool NvsBlob::open() {
    auto err = nvs_flash_init_partition(partition_);
    if (err == ESP_OK) err = nvs_open_from_partition(partition_, "miezmerker", NVS_READWRITE, &handle_);
    ready_ = err == ESP_OK;
    if (!ready_) ESP_LOGE("storage", "open %s: %s; no automatic erase", partition_, esp_err_to_name(err));
    return ready_;
}
BlobResult NvsBlob::read(Bytes& out) {
    if (!ready_) return BlobResult::error;
    std::size_t length = 0;
    auto err = nvs_get_blob(handle_, key_, nullptr, &length);
    if (err == ESP_ERR_NVS_NOT_FOUND) return BlobResult::missing;
    if (err != ESP_OK || length < 8 || length > 20000) return BlobResult::error;
    out.resize(length);
    return nvs_get_blob(handle_, key_, out.data(), &length) == ESP_OK ? BlobResult::loaded : BlobResult::error;
}
bool NvsBlob::replace(const Bytes& bytes) {
    if (!ready_ || bytes.size() > 20000) return false;
    auto err = nvs_set_blob(handle_, key_, bytes.data(), bytes.size());
    if (err == ESP_OK) err = nvs_commit(handle_);
    if (err != ESP_OK) {
        ready_ = false; // Ambiguous commit must not be followed by a stale mutation.
        ESP_LOGE("storage", "commit %s: %s; reboot/recovery required", partition_, esp_err_to_name(err));
    }
    return err == ESP_OK;
}
std::uint64_t BoardClock::monotonic_ms() const {
    const auto now = esp_timer_get_time();
    if (now < 0 || now < last_timer_us_) { timer_valid_ = false; return 0; }
    last_timer_us_ = now;
    return static_cast<std::uint64_t>(now) / 1000;
}
node_time::SecurityTimeState BoardClock::security_state() const {
    if (!bootstrap_) return node_time::SecurityTimeState::UNKNOWN;
    return bootstrap_->state();
}
bool BoardClock::trusted_interval_ms(std::int64_t& lower_ms,
                                     std::int64_t& upper_ms) const {
    lower_ms = 0; upper_ms = 0;
    if (!bootstrap_ || !timer_valid_) return false;
    // Delegate to the bootstrap manager (mutates failure latch like the
    // monotonic timer latch above, hence the const_cast on this const query).
    auto* manager = const_cast<node_time::NodeTimeBootstrapManager*>(bootstrap_);
    return manager->current_interval(lower_ms, upper_ms);
}
bool BoardClock::credential_covers(std::int64_t iat_s, std::int64_t exp_s) const {
    std::int64_t lower = 0, upper = 0;
    if (!trusted_interval_ms(lower, upper)) return false;
    return node_time::credential_covers(iat_s, exp_s, lower, upper);
}
std::uint64_t BoardClock::epoch_ms() const {
    // Nominal midpoint for claim freshness only; authorization uses the
    // strict interval above, never this point. Returns 0 unless bounded
    // trust is tight enough for the backend 5-minute freshness rule.
    std::int64_t lower = 0, upper = 0;
    if (!trusted_interval_ms(lower, upper)) return 0;
    if (lower > upper) return 0;
    const std::uint64_t width =
        static_cast<std::uint64_t>(upper - lower);
    if (width > node_time::kClaimFreshnessMs) return 0;
    const auto midpoint = node_time::nominal_midpoint(lower, upper);
    if (!midpoint || *midpoint <= 0) return 0;
    return static_cast<std::uint64_t>(*midpoint);
}
std::uint32_t BoardClock::reset_reason() const { return static_cast<std::uint32_t>(esp_reset_reason()); }
void BoardRandom::fill_random(std::span<std::uint8_t> out) { esp_fill_random(out.data(), out.size()); }
bool BoardRandom::fill(std::span<unsigned char> out) { esp_fill_random(out.data(), out.size()); return true; }
namespace {
psa_key_attributes_t attributes(psa_key_usage_t usage) {
    psa_key_attributes_t a = PSA_KEY_ATTRIBUTES_INIT;
    psa_set_key_type(&a, PSA_KEY_TYPE_ECC_KEY_PAIR(PSA_ECC_FAMILY_SECP_R1));
    psa_set_key_bits(&a, 256); psa_set_key_usage_flags(&a, usage);
    psa_set_key_algorithm(&a, PSA_ALG_ECDSA(PSA_ALG_SHA_256));
    return a;
}
int hex(char c) {
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    if (c >= 'A' && c <= 'F') return c - 'A' + 10;
    return -1;
}
}
BoardCrypto::BoardCrypto(BoardClock& clock) : clock_(clock) {
    const std::string pin = CONFIG_MM_ISSUER_PUBLIC_KEY_HEX;
    if (pin.empty()) {
        ESP_LOGE("issuer_pin", "missing: configure MM_ISSUER_PUBLIC_KEY_HEX from independent local issuer setup and reflash; trusted UTC/claim/sync denied");
        return;
    }
    ESP_LOGI("issuer_pin", "independent P-256 pin; compare SHA256(point) with local issuer setup before registration");
    if (pin.size() != 130) ESP_LOGE("issuer_pin", "invalid length: expected 130 hex characters (04||X||Y); rebuild/reflash");
    ESP_ERROR_CHECK(pin.size() == 130 ? ESP_OK : ESP_ERR_INVALID_ARG);
    for (std::size_t i = 0; i < issuer_.size(); ++i) {
        const int a = hex(pin[2*i]), b = hex(pin[2*i+1]);
        ESP_ERROR_CHECK(a < 0 || b < 0 ? ESP_ERR_INVALID_ARG : ESP_OK);
        issuer_[i] = static_cast<unsigned char>((a << 4) | b);
    }
    psa_key_attributes_t attr = PSA_KEY_ATTRIBUTES_INIT;
    psa_set_key_type(&attr, PSA_KEY_TYPE_ECC_PUBLIC_KEY(PSA_ECC_FAMILY_SECP_R1));
    psa_set_key_bits(&attr, 256); mbedtls_svc_key_id_t id = 0;
    ESP_ERROR_CHECK(psa_import_key(&attr, issuer_.data(), issuer_.size(), &id) == PSA_SUCCESS ? ESP_OK : ESP_ERR_INVALID_ARG);
    psa_destroy_key(id); psa_reset_key_attributes(&attr);
    std::array<unsigned char, 32> digest{}; std::size_t digest_len = 0;
    ESP_ERROR_CHECK(psa_hash_compute(PSA_ALG_SHA_256, issuer_.data(), issuer_.size(),
        digest.data(), digest.size(), &digest_len) == PSA_SUCCESS && digest_len == digest.size()
        ? ESP_OK : ESP_FAIL);
    constexpr char hex_digits[] = "0123456789abcdef";
    std::array<char, 65> fingerprint{};
    for (std::size_t i = 0; i < digest.size(); ++i) {
        fingerprint[2*i] = hex_digits[digest[i] >> 4];
        fingerprint[2*i+1] = hex_digits[digest[i] & 15];
    }
    ESP_LOGI("issuer_pin", "SHA256(point)=%s", fingerprint.data()); // Public trust anchor only.
}
bool BoardCrypto::generate_keypair(std::span<std::byte, 65> pub, std::span<std::byte, 32> priv) {
    auto attr = attributes(PSA_KEY_USAGE_EXPORT | PSA_KEY_USAGE_SIGN_MESSAGE);
    mbedtls_svc_key_id_t id = 0;
    const auto generated = psa_generate_key(&attr, &id); psa_reset_key_attributes(&attr);
    if (generated != PSA_SUCCESS) return false;
    std::size_t pub_len = 0, priv_len = 0;
    const bool ok = psa_export_key(id, reinterpret_cast<unsigned char*>(priv.data()), priv.size(), &priv_len) == PSA_SUCCESS
        && psa_export_public_key(id, reinterpret_cast<unsigned char*>(pub.data()), pub.size(), &pub_len) == PSA_SUCCESS
        && priv_len == priv.size() && pub_len == pub.size();
    psa_destroy_key(id);
    return ok;
}
bool BoardCrypto::keypair_valid(const NodeIdentity& identity) {
    auto attr = attributes(PSA_KEY_USAGE_SIGN_MESSAGE);
    mbedtls_svc_key_id_t id = 0;
    const auto imported = psa_import_key(&attr, reinterpret_cast<const unsigned char*>(identity.private_key.data()), 32, &id);
    psa_reset_key_attributes(&attr);
    if (imported != PSA_SUCCESS) return false;
    std::array<std::byte, 65> pub{}; std::size_t length = 0;
    const bool ok = psa_export_public_key(id, reinterpret_cast<unsigned char*>(pub.data()), pub.size(), &length) == PSA_SUCCESS
        && length == pub.size() && pub == identity.public_key;
    psa_destroy_key(id);
    return ok;
}
bool BoardCrypto::sign(std::span<const std::byte, 32> priv, std::span<const std::byte> message,
        std::span<std::byte, 64> signature) {
    auto attr = attributes(PSA_KEY_USAGE_SIGN_MESSAGE);
    mbedtls_svc_key_id_t id = 0;
    const auto imported = psa_import_key(&attr, reinterpret_cast<const unsigned char*>(priv.data()), priv.size(), &id);
    psa_reset_key_attributes(&attr);
    if (imported != PSA_SUCCESS) return false;
    std::size_t length = 0;
    const auto status = psa_sign_message(id, PSA_ALG_ECDSA(PSA_ALG_SHA_256),
        reinterpret_cast<const unsigned char*>(message.data()), message.size(),
        reinterpret_cast<unsigned char*>(signature.data()), signature.size(), &length);
    psa_destroy_key(id);
    return status == PSA_SUCCESS && length == signature.size();
}
bool BoardCrypto::verify_claim(const std::string& receipt, VerifiedClaim& claim) {
    if (!pinned()) return false;
    std::int64_t lower = 0, upper = 0;
    if (!clock_.trusted_interval_ms(lower, upper)) return false;
    ClaimReceiptInfo verified;
    if (!verify_claim_receipt_with_bounds(receipt, issuer_, lower, upper, verified))
        return false;
    const auto node = NodeId::parse(verified.node_id);
    if (!node) return false;
    std::transform(node->bytes.begin(), node->bytes.end(), claim.node_id.begin(), [](auto b) { return static_cast<std::byte>(b); });
    std::transform(verified.public_key.begin(), verified.public_key.end(), claim.public_key.begin(), [](auto b) { return static_cast<std::byte>(b); });
    claim.organization_id = verified.organization_id; claim.organization_name = verified.organization_name;
    claim.public_contact = verified.public_contact;
    return true;
}
bool BoardCrypto::trusted_interval_ms(std::int64_t& lower_ms,
                                      std::int64_t& upper_ms) const {
    return clock_.trusted_interval_ms(lower_ms, upper_ms);
}
bool BoardAuthorizer::begin(std::array<std::uint8_t, 32>& challenge) {
    disconnect();
    session_ = std::make_unique<OfflineAuthSession>(identity_.identity().organization_id, crypto_.issuer());
    return session_->begin(BoardRandom::fill, challenge);
}
bool BoardAuthorizer::authorize(const std::string& credential, const std::array<std::uint8_t, 64>& proof,
        ble::TrustedEpochSeconds) {
    if (!session_) return false;
    std::int64_t lower = 0, upper = 0;
    if (!crypto_.trusted_interval_ms(lower, upper)) return false;
    std::array<unsigned char, 64> proof_bytes{};
    std::copy(proof.begin(), proof.end(), proof_bytes.begin());
    return session_->authorize_with_bounds(
        credential, std::span<const unsigned char>(proof_bytes.data(), 64), lower,
        upper);
}
bool BoardAuthorizer::can_sync(ble::TrustedEpochSeconds) const {
    if (!session_) return false;
    std::int64_t lower = 0, upper = 0;
    if (!crypto_.trusted_interval_ms(lower, upper)) return false;
    return session_->can_sync_with_bounds(lower, upper);
}
void BoardAuthorizer::disconnect() { if (session_) session_->disconnect(); session_.reset(); }
bool BoardSigner::sign_session(const std::array<std::uint8_t, 32>& challenge, std::array<std::uint8_t, 64>& signature) {
    return identity_.sign_session_challenge(std::as_bytes(std::span(challenge)),
        std::span<std::byte, 64>(reinterpret_cast<std::byte*>(signature.data()), signature.size()));
}
} // namespace miezmerker::esp32
