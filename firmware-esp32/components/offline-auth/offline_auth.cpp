#include "miezmerker/offline_auth.hpp"
#include "cJSON.h"
#include "mbedtls/ecdsa.h"
#include "mbedtls/sha256.h"
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
bool verify(std::span<const unsigned char, 65> key, std::span<const unsigned char> message,
            std::span<const unsigned char> signature) {
    if (signature.size() != 64 || key[0] != 4) return false;
    unsigned char hash[32];
    if (mbedtls_sha256(message.data(), message.size(), hash, 0) != 0) return false;
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
    auto first = credential.find('.');
    auto second = credential.find('.', first == std::string_view::npos ? 0 : first + 1);
    if (first == std::string_view::npos || second == std::string_view::npos
        || credential.find('.', second + 1) != std::string_view::npos) return false;
    Bytes header_bytes, payload_bytes, signature;
    if (!decode(credential.substr(0, first), header_bytes)
        || !decode(credential.substr(first + 1, second - first - 1), payload_bytes)
        || !decode(credential.substr(second + 1), signature)) return false;
    auto header = parse(header_bytes);
    if (!header || string(header.get(), "alg") != "ES256"
        || string(header.get(), "kid") != "miezmerker-issuer-v1"
        || item(header.get(), "crit") || item(header.get(), "b64")) return false;
    auto signed_bytes = std::span(reinterpret_cast<const unsigned char*>(credential.data()), second);
    if (!verify(issuer_key_, signed_bytes, signature)) return false;
    auto claims = parse(payload_bytes);
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
    Bytes x, y, fingerprint;
    if (!decode(string(c, "dpk_x"), x) || x.size() != 32
        || !decode(string(c, "dpk_y"), y) || y.size() != 32
        || !decode(string(c, "dpf"), fingerprint) || fingerprint.size() != 32) return false;
    std::array<unsigned char, 65> device_key{}; device_key[0] = 4;
    std::copy(x.begin(), x.end(), device_key.begin() + 1);
    std::copy(y.begin(), y.end(), device_key.begin() + 33);
    unsigned char digest[32];
    if (mbedtls_sha256(device_key.data(), device_key.size(), digest, 0) != 0
        || !std::equal(fingerprint.begin(), fingerprint.end(), digest)
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
} // namespace miezmerker
