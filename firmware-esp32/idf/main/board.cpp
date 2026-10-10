#include "board.hpp"
#include "sdkconfig.h"
#include "esp_log.h"
#include "esp_timer.h"
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
    auto err = nvs_get_blob(handle_, "state_v1", nullptr, &length);
    if (err == ESP_ERR_NVS_NOT_FOUND) return BlobResult::missing;
    if (err != ESP_OK || length < 8 || length > 20000) return BlobResult::error;
    out.resize(length);
    return nvs_get_blob(handle_, "state_v1", out.data(), &length) == ESP_OK ? BlobResult::loaded : BlobResult::error;
}
bool NvsBlob::replace(const Bytes& bytes) {
    if (!ready_ || bytes.size() > 20000) return false;
    auto err = nvs_set_blob(handle_, "state_v1", bytes.data(), bytes.size());
    if (err == ESP_OK) err = nvs_commit(handle_);
    if (err != ESP_OK) {
        ready_ = false; // Ambiguous commit must not be followed by a stale mutation.
        ESP_LOGE("storage", "commit %s: %s; reboot/recovery required", partition_, esp_err_to_name(err));
    }
    return err == ESP_OK;
}
std::uint64_t BoardClock::monotonic_ms() const { return static_cast<std::uint64_t>(esp_timer_get_time()) / 1000; }
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
    if (pin.empty()) return;
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
    ClaimReceiptInfo verified;
    if (!pinned() || !verify_claim_receipt(receipt, issuer_, static_cast<std::int64_t>(clock_.epoch_ms() / 1000), verified)) return false;
    const auto node = NodeId::parse(verified.node_id);
    if (!node) return false;
    std::transform(node->bytes.begin(), node->bytes.end(), claim.node_id.begin(), [](auto b) { return static_cast<std::byte>(b); });
    std::transform(verified.public_key.begin(), verified.public_key.end(), claim.public_key.begin(), [](auto b) { return static_cast<std::byte>(b); });
    claim.organization_id = verified.organization_id; claim.organization_name = verified.organization_name;
    claim.public_contact = verified.public_contact;
    return true;
}
bool BoardAuthorizer::begin(std::array<std::uint8_t, 32>& challenge) {
    disconnect();
    session_ = std::make_unique<OfflineAuthSession>(identity_.identity().organization_id, crypto_.issuer());
    return session_->begin(BoardRandom::fill, challenge);
}
bool BoardAuthorizer::authorize(const std::string& credential, const std::array<std::uint8_t, 64>& proof,
        ble::TrustedEpochSeconds now) { return session_ && session_->authorize(credential, proof, now); }
bool BoardAuthorizer::can_sync(ble::TrustedEpochSeconds now) const { return session_ && session_->can_sync(now); }
void BoardAuthorizer::disconnect() { if (session_) session_->disconnect(); session_.reset(); }
bool BoardSigner::sign_session(const std::array<std::uint8_t, 32>& challenge, std::array<std::uint8_t, 64>& signature) {
    return identity_.sign_session_challenge(std::as_bytes(std::span(challenge)),
        std::span<std::byte, 64>(reinterpret_cast<std::byte*>(signature.data()), signature.size()));
}
} // namespace miezmerker::esp32
