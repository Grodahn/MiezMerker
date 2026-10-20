#include "miezmerker/offline_auth.hpp"
#include "cJSON.h"
#include "mbedtls/base64.h"
#include "mbedtls/ecdsa.h"
#include "mbedtls/sha256.h"
#include <algorithm>
#include <cassert>
#include <fstream>
#include <filesystem>
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
    ClaimReceiptInfo receipt_info;
    const auto receipt = value(json, "claim_receipt_jwt");
    assert(verify_claim_receipt(receipt, key, now, receipt_info));
    assert(receipt_info.node_id == value(json, "node_id") && receipt_info.organization_id == org);
    assert(receipt_info.organization_name == "Vector Org" && receipt_info.public_key[0] == 4);
    assert(!verify_claim_receipt(receipt, key, 0, receipt_info));
    assert(!verify_claim_receipt(receipt, key, now - 1, receipt_info));
    assert(!verify_claim_receipt(receipt, key, 2106259200, receipt_info));
    assert(!verify_claim_receipt(receipt, bad_key, now, receipt_info));
    assert(!verify_claim_receipt(jwt, key, now, receipt_info));
    assert(!verify_claim_receipt(receipt + ".", key, now, receipt_info));
    // Exact backend-issued UTF-8/escaped JSON at the transport boundary.
    std::ifstream budget_input(std::filesystem::path(argv[1]).parent_path() / "claim-receipt-budget-v1.json");
    std::string budget_text((std::istreambuf_iterator<char>(budget_input)), {});
    auto budget = cJSON_Parse(budget_text.c_str()); assert(budget);
    std::array<unsigned char, 65> budget_key{}; budget_key[0] = 4;
    auto bx = decode(value(budget, "issuer_x")), by = decode(value(budget, "issuer_y"));
    assert(bx.size() == 32 && by.size() == 32);
    std::copy(bx.begin(), bx.end(), budget_key.begin() + 1);
    std::copy(by.begin(), by.end(), budget_key.begin() + 33);
    const auto budget_receipt = value(budget, "receipt");
    assert(budget_receipt.size() >= 4094 && budget_receipt.size() <= kMaxCredentialBytes);
    assert(verify_claim_receipt(budget_receipt, budget_key, now, receipt_info));
    assert(receipt_info.organization_name == value(budget, "name"));
    assert(receipt_info.public_contact == value(budget, "contact"));
    assert(verify_claim_receipt_with_bounds(budget_receipt, budget_key, now * 1000, now * 1000 + 1, receipt_info));
    assert(!verify_claim_receipt_with_bounds(budget_receipt, budget_key, now * 1000 - 1, now * 1000, receipt_info));
    assert(!verify_claim_receipt(budget_receipt, key, now, receipt_info));
    auto tampered_budget = budget_receipt;
    tampered_budget[tampered_budget.find('.') + 10] ^= 1;
    assert(!verify_claim_receipt(tampered_budget, budget_key, now, receipt_info));
    assert(verify_claim_receipt(value(budget, "utf8_receipt"), budget_key, now, receipt_info));
    assert(receipt_info.organization_name.size() == 510 && receipt_info.public_contact.size() == 1000);
    assert(!verify_claim_receipt(budget_receipt + std::string(4097 - budget_receipt.size(), 'x'), budget_key, now, receipt_info));
    cJSON_Delete(budget);
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
    // Legal backend owner text may exceed 512 UTF-8 bytes, while the signed
    // receipt itself still fits the existing 4096-byte transport contract.
    const auto receipt_first = receipt.find('.'), receipt_second = receipt.find('.', receipt_first + 1);
    const auto receipt_bytes = decode(receipt.substr(receipt_first + 1, receipt_second - receipt_first - 1));
    const std::string receipt_payload(receipt_bytes.begin(), receipt_bytes.end());
    auto receipt_claims = cJSON_Parse(receipt_payload.c_str()); assert(receipt_claims);
    const auto test_x = encode(std::span(test_key).subspan(1, 32));
    const auto test_y = encode(std::span(test_key).subspan(33, 32));
    assert(cJSON_ReplaceItemInObjectCaseSensitive(receipt_claims, "ipk_x", cJSON_CreateString(test_x.c_str())));
    assert(cJSON_ReplaceItemInObjectCaseSensitive(receipt_claims, "ipk_y", cJSON_CreateString(test_y.c_str())));
    const std::string long_name(255, 'n');
    std::string long_contact;
    for (unsigned i = 0; i < 300; ++i) long_contact += "\xe7\x8c\xab";
    assert(cJSON_ReplaceItemInObjectCaseSensitive(receipt_claims, "org_name", cJSON_CreateString(long_name.c_str())));
    assert(cJSON_ReplaceItemInObjectCaseSensitive(receipt_claims, "contact", cJSON_CreateString(long_contact.c_str())));
    char* receipt_text = cJSON_PrintUnformatted(receipt_claims); assert(receipt_text);
    const auto long_receipt = sign(header, receipt_text, group);
    assert(long_receipt.size() <= 4096);
    assert(verify_claim_receipt(long_receipt, test_key, now, receipt_info));
    assert(receipt_info.organization_name == long_name && receipt_info.public_contact == long_contact);
    // Add JSON whitespace before signing to reach the maximum representable
    // compact length (4095). One more decoded byte yields 4097, above 4096.
    std::string padded = receipt_text;
    assert(padded.size() <= 2961);
    padded.append(2961 - padded.size(), ' ');
    auto edge = sign(header, padded, group);
    assert(edge.size() == 4095);
    assert(verify_claim_receipt(edge, test_key, now, receipt_info));
    padded += ' ';
    auto over = sign(header, padded, group);
    assert(over.size() == 4097);
    assert(!verify_claim_receipt(over, test_key, now, receipt_info));
    assert(!verify_claim_receipt_with_bounds(over, test_key, now * 1000, now * 1000, receipt_info));
    cJSON_free(receipt_text);
    cJSON_AddNumberToObject(receipt_claims, "bowl_number", 4711);
    cJSON_AddStringToObject(receipt_claims, "display_name", "der gelbe");
    receipt_text = cJSON_PrintUnformatted(receipt_claims); assert(receipt_text);
    const auto numbered_receipt = sign(header, receipt_text, group);
    assert(verify_claim_receipt(numbered_receipt, test_key, now, receipt_info));
    assert(receipt_info.bowl_number == 4711 && receipt_info.display_name == "der gelbe");
    assert(verify_claim_receipt_with_bounds(numbered_receipt, test_key, now * 1000, now * 1000, receipt_info));
    assert(receipt_info.bowl_number == 4711 && receipt_info.display_name == "der gelbe");
    cJSON_free(receipt_text);
    assert(cJSON_ReplaceItemInObjectCaseSensitive(receipt_claims, "bowl_number", cJSON_CreateNumber(2147483648.0)));
    receipt_text = cJSON_PrintUnformatted(receipt_claims); assert(receipt_text);
    assert(!verify_claim_receipt(sign(header, receipt_text, group), test_key, now, receipt_info));
    assert(!verify_claim_receipt_with_bounds(sign(header, receipt_text, group), test_key, now * 1000, now * 1000, receipt_info));
    cJSON_free(receipt_text); cJSON_Delete(receipt_claims);
    mbedtls_ecp_group_free(&group);
    cJSON_Delete(json);
}
