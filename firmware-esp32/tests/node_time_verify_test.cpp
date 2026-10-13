// Cryptographic verification of backend time proofs (#130, #129 contract).
//
// Host verification with mbed-TLS/cJSON: valid golden JWS, all negative
// fixtures from protocol/fixtures/node-time-v1.json, plus canonical
// challenge/acceptance signing bytes. Binding to a pending challenge and
// lifecycle are covered by the bootstrap manager tests.
#include <array>
#include <cassert>
#include <fstream>
#include <iostream>
#include <iterator>
#include <string>
#include <vector>

#include "cJSON.h"
#include "mbedtls/base64.h"
#include "mbedtls/ecdsa.h"
#include "mbedtls/sha256.h"
#include "miezmerker/node_time.hpp"
#include "miezmerker/node_time_verify.hpp"

using namespace miezmerker;
using namespace miezmerker::node_time;

namespace {
std::string file_text(const char* path) {
    std::ifstream input(path);
    assert(input);
    return std::string((std::istreambuf_iterator<char>(input)), {});
}
std::string json_string(cJSON* object, const char* name) {
    auto* item = cJSON_GetObjectItemCaseSensitive(object, name);
    assert(cJSON_IsString(item) && item->valuestring);
    return item->valuestring;
}
std::string hex_of(const std::vector<std::uint8_t>& bytes) {
    static constexpr char kHex[] = "0123456789abcdef";
    std::string out;
    for (auto b : bytes) {
        out += kHex[b >> 4];
        out += kHex[b & 15];
    }
    return out;
}
std::vector<std::uint8_t> decode_b64u(const std::string& text) {
    std::string padded = text;
    for (auto& c : padded) {
        if (c == '-') c = '+';
        if (c == '_') c = '/';
    }
    while (padded.size() % 4) padded += '=';
    std::vector<unsigned char> out(padded.size());
    std::size_t length = 0;
    assert(mbedtls_base64_decode(out.data(), out.size(), &length,
                                  reinterpret_cast<const unsigned char*>(padded.data()),
                                  padded.size()) == 0);
    out.resize(length);
    return std::vector<std::uint8_t>(out.begin(), out.end());
}
bool ecdsa_verify_64(const std::array<std::uint8_t, 65>& pubkey,
                     const std::vector<std::uint8_t>& message,
                     const std::vector<std::uint8_t>& sig64) {
    assert(pubkey[0] == 4 && sig64.size() == 64);
    unsigned char hash[32];
    assert(mbedtls_sha256(message.data(), message.size(), hash, 0) == 0);
    mbedtls_ecp_group group;
    mbedtls_ecp_group_init(&group);
    mbedtls_ecp_point point;
    mbedtls_ecp_point_init(&point);
    mbedtls_mpi r, s;
    mbedtls_mpi_init(&r);
    mbedtls_mpi_init(&s);
    bool valid = mbedtls_ecp_group_load(&group, MBEDTLS_ECP_DP_SECP256R1) == 0 &&
                 mbedtls_ecp_point_read_binary(&group, &point, pubkey.data(), 65) == 0 &&
                 mbedtls_ecp_check_pubkey(&group, &point) == 0 &&
                 mbedtls_mpi_read_binary(&r, sig64.data(), 32) == 0 &&
                 mbedtls_mpi_read_binary(&s, sig64.data() + 32, 32) == 0 &&
                 mbedtls_ecdsa_verify(&group, hash, sizeof(hash), &point, &r, &s) == 0;
    mbedtls_mpi_free(&s);
    mbedtls_mpi_free(&r);
    mbedtls_ecp_point_free(&point);
    mbedtls_ecp_group_free(&group);
    return valid;
}
}  // namespace

int main(int argc, char** argv) {
    assert(argc == 2);
    const std::string fixture = file_text(argv[1]);
    cJSON* json = cJSON_Parse(fixture.c_str());
    assert(json);
    auto* issuer = cJSON_GetObjectItemCaseSensitive(json, "issuerPublicKey");
    const std::string issuer_x = json_string(issuer, "x");
    const std::string issuer_y = json_string(issuer, "y");
    assert(json_string(issuer, "kid") == "miezmerker-issuer-v1");
    std::array<unsigned char, 65> issuer_key{};
    issuer_key[0] = 4;
    {
        const auto x = decode_b64u(issuer_x), y = decode_b64u(issuer_y);
        assert(x.size() == 32 && y.size() == 32);
        std::copy(x.begin(), x.end(), issuer_key.begin() + 1);
        std::copy(y.begin(), y.end(), issuer_key.begin() + 33);
    }
    auto* challenge = cJSON_GetObjectItemCaseSensitive(json, "challenge");
    const std::string node_id = json_string(challenge, "nodeId");
    const std::string incarnation = json_string(challenge, "incarnation");
    const int boot = cJSON_GetObjectItemCaseSensitive(challenge, "bootCounter")->valueint;
    const std::string nonce_b64 = json_string(challenge, "challengeNonce");
    const std::string pubkey_b64 = json_string(challenge, "devicePublicKey");
    const std::string fp_b64 = json_string(challenge, "devicePublicKeyFingerprint");
    const std::string sig_b64 = json_string(challenge, "deviceChallengeSignature");
    const std::string challenge_hex = json_string(json, "challengeSigningHex");
    const std::string proof = json_string(json, "proof");
    auto* acceptance = cJSON_GetObjectItemCaseSensitive(json, "acceptance");
    const std::string acceptance_hex = json_string(json, "acceptanceSigningHex");
    const long long expected_utc =
        static_cast<long long>(cJSON_GetObjectItemCaseSensitive(json, "expectedUtcMs")->valuedouble);
    assert(expected_utc == 1791748800123LL);

    // Valid proof verifies and yields the expected claims.
    TimeProofClaims claims;
    assert(verify_time_proof(proof, std::span<const unsigned char, 65>(issuer_key), claims));
    assert(claims.node_id == node_id && claims.incarnation == incarnation);
    assert(claims.boot == 42 && boot == 42);
    assert(claims.nonce_b64u == nonce_b64 && claims.dpf_b64u == fp_b64);
    assert(claims.utc_ms == expected_utc && claims.version == 1);
    assert(claims.jti == "77777777-7777-4777-8777-777777777777");

    // Challenge canonical bytes match the normative hex and the device
    // signature verifies with the uncompressed P-256 key.
    NodeTimeChallenge rebuilt;
    {
        auto node = NodeId::parse(node_id);
        auto incarn = IncarnationId::parse(incarnation);
        assert(node && incarn);
        rebuilt.node_id = *node;
        rebuilt.incarnation = *incarn;
        rebuilt.boot_counter = 42;
        const auto nonce = decode_b64u(nonce_b64);
        const auto pubkey = decode_b64u(pubkey_b64);
        const auto fp = decode_b64u(fp_b64);
        const auto sig = decode_b64u(sig_b64);
        assert(nonce.size() == 32 && pubkey.size() == 65 && fp.size() == 32 && sig.size() == 64);
        std::copy(nonce.begin(), nonce.end(), rebuilt.nonce.begin());
        std::copy(pubkey.begin(), pubkey.end(), rebuilt.device_public_key.begin());
        std::copy(fp.begin(), fp.end(), rebuilt.fingerprint.begin());
        std::copy(sig.begin(), sig.end(), rebuilt.signature.begin());
        rebuilt.challenge_uptime_ms = 1000;
        rebuilt.version = 1;
    }
    const auto signing = challenge_signing_bytes(rebuilt);
    assert(hex_of(signing) == challenge_hex);
    {
        const auto sig = decode_b64u(sig_b64);
        std::array<std::uint8_t, 65> pubkey{};
        const auto pub = decode_b64u(pubkey_b64);
        std::copy(pub.begin(), pub.end(), pubkey.begin());
        assert(ecdsa_verify_64(pubkey, signing, sig));
        // Fingerprint binds to the same point bytes.
        const auto digest = sha256(
            std::span<const std::uint8_t>(rebuilt.device_public_key.data(), 65));
        std::vector<std::uint8_t> fp(digest.begin(), digest.end());
        const auto expected_fp = decode_b64u(fp_b64);
        assert(fp == expected_fp);
    }

    // Acceptance canonical bytes match and the device signature verifies.
    {
        const std::string proof_digest_b64 = json_string(acceptance, "proofDigest");
        const std::string nonce_digest_b64 = json_string(acceptance, "challengeNonceDigest");
        const auto anchor_id = NodeId::parse(json_string(acceptance, "anchorId"));
        assert(anchor_id);
        const auto nonce = decode_b64u(nonce_b64);
        std::array<std::uint8_t, 32> nonce_digest = sha256(
            std::span<const std::uint8_t>(nonce.data(), nonce.size()));
        std::vector<std::uint8_t> proof_bytes(proof.begin(), proof.end());
        std::array<std::uint8_t, 32> proof_digest = sha256(
            std::span<const std::uint8_t>(proof_bytes.data(), proof_bytes.size()));
        std::vector<std::uint8_t> expected_nonce = decode_b64u(nonce_digest_b64);
        std::vector<std::uint8_t> expected_proof = decode_b64u(proof_digest_b64);
        assert(std::vector<std::uint8_t>(nonce_digest.begin(), nonce_digest.end()) ==
               expected_nonce);
        assert(std::vector<std::uint8_t>(proof_digest.begin(), proof_digest.end()) ==
               expected_proof);
        std::span<const std::uint8_t, 32> nonce_span(nonce_digest.data(), 32);
        std::span<const std::uint8_t, 32> proof_span(proof_digest.data(), 32);
        const auto accept_bytes = acceptance_signing_bytes(
            rebuilt.node_id, rebuilt.incarnation, 42, nonce_span, proof_span, 1300,
            *anchor_id);
        assert(hex_of(accept_bytes) == acceptance_hex);
        const auto sig = decode_b64u(json_string(acceptance, "signature"));
        std::array<std::uint8_t, 65> pubkey{};
        const auto pub = decode_b64u(pubkey_b64);
        std::copy(pub.begin(), pub.end(), pubkey.begin());
        assert(ecdsa_verify_64(pubkey, accept_bytes, sig));
    }

    // All signed negative variants fail verification.
    auto* negatives = cJSON_GetObjectItemCaseSensitive(json, "negativeProofs");
    assert(cJSON_IsObject(negatives));
    // Signed negatives split by layer: format/signature failures must fail
    // here; binding failures (wrong node/incarnation/boot/nonce) are
    // correctly signed and pass JWS verification but must fail the later
    // pending-challenge binding in the bootstrap manager.
    int negative_count = 0;
    int binding_pass_count = 0;
    for (auto* entry = negatives->child; entry; entry = entry->next) {
        assert(cJSON_IsString(entry) && entry->valuestring);
        const std::string name = entry->string ? entry->string : "";
        TimeProofClaims ignored;
        const bool ok = verify_time_proof(entry->valuestring,
                                          std::span<const unsigned char, 65>(issuer_key),
                                          ignored);
        const bool is_binding_case =
            (name == "wrong_node" || name == "wrong_incarnation" || name == "wrong_boot" ||
             name == "wrong_nonce");
        if (is_binding_case) {
            if (!ok) std::cerr << "UNEXPECTED FAIL (binding case should pass JWS): " << name << "\n";
            assert(ok);
            // Binding itself must mismatch the fixture challenge.
            assert(ignored.node_id != node_id || ignored.incarnation != incarnation ||
                   ignored.boot != 42 || ignored.nonce_b64u != nonce_b64);
            ++binding_pass_count;
        } else {
            if (ok) std::cerr << "UNEXPECTED PASS: " << name << "\n";
            assert(!ok);
        }
        ++negative_count;
    }
    // Fixture carries wrong_iss/kind/kid/node/incarnation/boot/nonce/ver,
    // overflow boot/version/utc, duplicate version, wrong header kid,
    // wrong algorithm and tampered signature.
    assert(negative_count >= 15 && binding_pass_count == 4);

    // Wrong independently pinned issuer key fails (signature mismatch).
    {
        auto wrong = issuer_key;
        wrong[5] ^= 1;
        TimeProofClaims ignored;
        assert(!verify_time_proof(proof, std::span<const unsigned char, 65>(wrong), ignored));
    }
    // Missing pin fails closed.
    {
        std::array<unsigned char, 65> missing{};
        TimeProofClaims ignored;
        assert(!verify_time_proof(proof, std::span<const unsigned char, 65>(missing), ignored));
    }
    // Modified UTC without re-signing fails (signature covers payload).
    {
        const auto first = proof.find('.');
        const auto second = proof.find('.', first + 1);
        std::string payload_b64 = proof.substr(first + 1, second - first - 1);
        auto payload_bytes = decode_b64u(payload_b64);
        std::string payload(payload_bytes.begin(), payload_bytes.end());
        const std::string needle = "1791748800123";
        const auto pos = payload.find(needle);
        assert(pos != std::string::npos);
        payload.replace(pos, needle.size(), "1791748800124");
        // Re-encode without re-signing: signature must fail.
        std::string tampered_payload_b64;
        {
            std::vector<unsigned char> raw(payload.begin(), payload.end());
            std::size_t out_len = 4 * ((raw.size() + 2) / 3) + 1;
            std::vector<unsigned char> encoded(out_len);
            std::size_t length = 0;
            assert(mbedtls_base64_encode(encoded.data(), encoded.size(), &length, raw.data(),
                                          raw.size()) == 0);
            encoded.resize(length);
            tampered_payload_b64.assign(encoded.begin(), encoded.end());
            for (auto& c : tampered_payload_b64) {
                if (c == '+') c = '-';
                if (c == '/') c = '_';
            }
            while (!tampered_payload_b64.empty() && tampered_payload_b64.back() == '=')
                tampered_payload_b64.pop_back();
        }
        const std::string tampered =
            proof.substr(0, first + 1) + tampered_payload_b64 + proof.substr(second);
        TimeProofClaims ignored;
        assert(!verify_time_proof(tampered, std::span<const unsigned char, 65>(issuer_key),
                                  ignored));
    }
    // Corrupted signature, oversized input and malformed structure fail.
    {
        TimeProofClaims ignored;
        std::string corrupted = proof;
        corrupted.back() = (corrupted.back() == 'A' ? 'B' : 'A');
        assert(!verify_time_proof(corrupted, std::span<const unsigned char, 65>(issuer_key),
                                  ignored));
        assert(!verify_time_proof(std::string(3000, 'A'),
                                  std::span<const unsigned char, 65>(issuer_key), ignored));
        assert(!verify_time_proof("malformed",
                                  std::span<const unsigned char, 65>(issuer_key), ignored));
        assert(!verify_time_proof(proof + ".",
                                  std::span<const unsigned char, 65>(issuer_key), ignored));
        assert(!verify_time_proof("", std::span<const unsigned char, 65>(issuer_key), ignored));
    }

    cJSON_Delete(json);
    std::cout << "node-time JWS verification passed (" << negative_count << " negatives)\n";
    return 0;
}
