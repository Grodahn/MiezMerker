#include "miezmerker/node_time_verify.hpp"
#include "cJSON.h"
#ifdef ESP_PLATFORM
#include "psa/crypto.h"
#else
#include "mbedtls/ecdsa.h"
#include "mbedtls/sha256.h"
#endif
#include <cmath>
#include <cstring>
#include <memory>

namespace miezmerker {
namespace {
using Bytes = std::vector<unsigned char>;
using Json = std::unique_ptr<cJSON, decltype(&cJSON_Delete)>;

bool decode_strict(std::string_view value, Bytes& out) {
    out.clear();
    if (value.empty() || value.size() % 4 == 1) return false;
    unsigned accumulator = 0;
    int bits = 0;
    for (char c : value) {
        int n = c >= 'A' && c <= 'Z' ? c - 'A'
                : c >= 'a' && c <= 'z' ? c - 'a' + 26
                : c >= '0' && c <= '9' ? c - '0' + 52 : c == '-' ? 62 : c == '_' ? 63 : -1;
        if (n < 0) return false;
        accumulator = (accumulator << 6) | static_cast<unsigned>(n);
        bits += 6;
        if (bits >= 8) {
            bits -= 8;
            out.push_back(static_cast<unsigned char>(accumulator >> bits));
        }
    }
    if (bits && (accumulator & ((1U << bits) - 1U)) != 0) return false;
    return true;
}

std::string encode_canonical(std::span<const unsigned char> bytes) {
    static constexpr char kAlphabet[] =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
    std::string out;
    unsigned accumulator = 0;
    int bits = 0;
    for (auto b : bytes) {
        accumulator = (accumulator << 8) | b;
        bits += 8;
        while (bits >= 6) {
            bits -= 6;
            out += kAlphabet[(accumulator >> bits) & 63];
        }
    }
    if (bits) out += kAlphabet[(accumulator << (6 - bits)) & 63];
    return out;
}

bool canonical_b64u(std::string_view text, std::size_t expected_bytes) {
    if (text.size() != (expected_bytes * 8 + 5) / 6) return false;
    Bytes decoded;
    if (!decode_strict(text, decoded) || decoded.size() != expected_bytes) return false;
    return encode_canonical(decoded) == text;
}

Json parse_strict(const Bytes& bytes) {
    std::string text(bytes.begin(), bytes.end());
    if (text.find('\0') != std::string::npos || text.find("\\u0000") != std::string::npos)
        return Json(nullptr, cJSON_Delete);
    const char* end = nullptr;
    Json result(cJSON_ParseWithLengthOpts(text.c_str(), text.size() + 1, &end, true),
                cJSON_Delete);
    if (!result || !cJSON_IsObject(result.get())) return Json(nullptr, cJSON_Delete);
    // No trailing tokens beyond optional JSON whitespace.
    if (end) {
        while (*end == ' ' || *end == '\t' || *end == '\n' || *end == '\r') ++end;
        if (*end != '\0') return Json(nullptr, cJSON_Delete);
    }
    for (auto a = result->child; a; a = a->next)
        for (auto b = a->next; b; b = b->next)
            if (a->string && b->string && std::strcmp(a->string, b->string) == 0)
                return Json(nullptr, cJSON_Delete);
    return result;
}

const cJSON* item(const cJSON* json, const char* name) {
    return cJSON_GetObjectItemCaseSensitive(json, name);
}
std::string_view field_string(const cJSON* json, const char* name) {
    auto v = item(json, name);
    return cJSON_IsString(v) && v->valuestring ? std::string_view(v->valuestring)
                                              : std::string_view{};
}
bool field_integer(const cJSON* json, const char* name, std::int64_t& out) {
    auto v = item(json, name);
    if (!cJSON_IsNumber(v) || !std::isfinite(v->valuedouble) || v->valuedouble < 0 ||
        v->valuedouble > 9007199254740991.0 || std::floor(v->valuedouble) != v->valuedouble)
        return false;
    out = static_cast<std::int64_t>(v->valuedouble);
    return true;
}
bool uuid_v4(std::string_view v) {
    if (v.size() != 36) return false;
    for (std::size_t i = 0; i < v.size(); ++i) {
        if (i == 8 || i == 13 || i == 18 || i == 23) {
            if (v[i] != '-') return false;
        } else if (!((v[i] >= '0' && v[i] <= '9') || (v[i] >= 'a' && v[i] <= 'f'))) {
            return false;
        }
    }
    // RFC 4122 version 4 + variant 10xx.
    if (v[14] != '4') return false;
    char variant = v[19];
    return variant == '8' || variant == '9' || variant == 'a' || variant == 'b';
}
}  // namespace

bool node_time_sha256(std::span<const unsigned char> input,
                      std::array<unsigned char, 32>& digest) {
#ifdef ESP_PLATFORM
    std::size_t length = 0;
    return psa_hash_compute(PSA_ALG_SHA_256, input.data(), input.size(), digest.data(),
                            digest.size(), &length) == PSA_SUCCESS &&
           length == digest.size();
#else
    return mbedtls_sha256(input.data(), input.size(), digest.data(), 0) == 0;
#endif
}

bool node_time_verify_es256(std::span<const unsigned char, 65> key,
                            std::span<const unsigned char> message,
                            std::span<const unsigned char, 64> signature) {
    if (key[0] != 4) return false;
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
    const auto status = psa_verify_message(id, PSA_ALG_ECDSA(PSA_ALG_SHA_256), message.data(),
                                           message.size(), signature.data(),
                                           signature.size());
    psa_destroy_key(id);
    return status == PSA_SUCCESS;
#else
    unsigned char hash[32];
    std::array<unsigned char, 32> digest{};
    if (!node_time_sha256(message, digest)) return false;
    std::copy(digest.begin(), digest.end(), hash);
    mbedtls_ecp_group group;
    mbedtls_ecp_group_init(&group);
    mbedtls_ecp_point point;
    mbedtls_ecp_point_init(&point);
    mbedtls_mpi r, s;
    mbedtls_mpi_init(&r);
    mbedtls_mpi_init(&s);
    bool valid = mbedtls_ecp_group_load(&group, MBEDTLS_ECP_DP_SECP256R1) == 0 &&
                 mbedtls_ecp_point_read_binary(&group, &point, key.data(), key.size()) == 0 &&
                 mbedtls_ecp_check_pubkey(&group, &point) == 0 &&
                 mbedtls_mpi_read_binary(&r, signature.data(), 32) == 0 &&
                 mbedtls_mpi_read_binary(&s, signature.data() + 32, 32) == 0 &&
                 mbedtls_ecdsa_verify(&group, hash, sizeof(hash), &point, &r, &s) == 0;
    mbedtls_mpi_free(&s);
    mbedtls_mpi_free(&r);
    mbedtls_ecp_point_free(&point);
    mbedtls_ecp_group_free(&group);
    return valid;
#endif
}

bool verify_time_proof(std::string_view proof,
                       std::span<const unsigned char, 65> issuer_key,
                       TimeProofClaims& out) {
    out = {};
    if (issuer_key[0] != 4) return false;
    if (proof.empty() || proof.size() > 2048) return false;
    const auto first = proof.find('.');
    if (first == std::string_view::npos) return false;
    const auto second = proof.find('.', first + 1);
    if (second == std::string_view::npos) return false;
    if (proof.find('.', second + 1) != std::string_view::npos) return false;
    const auto header_b64 = proof.substr(0, first);
    const auto payload_b64 = proof.substr(first + 1, second - first - 1);
    const auto sig_b64 = proof.substr(second + 1);
    if (header_b64.empty() || payload_b64.empty() || sig_b64.empty()) return false;
    Bytes header_bytes, payload_bytes, sig_bytes;
    if (!decode_strict(header_b64, header_bytes) ||
        !decode_strict(payload_b64, payload_bytes) ||
        !decode_strict(sig_b64, sig_bytes))
        return false;
    // Canonical base64url: re-encoding must reproduce the exact input.
    if (encode_canonical(header_bytes) != header_b64 ||
        encode_canonical(payload_bytes) != payload_b64 ||
        encode_canonical(sig_bytes) != sig_b64)
        return false;
    if (sig_bytes.size() != 64) return false;
    if (header_bytes.size() > 1024 || payload_bytes.size() > 1536) return false;
    auto header = parse_strict(header_bytes);
    if (!header) return false;
    // Strict header: exactly alg + kid, no crit/b64, ES256 + pinned kid.
    if (!cJSON_IsObject(header.get()) || header->child == nullptr) return false;
    int header_fields = 0;
    for (auto c = header->child; c; c = c->next) ++header_fields;
    if (header_fields != 2) return false;
    if (field_string(header.get(), "alg") != "ES256") return false;
    if (field_string(header.get(), "kid") != "miezmerker-issuer-v1") return false;
    if (item(header.get(), "crit") || item(header.get(), "b64")) return false;
    // Signature covers the exact ASCII header.payload bytes.
    std::vector<unsigned char> signed_input(proof.size() > second ? second : 0);
    for (std::size_t i = 0; i < second && i < proof.size(); ++i)
        signed_input[i] = static_cast<unsigned char>(proof[i]);
    std::array<unsigned char, 64> signature{};
    std::copy(sig_bytes.begin(), sig_bytes.end(), signature.begin());
    if (!node_time_verify_es256(issuer_key,
                                std::span<const unsigned char>(signed_input),
                                std::span<const unsigned char, 64>(signature)))
        return false;
    auto payload = parse_strict(payload_bytes);
    if (!payload) return false;
    int payload_fields = 0;
    for (auto c = payload->child; c; c = c->next) ++payload_fields;
    if (payload_fields != 11) return false;
    // Exact BackendTimeProofV1 claim set (#129): no more, no fewer.
    const char* all[] = {"iss", "kid",  "kind", "ver",   "node", "incarnation",
                         "boot", "nonce", "dpf", "utc_ms", "jti"};
    for (auto name : all)
        if (!item(payload.get(), name)) return false;
    // Count check above already enforces exactly 11; verify no unknown member.
    for (auto c = payload->child; c; c = c->next) {
        bool known = false;
        for (auto name : all)
            if (c->string && std::strcmp(c->string, name) == 0) known = true;
        if (!known) return false;
    }
    if (field_string(payload.get(), "iss") != "miezmerker") return false;
    if (field_string(payload.get(), "kid") != "miezmerker-issuer-v1") return false;
    if (field_string(payload.get(), "kind") != "node-time-bootstrap") return false;
    std::int64_t ver = 0, boot = 0, utc = 0;
    if (!field_integer(payload.get(), "ver", ver) || ver != 1) return false;
    if (!field_integer(payload.get(), "boot", boot) || boot < 1 || boot > 0xffffffffLL)
        return false;
    if (!field_integer(payload.get(), "utc_ms", utc) || utc <= 0) return false;
    const auto node = field_string(payload.get(), "node");
    const auto incarnation = field_string(payload.get(), "incarnation");
    const auto nonce = field_string(payload.get(), "nonce");
    const auto dpf = field_string(payload.get(), "dpf");
    const auto jti = field_string(payload.get(), "jti");
    if (!uuid_v4(node) || !uuid_v4(incarnation) || !uuid_v4(jti)) return false;
    if (!canonical_b64u(nonce, 32) || !canonical_b64u(dpf, 32)) return false;
    out.node_id.assign(node.begin(), node.end());
    out.incarnation.assign(incarnation.begin(), incarnation.end());
    out.nonce_b64u.assign(nonce.begin(), nonce.end());
    out.dpf_b64u.assign(dpf.begin(), dpf.end());
    out.jti.assign(jti.begin(), jti.end());
    out.boot = static_cast<std::uint32_t>(boot);
    out.utc_ms = utc;
    out.version = 1;
    out.signed_input = std::move(signed_input);
    out.signature = signature;
    return true;
}

}  // namespace miezmerker
