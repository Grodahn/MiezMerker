#pragma once

// Simulated BLE transport + collector test double for issue #7 (Muse Spark 1.3).
//
// Reuses production components wherever possible:
// - ble::SyncServer (firmware-core/ble_sync) for gating/cursor/ACK/compact
// - ble_gatt::GattRouter (firmware-esp32/components/ble-sync) for version
//   negotiation and frame routing (the real GATT state machine)
// - ble::encode_*/decode_* (firmware-core/ble_codec) for every wire byte
// - ble::contiguous_watermark for collector high-watermark computation
// - OfflineAuthSession (firmware-esp32/components/offline-auth) for offline
//   credential + proof verification (real ES256/JWT, no reimplementation)
// - Core (firmware-core) for capture/sequence/persistence (issue #5)
//
// Host adapters: SimDeviceIdentity atomically stores NodeIdentity (keys,
// capture metadata and ownership) with the existing fault injection. Imported
// fixture keys are opaque handles for precomputed signatures; reset keys are
// deterministic test-only P-256 keys. Production NodeIdentityManager owns
// claim gates, node/key binding, idempotent retry, signing messages and rotation.
// SimOwnerStore and SimNodeKeys are views rebuilt from this durable record.
// SimCollector models the phone's durable outbox without browser concepts.
// Backend issuance is a fixture selection with an ADMIN-only role gate;
// backend policy itself remains covered by backend tests.

#include <array>
#include <cstdint>
#include <map>
#include <memory>
#include <optional>
#include <string>
#include <vector>

#include "miezmerker/ble_sync.hpp"
#include "miezmerker/node_identity.hpp"
#include "miezmerker/ble_gatt.hpp"

namespace miezmerker::sim {

// Deterministic transport interruption inside a response frame. The real
// router processes the request; an interrupted response never reaches decoding
// or the collector outbox. The runner then tears down the node session.
class SimBleTransport {
public:
    explicit SimBleTransport(ble_gatt::GattRouter& router) : router_(router) {}
    std::vector<std::uint8_t> exchange(const std::vector<std::uint8_t>& request,
        ble::TrustedEpochSeconds now, std::optional<std::size_t> disconnect_after = std::nullopt);
    bool interrupted() const { return interrupted_; }
private:
    ble_gatt::GattRouter& router_;
    bool interrupted_{false};
};

// Canonical fixture bundle loaded once from protocol/fixtures/*.json.
// Uses cJSON (same parser family as OfflineAuthSession) at runtime so the
// simulator, PWA (#8) and backend tests (#13) share one source of truth.
struct SimFixtures {
    // sim-ble-v1 (fresh test-only keys).
    std::string issuer_x;
    std::string issuer_y;
    std::array<unsigned char, 65> issuer_key{};  // 0x04||x||y
    std::string org_a_id;
    std::string org_a_slug;
    std::string org_a_name;
    std::string org_a_contact;
    std::string org_b_id;
    std::string org_b_slug;
    std::string org_b_name;
    std::string org_b_contact;
    std::string member_jwt;
    std::string admin_jwt;
    std::string foreign_jwt;
    std::string expired_jwt;
    std::string fixed_challenge_hex;
    std::string alt_challenge_hex;
    std::array<unsigned char, 32> fixed_challenge{};
    std::array<unsigned char, 32> alt_challenge{};
    std::string member_proof_b64u;
    std::string admin_proof_b64u;
    std::string foreign_proof_b64u;
    std::array<unsigned char, 64> member_proof{};
    std::array<unsigned char, 64> admin_proof{};
    std::array<unsigned char, 64> foreign_proof{};
    std::string receipt_a;  // orgA x nodeMain
    std::string receipt_b;  // orgB x nodeMain
    std::string node_main_id;
    std::string node_main_x;
    std::string node_main_y;
    std::array<unsigned char, 65> node_main_key{};  // 0x04||x||y
    std::string claim_msg;
    std::string claim_sig_b64u;
    std::array<unsigned char, 64> claim_sig{};
    std::string session_msg;
    std::string session_sig_b64u;
    std::array<unsigned char, 64> session_sig{};
    std::array<unsigned char, 32> session_nonce{};  // fixed B0 nonce
    std::int64_t trusted_now_s{1790899300};

    // vector interop (offline-credential-v1.json) for one golden scenario.
    std::string vector_org_id;
    std::string vector_credential_jwt;
    std::string vector_proof_b64u;
    std::array<unsigned char, 64> vector_proof{};
    std::array<unsigned char, 32> vector_challenge{};
    std::array<unsigned char, 65> vector_issuer_key{};
    std::string vector_node_id;
    std::string vector_node_x;
    std::string vector_node_y;
    std::array<unsigned char, 65> vector_node_key{};
    std::string vector_receipt_jwt;

    bool loaded{false};
    std::string load_error;
};

const SimFixtures& sim_fixtures();

// Public owner view and provisioned trust anchor; ownership reloads from NodeIdentity.
struct SimOwnerStore {
    bool claimed{false};
    std::string organization_id;
    std::string organization_slug;
    std::string organization_name;
    std::string public_contact;
    std::array<unsigned char, 65> issuer_key{};  // pinned backend key
    bool has_issuer{false};

    void clear();
};

// Physical claim-mode trigger (volatile; reset on boot like a button).
struct SimClaimMode : ClaimMode {
    bool enabled{false};
    bool active() const override { return enabled; }
};

// Public node-key view rebuilt from the atomic NodeIdentity record.
struct SimNodeKeys {
    bool provisioned{false};
    std::string node_id;  // UUID string matching Core node_id when imported
    std::array<unsigned char, 65> public_key{};
    std::string key_x_b64u;
    std::string key_y_b64u;

    void clear();
};

// Host claim-receipt verifier (NodeCrypto) with real ES256/JWT checks.
// Follows the same strict JWS rules as OfflineAuthSession (no crit/b64,
// ES256/kid pinning, duplicate-member rejection) applied to kind=claim.
class SimClaimCrypto final : public NodeCrypto {
public:
    explicit SimClaimCrypto(const std::array<unsigned char, 65>& issuer_key);
    void set_issuer(const std::array<unsigned char, 65>& issuer_key);
    void set_now(std::int64_t now) { now_ = now; }
    bool generate_keypair(std::span<std::byte, 65> public_key,
                          std::span<std::byte, 32> private_key) override;
    bool sign(std::span<const std::byte, 32> private_key,
              std::span<const std::byte> message,
              std::span<std::byte, 64> signature) override;
    bool verify_claim(const std::string& receipt, VerifiedClaim& claim) override;

private:
    std::array<unsigned char, 65> issuer_key_;
    std::int64_t now_{1790899300};
    std::uint64_t key_counter_{0};
};

// Collector (phone-side) durable outbox with idempotent inserts.
// Uses production ble::contiguous_watermark for high-watermark math.
class SimCollector {
public:
    void clear();
    // Idempotent: same (node_id, sequence) twice stores once.
    // Returns number of newly stored records.
    std::size_t persist(const std::vector<RawObservation>& records);
    bool persist_one(const RawObservation& record);
    bool has(const NodeId& node, Sequence seq) const;
    std::size_t size() const { return records_.size(); }
    std::uint64_t watermark(const NodeId& node, std::uint64_t base) const;
    const std::map<std::pair<std::string, std::uint64_t>, RawObservation>& records() const {
        return records_;
    }

    // Real ECDSA verification of node session proofs with the pinned node key
    // (mirrors PWA WebCrypto check in #8; same message format as firmware).
    static bool verify_node_proof(const std::array<unsigned char, 65>& node_key,
                                  const std::string& node_id,
                                  const std::array<unsigned char, 32>& nonce,
                                  const std::array<unsigned char, 64>& signature);

private:
    std::map<std::pair<std::string, std::uint64_t>, RawObservation> records_;
};

}  // namespace miezmerker::sim
