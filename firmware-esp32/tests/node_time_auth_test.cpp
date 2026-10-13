// Strict interval authorization for offline credentials and claim receipts.
// Proves L >= iat && U < exp with real JWS fixtures; no midpoint fallback,
// no grace period, revalidated per operation. Bootstrap alone grants neither
// node:sync nor claiming.
#include <array>
#include <cassert>
#include <fstream>
#include <iostream>
#include <iterator>
#include <span>
#include <string>
#include <vector>

#include "cJSON.h"
#include "mbedtls/base64.h"
#include "miezmerker/node_time.hpp"
#include "miezmerker/offline_auth.hpp"

using namespace miezmerker;

namespace {
std::string read_file(const char* path) {
    std::ifstream input(path);
    assert(input);
    return std::string((std::istreambuf_iterator<char>(input)), {});
}
std::string field(cJSON* object, const char* name) {
    auto* item = cJSON_GetObjectItemCaseSensitive(object, name);
    assert(cJSON_IsString(item) && item->valuestring);
    return item->valuestring;
}
std::vector<unsigned char> decode_b64u(const std::string& text) {
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
    return out;
}
bool fill_nonce(std::span<unsigned char> out) {
    for (std::size_t i = 0; i < out.size(); ++i) out[i] = static_cast<unsigned char>(i);
    return true;
}
}  // namespace

int main(int argc, char** argv) {
    assert(argc == 2);
    const std::string text = read_file(argv[1]);
    cJSON* json = cJSON_Parse(text.c_str());
    assert(json);
    auto* issuer_json = cJSON_GetObjectItemCaseSensitive(json, "issuer_public_key");
    const auto x = decode_b64u(field(issuer_json, "x"));
    const auto y = decode_b64u(field(issuer_json, "y"));
    assert(x.size() == 32 && y.size() == 32);
    std::array<unsigned char, 65> issuer{};
    issuer[0] = 4;
    std::copy(x.begin(), x.end(), issuer.begin() + 1);
    std::copy(y.begin(), y.end(), issuer.begin() + 33);
    const std::string org = field(json, "organization_id");
    const std::string credential = field(json, "credential_jwt");
    const std::string receipt = field(json, "claim_receipt_jwt");
    const auto proof_bytes = decode_b64u(field(json, "challenge_signature_b64u"));
    assert(proof_bytes.size() == 64);
    std::array<unsigned char, 64> proof{};
    std::copy(proof_bytes.begin(), proof_bytes.end(), proof.begin());

    // Security-clock interval from the time bootstrap (TV5-style): S is the
    // backend issuance, E=1000ms, D=300ms, sampling=2ms. Credential iat
    // 1790899200 / exp 1917129600 wholly contains it; claim receipt
    // iat 1790899200 / exp 2106259200 does as well.
    node_time::AcceptanceInterval interval;
    assert(node_time::compute_acceptance_interval(1791748800123LL, 1000, 1000, 1300, 2,
                                                  interval));
    const std::int64_t lower = interval.lower_ms;  // 1791748799121
    const std::int64_t upper = interval.upper_ms;  // 1791748801425
    assert(lower == 1791748800123LL - 1002 && upper == 1791748800123LL + 1302);

    // Valid credential + PoP authorizes under the full interval.
    {
        OfflineAuthSession session(org, issuer);
        std::array<unsigned char, 32> challenge{};
        assert(session.begin(fill_nonce, challenge));
        assert(session.authorize_with_bounds(
            credential, std::span<const unsigned char>(proof.data(), 64), lower, upper));
        assert(session.can_sync_with_bounds(lower, upper));
        // Rechecked during a long connection: expiry inside the new bounds denies.
        assert(!session.can_sync_with_bounds(lower, 1917129600000LL));
        assert(!session.can_sync_with_bounds(1790899199000LL, upper));
        assert(!session.can_sync_with_bounds(1917129600000LL, 1917129600000LL));
        session.disconnect();
        assert(!session.can_sync_with_bounds(lower, upper));
    }
    // Expiry/iat overlapping the uncertainty interval denies (no grace).
    {
        OfflineAuthSession session(org, issuer);
        std::array<unsigned char, 32> challenge{};
        assert(session.begin(fill_nonce, challenge));
        // Upper exactly at exp_ms is not strictly inside (U < exp required).
        assert(!session.authorize_with_bounds(
            credential, std::span<const unsigned char>(proof.data(), 64), lower,
            1917129600000LL));
        assert(session.begin(fill_nonce, challenge));
        assert(!session.authorize_with_bounds(
            credential, std::span<const unsigned char>(proof.data(), 64),
            1790899199000LL, upper));
        // Unknown bounds fail closed.
        assert(session.begin(fill_nonce, challenge));
        assert(!session.authorize_with_bounds(
            credential, std::span<const unsigned char>(proof.data(), 64), 0, 0));
    }
    // Existing invalid signature/scope/org/PoP behavior remains rejected.
    {
        OfflineAuthSession session(org, issuer);
        std::array<unsigned char, 32> challenge{};
        auto bad_proof = proof;
        bad_proof[0] ^= 1;
        assert(session.begin(fill_nonce, challenge));
        assert(!session.authorize_with_bounds(
            credential, std::span<const unsigned char>(bad_proof.data(), 64), lower,
            upper));
        OfflineAuthSession foreign("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", issuer);
        assert(foreign.begin(fill_nonce, challenge));
        assert(!foreign.authorize_with_bounds(
            credential, std::span<const unsigned char>(proof.data(), 64), lower, upper));
        assert(session.begin(fill_nonce, challenge));
        assert(!session.authorize_with_bounds(
            credential + ".", std::span<const unsigned char>(proof.data(), 64), lower,
            upper));
        // Bootstrap alone grants no sync without a credential.
        OfflineAuthSession empty(org, issuer);
        assert(!empty.can_sync_with_bounds(lower, upper));
    }
    // Claim receipts use the same strict interval guard.
    {
        ClaimReceiptInfo info;
        assert(verify_claim_receipt_with_bounds(
            receipt, std::span<const unsigned char, 65>(issuer), lower, upper, info));
        assert(info.organization_id == org);
        assert(!verify_claim_receipt_with_bounds(
            receipt, std::span<const unsigned char, 65>(issuer), lower, 2106259200000LL,
            info));
        assert(!verify_claim_receipt_with_bounds(
            receipt, std::span<const unsigned char, 65>(issuer), 0, 0, info));
        auto wrong = issuer;
        wrong[5] ^= 1;
        assert(!verify_claim_receipt_with_bounds(
            receipt, std::span<const unsigned char, 65>(wrong), lower, upper, info));
    }

    cJSON_Delete(json);
    std::cout << "node-time interval authorization passed\n";
    return 0;
}
