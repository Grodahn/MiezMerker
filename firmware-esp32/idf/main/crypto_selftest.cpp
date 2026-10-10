// Explicit dev build only. Separate verifier instances and fixed fixture time;
// never grants authority to the BLE/Core session or changes the BoardClock.
#include "board.hpp"
#include "auth_vectors.hpp"
#include "mbedtls/base64.h"
#include "psa/crypto.h"
#include <algorithm>

namespace miezmerker::esp32 {
namespace {
bool decode(std::string text, std::span<unsigned char> output) {
    for (auto& c : text) { if (c == '-') c = '+'; if (c == '_') c = '/'; }
    while (text.size() % 4) text += '=';
    std::size_t length = 0;
    return mbedtls_base64_decode(output.data(), output.size(), &length,
        reinterpret_cast<const unsigned char*>(text.data()), text.size()) == 0 && length == output.size();
}
bool fixture_nonce(std::span<unsigned char> output) {
    for (unsigned i = 0; i < output.size(); ++i) output[i] = static_cast<unsigned char>(i);
    return true;
}
}
bool crypto_selftest(BoardCrypto& crypto, const NodeIdentity& identity) {
    std::array<unsigned char, 65> issuer{}; issuer[0] = 4;
    std::array<unsigned char, 64> proof{};
    if (!decode(vectors::issuer_x, std::span(issuer).subspan(1, 32))
        || !decode(vectors::issuer_y, std::span(issuer).subspan(33, 32)) || !decode(vectors::proof, proof)) return false;
    std::array<unsigned char, 32> nonce{};
    constexpr std::int64_t now = 1790899200;
    OfflineAuthSession session(vectors::organization, issuer);
    if (!session.begin(fixture_nonce, nonce) || !session.authorize(vectors::credential, proof, now)
        || !session.can_sync(now) || session.can_sync(0) || session.can_sync(1917129600)
        || session.authorize(vectors::credential, proof, now)) return false;
    if (!session.begin(fixture_nonce, nonce) || session.authorize(vectors::credential, proof, 0)) return false;
    if (!session.begin(fixture_nonce, nonce) || session.authorize(vectors::credential, proof, now - 1)) return false;
    proof[0] ^= 1;
    if (!session.begin(fixture_nonce, nonce) || session.authorize(vectors::credential, proof, now)) return false;
    proof[0] ^= 1;
    if (session.authorize(vectors::credential, proof, now)) return false;
    OfflineAuthSession foreign("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", issuer);
    if (!foreign.begin(fixture_nonce, nonce) || foreign.authorize(vectors::credential, proof, now)) return false;
    ClaimReceiptInfo claim;
    if (!verify_claim_receipt(vectors::receipt, issuer, now, claim)
        || verify_claim_receipt(vectors::receipt, issuer, 0, claim)) return false;
    issuer[5] ^= 1;
    if (verify_claim_receipt(vectors::receipt, issuer, now, claim)) return false;
    // Exercise real persisted device scalar, PSA signing and public verification.
    const std::string message = "MM-LOCAL-SELFTEST-v1";
    std::array<std::byte, 64> signature{};
    if (!crypto.keypair_valid(identity) || !crypto.sign(identity.private_key,
        std::as_bytes(std::span(message)), signature)) return false;
    psa_key_attributes_t attributes = PSA_KEY_ATTRIBUTES_INIT;
    psa_set_key_type(&attributes, PSA_KEY_TYPE_ECC_PUBLIC_KEY(PSA_ECC_FAMILY_SECP_R1));
    psa_set_key_bits(&attributes, 256); psa_set_key_usage_flags(&attributes, PSA_KEY_USAGE_VERIFY_MESSAGE);
    psa_set_key_algorithm(&attributes, PSA_ALG_ECDSA(PSA_ALG_SHA_256));
    mbedtls_svc_key_id_t key = 0;
    const auto imported = psa_import_key(&attributes, reinterpret_cast<const unsigned char*>(identity.public_key.data()), 65, &key);
    psa_reset_key_attributes(&attributes);
    if (imported != PSA_SUCCESS) return false;
    const auto verified = psa_verify_message(key, PSA_ALG_ECDSA(PSA_ALG_SHA_256),
        reinterpret_cast<const unsigned char*>(message.data()), message.size(),
        reinterpret_cast<const unsigned char*>(signature.data()), signature.size());
    psa_destroy_key(key);
    return verified == PSA_SUCCESS;
}
} // namespace miezmerker::esp32
