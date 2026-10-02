#pragma once
#include <array>
#include <cstdint>
#include <span>
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
};

// Physical claim-mode trigger (#18). Real ESP32-C3 button handling is #4; the core
// only depends on this port so tests can use a fake and later firmware a real one.
class ClaimMode {
public:
    virtual ~ClaimMode() = default;
    virtual bool active() const = 0;
};

// Durable identity store. Implementations must be crash-safe enough that a reboot
// never loses or corrupts identity (ESP32: NVS/flash; simulator: memory file).
class NodeIdentityStore {
public:
    virtual ~NodeIdentityStore() = default;
    virtual bool load(NodeIdentity& identity) = 0;
    virtual bool save(const NodeIdentity& identity) = 0;
    virtual bool clear() = 0;
};

class NodeIdentityManager {
public:
    NodeIdentityManager(NodeIdentityStore& store, ClaimMode& claim_mode, RandomSource& random);

    // Loads existing identity or provisions a new one. Reboot/power loss/firmware
    // update keep identity; only factory_reset() creates a new one.
    bool initialize();
    bool ready() const { return ready_; }
    const NodeIdentity& identity() const { return identity_; }

    // True only while the physical claim mode is active (fake in tests, button in #4).
    bool in_claim_mode() const { return claim_mode_.active(); }

    // Monotonic sequence tied to the node_id lifetime. Never resets without a
    // factory reset (which creates a new node_id).
    std::uint64_t next_sequence();

    // Factory reset: new node_id + new key pair + new sequence lifetime.
    // Historical data of the old identity stays bound to the old node_id.
    bool factory_reset();

    // Sign the canonical claim advertisement with the device private key.
    // Opaque here: returns raw signature bytes for the ESP32 adapter to produce.
    // The backend verifies ECDSA P-256/SHA256 over the canonical message.
    bool sign_claim(const std::byte* message, std::size_t len,
                    std::span<std::byte, 64> out_signature);

private:
    bool provision_new();
    NodeIdentityStore& store_;
    ClaimMode& claim_mode_;
    RandomSource& random_;
    NodeIdentity identity_;
    bool ready_{false};
};

} // namespace miezmerker
