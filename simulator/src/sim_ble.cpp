#include "miezmerker/sim/sim_ble.hpp"

#include <algorithm>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <sstream>

#include "cJSON.h"
#include "mbedtls/ecdsa.h"
#include "mbedtls/sha256.h"

namespace miezmerker::sim {

namespace {

using Bytes = std::vector<unsigned char>;

bool hex_to_bytes(const std::string& hex, Bytes& out) {
    if (hex.size() % 2 != 0) return false;
    out.clear();
    out.reserve(hex.size() / 2);
    auto v = [](char c) -> int {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        return -1;
    };
    for (std::size_t i = 0; i < hex.size(); i += 2) {
        int hi = v(hex[i]);
        int lo = v(hex[i + 1]);
        if (hi < 0 || lo < 0) return false;
        out.push_back(static_cast<unsigned char>((hi << 4) | lo));
    }
    return true;
}

// Strict unpadded base64url (same rules as OfflineAuthSession).
bool b64u_decode(const std::string& value, Bytes& out) {
    out.clear();
    if (value.empty() || value.size() % 4 == 1) return false;
    unsigned accumulator = 0;
    unsigned bits = 0;
    for (char c : value) {
        int n = c >= 'A' && c <= 'Z' ? c - 'A'
              : c >= 'a' && c <= 'z' ? c - 'a' + 26
              : c >= '0' && c <= '9' ? c - '0' + 52
              : c == '-'             ? 62
              : c == '_'             ? 63
                                     : -1;
        if (n < 0) return false;
        accumulator = (accumulator << 6) | static_cast<unsigned>(n);
        bits += 6;
        if (bits >= 8) {
            bits -= 8;
            out.push_back(static_cast<unsigned char>(accumulator >> bits));
        }
    }
    return (accumulator & ((1U << bits) - 1)) == 0;
}

std::string b64u_encode(const unsigned char* data, std::size_t len) {
    static const char* alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
    std::string out;
    unsigned bits = 0;
    unsigned value = 0;
    for (std::size_t i = 0; i < len; ++i) {
        value = (value << 8) | data[i];
        bits += 8;
        while (bits >= 6) {
            bits -= 6;
            out += alphabet[(value >> bits) & 63];
        }
    }
    if (bits) out += alphabet[(value << (6 - bits)) & 63];
    return out;
}

bool ecdsa_verify(const std::array<unsigned char, 65>& key,
                  const unsigned char* message, std::size_t message_len,
                  const unsigned char* signature, std::size_t sig_len) {
    if (sig_len != 64 || key[0] != 4) return false;
    unsigned char hash[32];
    if (mbedtls_sha256(message, message_len, hash, 0) != 0) return false;
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
                 mbedtls_mpi_read_binary(&r, signature, 32) == 0 &&
                 mbedtls_mpi_read_binary(&s, signature + 32, 32) == 0 &&
                 mbedtls_ecdsa_verify(&group, hash, sizeof(hash), &point, &r, &s) == 0;
    mbedtls_mpi_free(&s);
    mbedtls_mpi_free(&r);
    mbedtls_ecp_point_free(&point);
    mbedtls_ecp_group_free(&group);
    return valid;
}

using JsonPtr = std::unique_ptr<cJSON, decltype(&cJSON_Delete)>;

JsonPtr parse_object(const Bytes& bytes) {
    std::string text(bytes.begin(), bytes.end());
    if (text.find('\0') != std::string::npos || text.find("\\u0000") != std::string::npos)
        return JsonPtr(nullptr, cJSON_Delete);
    JsonPtr result(
        cJSON_ParseWithLengthOpts(text.c_str(), text.size() + 1, nullptr, true), cJSON_Delete);
    if (!cJSON_IsObject(result.get())) return JsonPtr(nullptr, cJSON_Delete);
    for (auto* a = result->child; a; a = a->next)
        for (auto* b = a->next; b; b = b->next)
            if (std::strcmp(a->string, b->string) == 0) return JsonPtr(nullptr, cJSON_Delete);
    return result;
}

std::string json_string(const cJSON* obj, const char* name) {
    auto* v = cJSON_GetObjectItemCaseSensitive(obj, name);
    return cJSON_IsString(v) ? std::string(v->valuestring) : std::string{};
}

bool uuid_valid(const std::string& v) {
    if (v.size() != 36) return false;
    for (std::size_t i = 0; i < v.size(); ++i) {
        if (i == 8 || i == 13 || i == 18 || i == 23) {
            if (v[i] != '-') return false;
        } else if (!((v[i] >= '0' && v[i] <= '9') || (v[i] >= 'a' && v[i] <= 'f'))) {
            return false;
        }
    }
    return true;
}

bool uuid_to_bytes(const std::string& uuid, std::array<std::byte, 16>& out) {
    if (!uuid_valid(uuid)) return false;
    std::string hex;
    for (char c : uuid)
        if (c != '-') hex += c;
    Bytes raw;
    if (!hex_to_bytes(hex, raw) || raw.size() != 16) return false;
    for (std::size_t i = 0; i < 16; ++i) out[i] = static_cast<std::byte>(raw[i]);
    return true;
}

bool key_from_b64u(const std::string& x, const std::string& y,
                   std::array<unsigned char, 65>& out) {
    Bytes xb, yb;
    if (!b64u_decode(x, xb) || xb.size() != 32) return false;
    if (!b64u_decode(y, yb) || yb.size() != 32) return false;
    out[0] = 4;
    std::copy(xb.begin(), xb.end(), out.begin() + 1);
    std::copy(yb.begin(), yb.end(), out.begin() + 33);
    return true;
}

std::string read_file(const std::string& path, bool& ok) {
    std::ifstream in(path, std::ios::binary);
    if (!in) {
        ok = false;
        return {};
    }
    std::string text((std::istreambuf_iterator<char>(in)), std::istreambuf_iterator<char>());
    ok = true;
    return text;
}

std::string fixture_path(const std::string& name) {
#ifdef MIEZMERKER_FIXTURES_DIR
    return std::string(MIEZMERKER_FIXTURES_DIR) + "/" + name;
#else
    return std::string("protocol/fixtures/") + name;
#endif
}

bool get_string(const cJSON* obj, const char* name, std::string& out) {
    auto* v = cJSON_GetObjectItemCaseSensitive(obj, name);
    if (!cJSON_IsString(v)) return false;
    out = v->valuestring;
    return true;
}

bool get_nested_string(const cJSON* root, const char* parent, const char* name,
                       std::string& out) {
    auto* p = cJSON_GetObjectItemCaseSensitive(root, parent);
    if (!cJSON_IsObject(p)) return false;
    return get_string(p, name, out);
}

}  // namespace

void SimOwnerStore::clear() {
    claimed = false;
    organization_id.clear();
    organization_slug.clear();
    organization_name.clear();
    public_contact.clear();
    issuer_key.fill(0);
    has_issuer = false;
}

void SimNodeKeys::clear() {
    provisioned = false;
    node_id.clear();
    public_key.fill(0);
    key_x_b64u.clear();
    key_y_b64u.clear();
}

SimClaimCrypto::SimClaimCrypto(const std::array<unsigned char, 65>& issuer_key)
    : issuer_key_(issuer_key) {}

void SimClaimCrypto::set_issuer(const std::array<unsigned char, 65>& issuer_key) {
    issuer_key_ = issuer_key;
}

bool SimClaimCrypto::generate_keypair(std::span<std::byte, 65>,
                                      std::span<std::byte, 32>) {
    // Host never generates device keys; provisioning is covered by
    // firmware-core node_identity_test. Simulator imports fixture keys.
    return false;
}

bool SimClaimCrypto::sign(std::span<const std::byte, 32>, std::span<const std::byte>,
                          std::span<std::byte, 64>) {
    // Host never signs claim advertisements; fixtures carry precomputed
    // signatures. Node session proofs replay fixture signatures for the fixed
    // nonce; dynamic signing stays a board/PWA concern (#4/#8).
    return false;
}

bool SimClaimCrypto::verify_claim(const std::string& receipt, VerifiedClaim& claim) {
    claim = VerifiedClaim{};
    if (receipt.empty() || receipt.size() > 4096) return false;
    auto first = receipt.find('.');
    auto second = receipt.find('.', first == std::string::npos ? 0 : first + 1);
    if (first == std::string::npos || second == std::string::npos ||
        receipt.find('.', second + 1) != std::string::npos)
        return false;
    Bytes hb, pb, sig;
    if (!b64u_decode(receipt.substr(0, first), hb)) return false;
    if (!b64u_decode(receipt.substr(first + 1, second - first - 1), pb)) return false;
    if (!b64u_decode(receipt.substr(second + 1), sig)) return false;
    if (sig.size() != 64) return false;
    auto header = parse_object(hb);
    if (!header || json_string(header.get(), "alg") != "ES256" ||
        json_string(header.get(), "kid") != "miezmerker-issuer-v1" ||
        cJSON_GetObjectItemCaseSensitive(header.get(), "crit") ||
        cJSON_GetObjectItemCaseSensitive(header.get(), "b64"))
        return false;
    // Verify with the independently pinned issuer key (never ipk_*).
    std::string signed_text = receipt.substr(0, second);
    if (!ecdsa_verify(issuer_key_,
                      reinterpret_cast<const unsigned char*>(signed_text.data()),
                      signed_text.size(), sig.data(), sig.size()))
        return false;
    auto claims = parse_object(pb);
    if (!claims) return false;
    auto* c = claims.get();
    auto kind = json_string(c, "kind");
    auto iss = json_string(c, "iss");
    auto sub = json_string(c, "sub");
    auto org = json_string(c, "org");
    auto ndx = json_string(c, "ndpk_x");
    auto ndy = json_string(c, "ndpk_y");
    auto ndp = json_string(c, "ndpf");
    if (iss != "miezmerker" || kind != "claim") return false;
    auto* ver = cJSON_GetObjectItemCaseSensitive(c, "ver");
    if (!cJSON_IsNumber(ver) || ver->valuedouble != 1) return false;
    if (!uuid_valid(sub) || !uuid_valid(org) || org.empty()) return false;
    Bytes xb, yb, fp;
    if (!b64u_decode(ndx, xb) || xb.size() != 32) return false;
    if (!b64u_decode(ndy, yb) || yb.size() != 32) return false;
    if (!b64u_decode(ndp, fp) || fp.size() != 32) return false;
    // Fingerprint must match the embedded node key.
    unsigned char uncompressed[65];
    uncompressed[0] = 0x04;
    std::copy(xb.begin(), xb.end(), uncompressed + 1);
    std::copy(yb.begin(), yb.end(), uncompressed + 33);
    unsigned char digest[32];
    if (mbedtls_sha256(uncompressed, sizeof(uncompressed), digest, 0) != 0) return false;
    if (!std::equal(fp.begin(), fp.end(), digest)) return false;
    // Pinned issuer consistency: receipt's ipk_* (when present) must match.
    auto ipkx = json_string(c, "ipk_x");
    auto ipky = json_string(c, "ipk_y");
    if (!ipkx.empty() || !ipky.empty()) {
        Bytes ix, iy;
        if (!b64u_decode(ipkx, ix) || ix.size() != 32) return false;
        if (!b64u_decode(ipky, iy) || iy.size() != 32) return false;
        if (!std::equal(ix.begin(), ix.end(), issuer_key_.begin() + 1)) return false;
        if (!std::equal(iy.begin(), iy.end(), issuer_key_.begin() + 33)) return false;
    }
    std::array<std::byte, 16> node_id{};
    if (!uuid_to_bytes(sub, node_id)) return false;
    claim.node_id = node_id;
    claim.public_key[0] = std::byte{0x04};
    for (std::size_t i = 0; i < 32; ++i) {
        claim.public_key[1 + i] = static_cast<std::byte>(xb[i]);
        claim.public_key[33 + i] = static_cast<std::byte>(yb[i]);
    }
    claim.organization_id = org;
    claim.organization_name = json_string(c, "org_name");
    claim.public_contact = json_string(c, "contact");
    if (claim.organization_id.empty()) return false;
    return true;
}

void SimCollector::clear() {
    records_.clear();
    base_watermark_ = 0;
}

std::size_t SimCollector::persist(const std::vector<RawObservation>& records) {
    std::size_t added = 0;
    for (const auto& r : records) {
        if (persist_one(r)) ++added;
    }
    return added;
}

bool SimCollector::persist_one(const RawObservation& record) {
    if (!record.valid()) return false;
    auto key = std::make_pair(record.node_id.to_string(), record.sequence);
    auto [it, inserted] = records_.emplace(key, record);
    return inserted;
}

bool SimCollector::has(Sequence seq) const {
    for (const auto& [k, _] : records_) {
        if (k.second == seq) return true;
    }
    return false;
}

bool SimCollector::has(const NodeId& node, Sequence seq) const {
    return records_.find({node.to_string(), seq}) != records_.end();
}

std::vector<std::uint64_t> SimCollector::sequences() const {
    std::vector<std::uint64_t> out;
    out.reserve(records_.size());
    for (const auto& [k, _] : records_) out.push_back(k.second);
    std::sort(out.begin(), out.end());
    return out;
}

std::uint64_t SimCollector::watermark(std::uint64_t base) const {
    return ble::contiguous_watermark(base, sequences());
}

bool SimCollector::verify_node_proof(const std::array<unsigned char, 65>& node_key,
                                     const std::string& node_id,
                                     const std::array<unsigned char, 32>& nonce,
                                     const std::array<unsigned char, 64>& signature) {
    if (!uuid_valid(node_id)) return false;
    std::string msg = "MM-NODE-SESSION-v1\n" + node_id + "\n" +
                      b64u_encode(nonce.data(), nonce.size());
    return ecdsa_verify(node_key,
                        reinterpret_cast<const unsigned char*>(msg.data()), msg.size(),
                        signature.data(), signature.size());
}

const SimFixtures& sim_fixtures() {
    static SimFixtures fixtures;
    static bool attempted = false;
    if (attempted) return fixtures;
    attempted = true;

    bool ok = false;
    std::string sim_text = read_file(fixture_path("sim-ble-v1.json"), ok);
    if (!ok) {
        fixtures.load_error = "missing sim-ble-v1.json at " + fixture_path("sim-ble-v1.json");
        return fixtures;
    }
    JsonPtr sim(cJSON_Parse(sim_text.c_str()), cJSON_Delete);
    if (!cJSON_IsObject(sim.get())) {
        fixtures.load_error = "sim-ble-v1.json is not a JSON object";
        return fixtures;
    }
    auto req = [&](const char* parent, const char* name, std::string& out) -> bool {
        if (parent) {
            auto* p = cJSON_GetObjectItemCaseSensitive(sim.get(), parent);
            if (!cJSON_IsObject(p)) return false;
            return get_string(p, name, out);
        }
        return get_string(sim.get(), name, out);
    };
    // Flat fields.
    std::string tmp;
    if (!get_nested_string(sim.get(), "issuer_public_key", "x", fixtures.issuer_x)) goto fail;
    if (!get_nested_string(sim.get(), "issuer_public_key", "y", fixtures.issuer_y)) goto fail;
    if (!key_from_b64u(fixtures.issuer_x, fixtures.issuer_y, fixtures.issuer_key)) goto fail;
    if (!get_nested_string(sim.get(), "organization_a", "id", fixtures.org_a_id)) goto fail;
    if (!get_nested_string(sim.get(), "organization_a", "slug", fixtures.org_a_slug)) goto fail;
    if (!get_nested_string(sim.get(), "organization_a", "name", fixtures.org_a_name)) goto fail;
    if (!get_nested_string(sim.get(), "organization_a", "contact", fixtures.org_a_contact))
        goto fail;
    if (!get_nested_string(sim.get(), "organization_b", "id", fixtures.org_b_id)) goto fail;
    if (!get_nested_string(sim.get(), "organization_b", "slug", fixtures.org_b_slug)) goto fail;
    if (!get_nested_string(sim.get(), "organization_b", "name", fixtures.org_b_name)) goto fail;
    if (!get_nested_string(sim.get(), "organization_b", "contact", fixtures.org_b_contact))
        goto fail;
    if (!req("credentials", "member_valid_jwt", fixtures.member_jwt)) goto fail;
    if (!req("credentials", "admin_valid_jwt", fixtures.admin_jwt)) goto fail;
    if (!req("credentials", "foreign_valid_jwt", fixtures.foreign_jwt)) goto fail;
    if (!req("credentials", "expired_jwt", fixtures.expired_jwt)) goto fail;
    if (!req("challenges", "fixed_hex", fixtures.fixed_challenge_hex)) goto fail;
    if (!req("challenges", "alt_hex", fixtures.alt_challenge_hex)) goto fail;
    {
        Bytes b;
        if (!hex_to_bytes(fixtures.fixed_challenge_hex, b) || b.size() != 32) goto fail;
        std::copy(b.begin(), b.end(), fixtures.fixed_challenge.begin());
        if (!hex_to_bytes(fixtures.alt_challenge_hex, b) || b.size() != 32) goto fail;
        std::copy(b.begin(), b.end(), fixtures.alt_challenge.begin());
    }
    if (!req("proofs", "member_fixed_b64u", fixtures.member_proof_b64u)) goto fail;
    if (!req("proofs", "admin_fixed_b64u", fixtures.admin_proof_b64u)) goto fail;
    if (!req("proofs", "foreign_fixed_b64u", fixtures.foreign_proof_b64u)) goto fail;
    {
        Bytes b;
        if (!b64u_decode(fixtures.member_proof_b64u, b) || b.size() != 64) goto fail;
        std::copy(b.begin(), b.end(), fixtures.member_proof.begin());
        if (!b64u_decode(fixtures.admin_proof_b64u, b) || b.size() != 64) goto fail;
        std::copy(b.begin(), b.end(), fixtures.admin_proof.begin());
        if (!b64u_decode(fixtures.foreign_proof_b64u, b) || b.size() != 64) goto fail;
        std::copy(b.begin(), b.end(), fixtures.foreign_proof.begin());
    }
    if (!req("claim_receipts", "orgA_nodeMain_jwt", fixtures.receipt_a)) goto fail;
    if (!req("claim_receipts", "orgB_nodeMain_jwt", fixtures.receipt_b)) goto fail;
    if (!get_nested_string(sim.get(), "node_main", "id", fixtures.node_main_id)) goto fail;
    if (!get_nested_string(sim.get(), "node_main", "x", fixtures.node_main_x)) goto fail;
    if (!get_nested_string(sim.get(), "node_main", "y", fixtures.node_main_y)) goto fail;
    if (!key_from_b64u(fixtures.node_main_x, fixtures.node_main_y, fixtures.node_main_key))
        goto fail;
    {
        auto* adv = cJSON_GetObjectItemCaseSensitive(sim.get(), "claim_advertisement");
        if (!cJSON_IsObject(adv)) goto fail;
        if (!get_string(adv, "message_ascii", fixtures.claim_msg)) goto fail;
        if (!get_string(adv, "signature_b64u", fixtures.claim_sig_b64u)) goto fail;
        Bytes b;
        if (!b64u_decode(fixtures.claim_sig_b64u, b) || b.size() != 64) goto fail;
        std::copy(b.begin(), b.end(), fixtures.claim_sig.begin());
    }
    {
        auto* sp = cJSON_GetObjectItemCaseSensitive(sim.get(), "node_session_proof");
        if (!cJSON_IsObject(sp)) goto fail;
        if (!get_string(sp, "message_ascii", fixtures.session_msg)) goto fail;
        if (!get_string(sp, "signature_b64u", fixtures.session_sig_b64u)) goto fail;
        Bytes b;
        if (!b64u_decode(fixtures.session_sig_b64u, b) || b.size() != 64) goto fail;
        std::copy(b.begin(), b.end(), fixtures.session_sig.begin());
        std::string nonce_hex;
        if (!get_string(sp, "fixed_nonce_hex", nonce_hex)) goto fail;
        if (!hex_to_bytes(nonce_hex, b) || b.size() != 32) goto fail;
        std::copy(b.begin(), b.end(), fixtures.session_nonce.begin());
    }
    {
        auto* now = cJSON_GetObjectItemCaseSensitive(sim.get(), "trusted_now_s");
        if (!cJSON_IsNumber(now)) goto fail;
        fixtures.trusted_now_s = static_cast<std::int64_t>(now->valuedouble);
    }

    // Vector interop (single golden credential + claim receipt + node key).
    {
        bool vok = false;
        std::string vtext = read_file(fixture_path("offline-credential-v1.json"), vok);
        if (!vok) goto fail;
        JsonPtr v(cJSON_Parse(vtext.c_str()), cJSON_Delete);
        if (!cJSON_IsObject(v.get())) goto fail;
        if (!get_string(v.get(), "organization_id", fixtures.vector_org_id)) goto fail;
        if (!get_string(v.get(), "credential_jwt", fixtures.vector_credential_jwt)) goto fail;
        if (!get_string(v.get(), "challenge_signature_b64u", fixtures.vector_proof_b64u))
            goto fail;
        if (!get_string(v.get(), "node_id", fixtures.vector_node_id)) goto fail;
        if (!get_string(v.get(), "claim_receipt_jwt", fixtures.vector_receipt_jwt)) goto fail;
        std::string ch_hex, ix, iy;
        if (!get_string(v.get(), "challenge_hex", ch_hex)) goto fail;
        auto* iss = cJSON_GetObjectItemCaseSensitive(v.get(), "issuer_public_key");
        if (!cJSON_IsObject(iss)) goto fail;
        if (!get_string(iss, "x", ix)) goto fail;
        if (!get_string(iss, "y", iy)) goto fail;
        if (!key_from_b64u(ix, iy, fixtures.vector_issuer_key)) goto fail;
        auto* npk = cJSON_GetObjectItemCaseSensitive(v.get(), "node_public_key");
        if (!cJSON_IsObject(npk)) goto fail;
        if (!get_string(npk, "x", fixtures.vector_node_x)) goto fail;
        if (!get_string(npk, "y", fixtures.vector_node_y)) goto fail;
        if (!key_from_b64u(fixtures.vector_node_x, fixtures.vector_node_y,
                           fixtures.vector_node_key))
            goto fail;
        Bytes b;
        if (!hex_to_bytes(ch_hex, b) || b.size() != 32) goto fail;
        std::copy(b.begin(), b.end(), fixtures.vector_challenge.begin());
        if (!b64u_decode(fixtures.vector_proof_b64u, b) || b.size() != 64) goto fail;
        std::copy(b.begin(), b.end(), fixtures.vector_proof.begin());
    }

    fixtures.loaded = true;
    return fixtures;
fail:
    fixtures.load_error = "sim-ble-v1.json missing/invalid field";
    fixtures.loaded = false;
    return fixtures;
}

}  // namespace miezmerker::sim
