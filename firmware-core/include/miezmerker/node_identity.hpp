#pragma once
#include <array>
#include <cstdint>
#include <span>
#include <string>
#include "miezmerker/ports.hpp"

namespace miezmerker {

// Persistent node identity (#18): random UUIDv4 node_id + opaque P-256 device key
// material + monotonic sequence. Never derived from BLE MAC, ESP MAC, DB id or
// FeedingSite id. Key material is opaque here; real P-256 generation/verification
// belongs to the ESP32 adapter (#4) and backend crypto (#17/#18).
struct NodeIdentity {
    static constexpr std::size_t ID_SIZE = 16;
    static constexpr std::size_t PUBKEY_SIZE = 65;   // 0x04 || x(32) || y(32)
    static constexpr std::size_t PRIVKEY_SIZE = 32;  // P-256 scalar

    std::array<std::byte, ID_SIZE> node_id{};
    std::array<std::byte, PUBKEY_SIZE> public_key{};
    std::array<std::byte, PRIVKEY_SIZE> private_key{};
    std::uint64_t sequence{0};
    // Saved in the same atomic record as the identity; empty until provisioned.
    std::string organization_id;
    std::string organization_name;
    std::string public_contact;
    std::string claim_receipt;
};

// Physical claim-mode trigger (#18). Real ESP32-C3 button handling is #4; the core
// only depends on this port so tests can use a fake and later firmware a real one.
class ClaimMode {
public:
    virtual ~ClaimMode() = default;
    virtual bool active() const = 0;
};

// Durable identity store. Save atomically replaces the whole record (including
// ownership); implementations must serialize fields, not memcpy this C++ object.
// A load error must never be reported as missing and silently reprovision a node.
enum class IdentityLoadResult { missing, loaded, error };
class NodeIdentityStore {
public:
    virtual ~NodeIdentityStore() = default;
    virtual IdentityLoadResult load(NodeIdentity& identity) = 0;
    virtual bool save(const NodeIdentity& identity) = 0;
    virtual bool clear() = 0;
};

struct VerifiedClaim {
    std::array<std::byte, 16> node_id{};
    std::array<std::byte, 65> public_key{};
    std::string organization_id;
    std::string organization_name;
    std::string public_contact;
};

// Required cryptographic adapter, never random-byte or always-success defaults.
// verify_claim must verify ES256 against an independently pinned backend key,
// issuer/kind/version/expiry and node binding. Never trust ipk_* from the receipt
// as its own verification anchor. The stored receipt preserves the trust context.
class NodeCrypto {
public:
    virtual ~NodeCrypto() = default;
    virtual bool generate_keypair(std::span<std::byte, 65> public_key,
                                  std::span<std::byte, 32> private_key) = 0;
    virtual bool sign(std::span<const std::byte, 32> private_key,
                      std::span<const std::byte> message,
                      std::span<std::byte, 64> signature) = 0;
    virtual bool verify_claim(const std::string& receipt, VerifiedClaim& claim) = 0;
};

class NodeIdentityManager {
public:
    NodeIdentityManager(NodeIdentityStore& store, ClaimMode& claim_mode, RandomSource& random,
                        NodeCrypto& crypto);

    // Loads existing identity or provisions a new one. Reboot/power loss/firmware
    // update keep identity; only factory_reset() creates a new one.
    bool initialize();
    bool ready() const { return ready_; }
    const NodeIdentity& identity() const { return identity_; }

    // True only while the physical claim mode is active (fake in tests, button in #4).
    bool in_claim_mode() const { return ready_ && !claimed() && claim_mode_.active(); }
    bool claimed() const { return !identity_.organization_id.empty(); }
    bool apply_claim(const std::string& receipt);

    // Produce the canonical backend claim proof internally, only in claim mode.
    bool sign_claim(std::uint64_t timestamp_ms, std::span<std::byte, 64> signature);
    // A later session challenge proves possession of the persisted device key.
    bool sign_session_challenge(std::span<const std::byte> challenge,
                                std::span<std::byte, 64> signature);

    // Monotonic sequence tied to the node_id lifetime. Never resets without a
    // factory reset (which creates a new node_id).
    std::uint64_t next_sequence();

    // Factory reset: new node_id + new key pair + new sequence lifetime.
    // Historical data of the old identity stays bound to the old node_id.
    bool factory_reset();

private:
    bool provision_new();
    NodeIdentityStore& store_;
    ClaimMode& claim_mode_;
    RandomSource& random_;
    NodeCrypto& crypto_;
    NodeIdentity identity_;
    bool ready_{false};
};

} // namespace miezmerker
