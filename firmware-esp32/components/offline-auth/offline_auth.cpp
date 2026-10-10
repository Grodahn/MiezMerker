#include "miezmerker/offline_auth.hpp"
#include "cJSON.h"
#ifdef ESP_PLATFORM
#include "psa/crypto.h"
#else
#include "mbedtls/ecdsa.h"
#include "mbedtls/sha256.h"
#endif
#include <cmath>
#include <algorithm>
#include <cstring>
#include <memory>
#include <vector>

namespace miezmerker {
namespace {
using Bytes = std::vector<unsigned char>;
using Json = std::unique_ptr<cJSON, decltype(&cJSON_Delete)>;

// Strict unpadded base64url: reject aliases, padding, and nonzero unused bits.
bool decode(std::string_view value, Bytes& out) {
    if (value.empty() || value.size() % 4 == 1) return false;
    unsigned accumulator = 0, bits = 0;
    for (char c : value) {
        int n = c >= 'A' && c <= 'Z' ? c - 'A'
            : c >= 'a' && c <= 'z' ? c - 'a' + 26
            : c >= '0' && c <= '9' ? c - '0' + 52 : c == '-' ? 62 : c == '_' ? 63 : -1;
        if (n < 0) return false;
        accumulator = (accumulator << 6) | static_cast<unsigned>(n);
        bits += 6;
        if (bits >= 8) { bits -= 8; out.push_back(static_cast<unsigned char>(accumulator >> bits)); }
    }
    return (accumulator & ((1U << bits) - 1)) == 0;
}

Json parse(const Bytes& bytes) {
    // cJSON strings are C strings; embedded NUL would make comparisons ambiguous.
    std::string text(bytes.begin(), bytes.end());
    if (text.find('\0') != std::string::npos || text.find("\\u0000") != std::string::npos)
        return Json(nullptr, cJSON_Delete);
    Json result(cJSON_ParseWithLengthOpts(text.c_str(), text.size() + 1, nullptr, true), cJSON_Delete);
    if (!cJSON_IsObject(result.get())) return Json(nullptr, cJSON_Delete);
    // Duplicate members can disagree across implementations; never accept them.
    for (auto a = result->child; a; a = a->next)
        for (auto b = a->next; b; b = b->next)
            if (std::strcmp(a->string, b->string) == 0) return Json(nullptr, cJSON_Delete);
    return result;
}
const cJSON* item(const cJSON* json, const char* name) { return cJSON_GetObjectItemCaseSensitive(json, name); }
std::string_view string(const cJSON* json, const char* name) {
    auto v = item(json, name);
    return cJSON_IsString(v) ? std::string_view(v->valuestring) : std::string_view{};
}
bool integer(const cJSON* json, const char* name, std::int64_t& out) {
    auto v = item(json, name);
    if (!cJSON_IsNumber(v) || !std::isfinite(v->valuedouble) || v->valuedouble < 0
        || v->valuedouble > 9007199254740991.0 || std::floor(v->valuedouble) != v->valuedouble) return false;
    out = static_cast<std::int64_t>(v->valuedouble);
    return true;
}
bool uuid(std::string_view v) {
    if (v.size() != 36) return false;
    for (std::size_t i = 0; i < v.size(); ++i) {
        if (i == 8 || i == 13 || i == 18 || i == 23) { if (v[i] != '-') return false; }
        else if (!((v[i] >= '0' && v[i] <= '9') || (v[i] >= 'a' && v[i] <= 'f'))) return false;
    }
    return true;
}
bool sha256(std::span<const unsigned char> input, unsigned char* hash) {
#ifdef ESP_PLATFORM
    std::size_t length = 0;
    return psa_hash_compute(PSA_ALG_SHA_256, input.data(), input.size(), hash, 32, &length) == PSA_SUCCESS && length == 32;
#else
    return mbedtls_sha256(input.data(), input.size(), hash, 0) == 0;
#endif
}
bool verify(std::span<const unsigned char, 65> key, std::span<const unsigned char> message,
            std::span<const unsigned char> signature) {
    if (signature.size() != 64 || key[0] != 4) return false;
#ifdef ESP_PLATFORM
    psa_key_attributes_t attributes = PSA_KEY_ATTRIBUTES_INIT;
    psa_set_key_type(&attributes, PSA_KEY_TYPE_ECC_PUBLIC_KEY(PSA_ECC_FAMILY_SECP_R1));
    psa_set_key_bits(&attributes, 256);
    psa_set_key_usage_flags(&attributes, PSA_KEY_USAGE_VERIFY_MESSAGE);
    psa_set_key_algorithm(&attributes, PSA_ALG_ECDSA(PSA_ALG_SHA_256));
    mbedtls_svc_key_id_t id = 0;
    const auto imported = psa_import_key(&attributes, key.data(), key.size(), &id);
    psa_reset_key_attributes(&attributes);
    if (imported != PSA_SUCCESS) return false;
    const auto status = psa_verify_message(id, PSA_ALG_ECDSA(PSA_ALG_SHA_256),
        message.data(), message.size(), signature.data(), signature.size());
    psa_destroy_key(id);
    return status == PSA_SUCCESS;
#else
    unsigned char hash[32];
    if (!sha256(message, hash)) return false;
    mbedtls_ecp_group group; mbedtls_ecp_group_init(&group);
    mbedtls_ecp_point point; mbedtls_ecp_point_init(&point);
    mbedtls_mpi r, s; mbedtls_mpi_init(&r); mbedtls_mpi_init(&s);
    bool valid = mbedtls_ecp_group_load(&group, MBEDTLS_ECP_DP_SECP256R1) == 0
        && mbedtls_ecp_point_read_binary(&group, &point, key.data(), key.size()) == 0
        && mbedtls_ecp_check_pubkey(&group, &point) == 0
        && mbedtls_mpi_read_binary(&r, signature.data(), 32) == 0
        && mbedtls_mpi_read_binary(&s, signature.data() + 32, 32) == 0
        && mbedtls_ecdsa_verify(&group, hash, sizeof(hash), &point, &r, &s) == 0;
    mbedtls_mpi_free(&s); mbedtls_mpi_free(&r);
    mbedtls_ecp_point_free(&point); mbedtls_ecp_group_free(&group);
    return valid;
#endif
}
Json verified_payload(std::string_view credential, std::span<const unsigned char, 65> issuer_key) {
    auto reject = [] { return Json(nullptr, cJSON_Delete); };
    if (credential.size() > 4096) return reject();
    auto first = credential.find('.');
    auto second = credential.find('.', first == std::string_view::npos ? 0 : first + 1);
    if (first == std::string_view::npos || second == std::string_view::npos
        || credential.find('.', second + 1) != std::string_view::npos) return reject();
    Bytes header_bytes, payload_bytes, signature;
    if (!decode(credential.substr(0, first), header_bytes)
        || !decode(credential.substr(first + 1, second - first - 1), payload_bytes)
        || !decode(credential.substr(second + 1), signature)) return reject();
    auto header = parse(header_bytes);
    if (!header || string(header.get(), "alg") != "ES256"
        || string(header.get(), "kid") != "miezmerker-issuer-v1"
        || item(header.get(), "crit") || item(header.get(), "b64")) return reject();
    auto signed_bytes = std::span(reinterpret_cast<const unsigned char*>(credential.data()), second);
    if (!verify(issuer_key, signed_bytes, signature)) return reject();
    return parse(payload_bytes);
}
bool point(const cJSON* c, const char* x_name, const char* y_name, const char* fingerprint_name,
        std::array<unsigned char, 65>& key) {
    Bytes x, y, fingerprint;
    if (!decode(string(c, x_name), x) || x.size() != 32
        || !decode(string(c, y_name), y) || y.size() != 32
        || !decode(string(c, fingerprint_name), fingerprint) || fingerprint.size() != 32) return false;
    key[0] = 4;
    std::copy(x.begin(), x.end(), key.begin() + 1);
    std::copy(y.begin(), y.end(), key.begin() + 33);
    unsigned char digest[32];
    return sha256(key, digest) && std::equal(fingerprint.begin(), fingerprint.end(), digest);
}
} // namespace

OfflineAuthSession::OfflineAuthSession(std::string organization_id,
        std::array<unsigned char, 65> issuer_key)
    : organization_id_(std::move(organization_id)), issuer_key_(issuer_key) {}

bool OfflineAuthSession::begin(RandomFill random, std::span<unsigned char, 32> challenge) {
    disconnect();
    if (!random || !random(challenge_)) return false;
    std::copy(challenge_.begin(), challenge_.end(), challenge.begin());
    pending_ = true;
    return true;
}

bool OfflineAuthSession::authorize(std::string_view credential,
        std::span<const unsigned char> proof, std::int64_t now) {
    bool pending = pending_;
    pending_ = false; // Every attempt consumes the nonce, including failed attempts.
    expires_ = 0;
    if (!pending || now <= 0 || credential.size() > 4096 || proof.size() != 64) return false;
    auto claims = verified_payload(credential, issuer_key_);
    if (!claims) return false;
    auto c = claims.get();
    std::int64_t version, issued, expires;
    if (string(c, "iss") != "miezmerker" || string(c, "kind") != "offline"
        || !integer(c, "ver", version) || version != 1
        || string(c, "org") != organization_id_ || !uuid(organization_id_)
        || !uuid(string(c, "sub")) || !uuid(string(c, "dev"))
        || !integer(c, "iat", issued) || !integer(c, "exp", expires)
        || issued > now || expires <= now || issued >= expires
        || (string(c, "role") != "MEMBER" && string(c, "role") != "ADMIN")) return false;
    auto scopes = item(c, "scope");
    if (!cJSON_IsArray(scopes)) return false;
    bool sync = false;
    for (auto scope = scopes->child; scope; scope = scope->next) {
        if (!cJSON_IsString(scope)) return false;
        if (std::strcmp(scope->valuestring, "node:sync") == 0) sync = true;
    }
    if (!sync) return false;
    std::array<unsigned char, 65> device_key{};
    if (!point(c, "dpk_x", "dpk_y", "dpf", device_key)
        || !verify(device_key, challenge_, proof)) return false;
    issued_ = issued;
    expires_ = expires;
    return true;
}

bool OfflineAuthSession::can_sync(std::int64_t now) const {
    return now > 0 && issued_ <= now && now < expires_;
}
void OfflineAuthSession::disconnect() {
    pending_ = false; expires_ = 0; issued_ = 0; challenge_.fill(0);
}
bool verify_claim_receipt(std::string_view receipt, std::span<const unsigned char, 65> issuer_key,
        std::int64_t now, ClaimReceiptInfo& out) {
    if (now <= 0) return false;
    auto claims = verified_payload(receipt, issuer_key);
    if (!claims) return false;
    const auto c = claims.get();
    std::int64_t version, issued, expires;
    if (string(c, "iss") != "miezmerker" || string(c, "kind") != "claim"
        || !integer(c, "ver", version) || version != 1
        || !uuid(string(c, "sub")) || !uuid(string(c, "org"))
        || !integer(c, "iat", issued) || !integer(c, "exp", expires)
        || issued > now || expires <= now || issued >= expires) return false;
    ClaimReceiptInfo candidate;
    candidate.node_id = string(c, "sub"); candidate.organization_id = string(c, "org");
    candidate.organization_name = string(c, "org_name"); candidate.public_contact = string(c, "contact");
    if (candidate.organization_name.empty() || candidate.organization_name.size() > 512
        || candidate.public_contact.size() > 512
        || !point(c, "ndpk_x", "ndpk_y", "ndpf", candidate.public_key)) return false;
    // ipk fields are never a trust anchor. If present, require the signed echo to match the independently pinned key.
    Bytes x, y;
    if (!decode(string(c, "ipk_x"), x) || x.size() != 32
        || !decode(string(c, "ipk_y"), y) || y.size() != 32
        || !std::equal(x.begin(), x.end(), issuer_key.begin() + 1)
        || !std::equal(y.begin(), y.end(), issuer_key.begin() + 33)) return false;
    out = std::move(candidate);
    return true;
}
} // namespace miezmerker
