#include "miezmerker/node_identity.hpp"
#include <cstring>

namespace miezmerker {

NodeIdentityManager::NodeIdentityManager(NodeIdentityStore& store, ClaimMode& claim_mode,
        RandomSource& random)
    : store_(store), claim_mode_(claim_mode), random_(random) {}

bool NodeIdentityManager::initialize() {
    if (store_.load(identity_)) {
        ready_ = true;
        return true;
    }
    return provision_new();
}

bool NodeIdentityManager::provision_new() {
    identity_ = NodeIdentity{};
    random_.random_bytes(std::span<std::byte>(identity_.node_id.data(), identity_.node_id.size()));
    // UUIDv4 shape: version 4 in the high nibble of byte 6, variant 10xx in byte 8.
    identity_.node_id[6] = static_cast<std::byte>(
            (static_cast<std::uint8_t>(identity_.node_id[6]) & 0x0f) | 0x40);
    identity_.node_id[8] = static_cast<std::byte>(
            (static_cast<std::uint8_t>(identity_.node_id[8]) & 0x3f) | 0x80);
    random_.random_bytes(
            std::span<std::byte>(identity_.public_key.data(), identity_.public_key.size()));
    identity_.public_key[0] = std::byte{0x04}; // uncompressed point marker
    random_.random_bytes(
            std::span<std::byte>(identity_.private_key.data(), identity_.private_key.size()));
    identity_.sequence = 0;
    if (!store_.save(identity_)) {
        return false;
    }
    ready_ = true;
    return true;
}

std::uint64_t NodeIdentityManager::next_sequence() {
    if (!ready_) {
        return 0;
    }
    std::uint64_t next = identity_.sequence + 1;
    identity_.sequence = next;
    store_.save(identity_);
    return next;
}

bool NodeIdentityManager::factory_reset() {
    if (!store_.clear()) {
        return false;
    }
    ready_ = false;
    return provision_new();
}

bool NodeIdentityManager::sign_claim(const std::byte* message, std::size_t len,
        std::span<std::byte, 64> out_signature) {
    // Opaque stub: real ECDSA P-256/SHA256 signing is provided by the ESP32 adapter
    // (#4) using the persisted private key. The core only guarantees the private key
    // is available to the adapter and never leaves the device.
    (void)message;
    (void)len;
    (void)out_signature;
    return false;
}

} // namespace miezmerker
