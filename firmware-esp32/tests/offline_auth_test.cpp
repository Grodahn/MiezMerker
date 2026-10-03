#include "miezmerker/offline_auth.hpp"
#include "cJSON.h"
#include "mbedtls/base64.h"
#include "mbedtls/ecdsa.h"
#include "mbedtls/sha256.h"
#include <algorithm>
#include <cassert>
#include <fstream>
#include <iterator>
#include <string>
#include <vector>
using namespace miezmerker;
using Bytes = std::vector<unsigned char>;
static Bytes decode(std::string v) {
    for (auto& c : v) { if (c == '-') c = '+'; if (c == '_') c = '/'; }
    while (v.size() % 4) v += '=';
    Bytes out(v.size()); std::size_t length;
    assert(mbedtls_base64_decode(out.data(), out.size(), &length,
        reinterpret_cast<const unsigned char*>(v.data()), v.size()) == 0);
    out.resize(length); return out;
}
static bool nonce(std::span<unsigned char> out) {
    for (std::size_t i = 0; i < out.size(); ++i) out[i] = static_cast<unsigned char>(i);
    return true;
}
static bool other_nonce(std::span<unsigned char> out) { for (auto& b : out) b = 42; return true; }
static std::string value(cJSON* object, const char* name) {
    return cJSON_GetObjectItemCaseSensitive(object, name)->valuestring;
}
static std::string encode(std::span<const unsigned char> bytes) {
    Bytes out(4 * ((bytes.size() + 2) / 3) + 1); std::size_t length;
    assert(mbedtls_base64_encode(out.data(), out.size(), &length, bytes.data(), bytes.size()) == 0);
    std::string result(out.begin(), out.begin() + length);
    for (auto& c : result) { if (c == '+') c = '-'; if (c == '/') c = '_'; }
    while (result.ends_with('=')) result.pop_back();
    return result;
}
// Test-only key scalar 1. Never provision a production node with this key.
static int test_rng(void*, unsigned char* out, std::size_t length) {
    static unsigned state = 17;
    for (std::size_t i = 0; i < length; ++i) { state = state * 1664525 + 1013904223; out[i] = state >> 24; }
    return 0;
}
static std::string sign(std::string_view header, std::string_view payload, mbedtls_ecp_group& group) {
    auto bytes = [](std::string_view v) { return std::span(reinterpret_cast<const unsigned char*>(v.data()), v.size()); };
    std::string message = encode(bytes(header)) + "." + encode(bytes(payload));
    unsigned char hash[32]; assert(mbedtls_sha256(bytes(message).data(), message.size(), hash, 0) == 0);
    mbedtls_mpi d, r, s; mbedtls_mpi_init(&d); mbedtls_mpi_init(&r); mbedtls_mpi_init(&s);
    assert(mbedtls_mpi_lset(&d, 1) == 0);
    assert(mbedtls_ecdsa_sign(&group, &r, &s, &d, hash, 32, test_rng, nullptr) == 0);
    std::array<unsigned char, 64> signature{};
    assert(mbedtls_mpi_write_binary(&r, signature.data(), 32) == 0);
    assert(mbedtls_mpi_write_binary(&s, signature.data() + 32, 32) == 0);
    mbedtls_mpi_free(&d); mbedtls_mpi_free(&r); mbedtls_mpi_free(&s);
    return message + "." + encode(signature);
}
int main(int argc, char** argv) {
    assert(argc == 2);
    std::ifstream input(argv[1]);
    std::string fixture((std::istreambuf_iterator<char>(input)), {});
    auto json = cJSON_Parse(fixture.c_str()); assert(json);
    auto issuer = cJSON_GetObjectItemCaseSensitive(json, "issuer_public_key");
    std::array<unsigned char, 65> key{}; key[0] = 4;
    auto x = decode(value(issuer, "x")), y = decode(value(issuer, "y"));
    std::copy(x.begin(), x.end(), key.begin() + 1); std::copy(y.begin(), y.end(), key.begin() + 33);
    std::string org = value(json, "organization_id"), jwt = value(json, "credential_jwt");
    auto proof = decode(value(json, "challenge_signature_b64u"));
    std::array<unsigned char, 32> challenge{};
    constexpr std::int64_t now = 1790899200;
    OfflineAuthSession session(org, key);
    assert(!session.can_sync(now));
    assert(!session.authorize(jwt, proof, now));
    assert(session.begin(nonce, challenge));
    assert(session.authorize(jwt, proof, now)); // Java golden JWS + raw PoP.
    assert(session.can_sync(now));
    assert(!session.can_sync(1917129600)); // Exact expiry, including ongoing connection.
    assert(!session.can_sync(0));
    assert(!session.authorize(jwt, proof, now)); // Repeated nonce/proof.
    assert(session.begin(other_nonce, challenge));
    assert(!session.authorize(jwt, proof, now)); // Prior connection's proof.
    assert(session.begin(nonce, challenge));
    proof[0] ^= 1;
    assert(!session.authorize(jwt, proof, now)); // Copied credential / other private key.
    proof[0] ^= 1;
    assert(!session.authorize(jwt, proof, now)); // Failed attempt consumes challenge too.
    assert(session.begin(nonce, challenge));
    assert(!session.authorize(jwt, proof, 1917129600));
    assert(session.begin(nonce, challenge));
    assert(!session.authorize(jwt, proof, now - 1)); // Future iat.
    assert(session.begin(nonce, challenge));
    assert(!session.authorize(jwt, proof, 0)); // Unknown trusted clock.
    assert(session.begin(nonce, challenge));
    assert(session.authorize(jwt, proof, now));
    session.disconnect(); assert(!session.can_sync(now));
    OfflineAuthSession foreign("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", key);
    assert(foreign.begin(nonce, challenge)); assert(!foreign.authorize(jwt, proof, now));
    auto bad_key = key; bad_key[5] ^= 1;
    OfflineAuthSession wrong_issuer(org, bad_key);
    assert(wrong_issuer.begin(nonce, challenge)); assert(!wrong_issuer.authorize(jwt, proof, now));
    for (const auto& bad : {jwt + ".", std::string("malformed"), jwt.substr(0, jwt.size() - 1),
                            jwt.substr(0, jwt.size() - 2) + "AA"}) {
        assert(session.begin(nonce, challenge)); assert(!session.authorize(bad, proof, now));
    }
    mbedtls_ecp_group group; mbedtls_ecp_group_init(&group);
    assert(mbedtls_ecp_group_load(&group, MBEDTLS_ECP_DP_SECP256R1) == 0);
    std::array<unsigned char, 65> test_key{}; std::size_t key_length;
    assert(mbedtls_ecp_point_write_binary(&group, &group.G, MBEDTLS_ECP_PF_UNCOMPRESSED,
        &key_length, test_key.data(), test_key.size()) == 0);
    auto first = jwt.find('.'), second = jwt.find('.', first + 1);
    auto payload_bytes = decode(jwt.substr(first + 1, second - first - 1));
    std::string payload(payload_bytes.begin(), payload_bytes.end());
    constexpr auto header = "{\"alg\":\"ES256\",\"kid\":\"miezmerker-issuer-v1\"}";
    auto check = [&](std::string_view h, std::string_view p, bool expected) {
        OfflineAuthSession test_session(org, test_key);
        assert(test_session.begin(nonce, challenge));
        assert(test_session.authorize(sign(h, p, group), proof, now) == expected);
    };
    check(header, payload, true);
    auto replace = [&](const char* name, cJSON* replacement, bool expected) {
        auto claims = cJSON_Parse(payload.c_str()); assert(claims);
        assert(cJSON_ReplaceItemInObjectCaseSensitive(claims, name, replacement));
        char* text = cJSON_PrintUnformatted(claims); assert(text);
        check(header, text, expected); cJSON_free(text); cJSON_Delete(claims);
    };
    replace("role", cJSON_CreateString("ADMIN"), true);
    replace("role", cJSON_CreateString("OWNER"), false);
    replace("scope", cJSON_CreateArray(), false);
    replace("ver", cJSON_CreateNumber(1.5), false);
    replace("kind", cJSON_CreateString("claim"), false);
    replace("dpf", cJSON_CreateString("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"), false);
    replace("exp", cJSON_CreateNumber(now), false);
    replace("iat", cJSON_CreateNumber(now + 1), false);
    check("{\"alg\":\"ES256\",\"kid\":\"other\"}", payload, false);
    check("{\"alg\":\"ES256\",\"kid\":\"miezmerker-issuer-v1\",\"crit\":[\"x\"]}", payload, false);
    check(header, payload.substr(0, payload.size() - 1) + ",\"ver\":1}", false);
    mbedtls_ecp_group_free(&group);
    cJSON_Delete(json);
}
