#include "miezmerker/node_identity.hpp"
#include <limits>
#include <utility>

namespace miezmerker {
namespace {
std::string base64url(std::span<const std::byte> bytes) {
    constexpr char alphabet[] = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
    std::string out;
    unsigned bits = 0, value = 0;
    for (auto b : bytes) {
        value = (value << 8) | std::to_integer<unsigned>(b);
        bits += 8;
        while (bits >= 6) { bits -= 6; out += alphabet[(value >> bits) & 63]; }
    }
    if (bits) out += alphabet[(value << (6 - bits)) & 63];
    return out;
}
std::string uuid(const std::array<std::byte, 16>& id) {
    constexpr char hex[] = "0123456789abcdef";
    std::string out;
    for (std::size_t i = 0; i < id.size(); ++i) {
        if (i == 4 || i == 6 || i == 8 || i == 10) out += '-';
        auto b = std::to_integer<unsigned>(id[i]);
        out += hex[b >> 4]; out += hex[b & 15];
    }
    return out;
}
}
NodeIdentityManager::NodeIdentityManager(NodeIdentityStore& store, ClaimMode& claim_mode,
        RandomSource& random, NodeCrypto& crypto, ClaimClock& clock)
    : store_(store), claim_mode_(claim_mode), random_(random), crypto_(crypto), clock_(clock) {}

bool NodeIdentityManager::initialize() {
    ready_ = false;
    NodeIdentity loaded;
    auto result = store_.load(loaded);
    if (result == IdentityLoadResult::error) return false;
    if (result == IdentityLoadResult::loaded) {
        identity_ = std::move(loaded);
        ready_ = true;
        return true;
    }
    return provision_new();
}

bool NodeIdentityManager::provision_new() {
    NodeIdentity candidate;
    random_.random_bytes(candidate.node_id);
    candidate.node_id[6] = static_cast<std::byte>(
            (std::to_integer<unsigned>(candidate.node_id[6]) & 0x0f) | 0x40);
    candidate.node_id[8] = static_cast<std::byte>(
            (std::to_integer<unsigned>(candidate.node_id[8]) & 0x3f) | 0x80);
    if (!crypto_.generate_keypair(candidate.public_key, candidate.private_key)) return false;
    if (!store_.save(candidate)) { ready_ = false; return false; }
    identity_ = std::move(candidate);
    ready_ = true;
    return true;
}

std::uint64_t NodeIdentityManager::next_sequence() {
    if (!ready_ || identity_.sequence == std::numeric_limits<std::uint64_t>::max()) return 0;
    NodeIdentity candidate = identity_;
    ++candidate.sequence;
    if (!store_.save(candidate)) {
        // A failed write may have committed: force reload before any further use.
        ready_ = false;
        return 0;
    }
    identity_ = std::move(candidate);
    return identity_.sequence;
}

bool NodeIdentityManager::factory_reset() {
    // Atomic replacement preserves the old identity if generation/write fails.
    return provision_new();
}

bool NodeIdentityManager::sign_claim(ClaimAdvertisement& advertisement) {
    advertisement = {};
    const auto timestamp_ms = clock_.epoch_ms();
    if (!in_claim_mode() || timestamp_ms == 0) return false;
    auto key = std::span<const std::byte>(identity_.public_key);
    std::string message = "MM-CLAIM-v1\n" + uuid(identity_.node_id) + "\n"
        + base64url(key.subspan(1, 32)) + "\n" + base64url(key.subspan(33, 32))
        + "\n" + std::to_string(timestamp_ms) + "\nclaim-mode";
    ClaimAdvertisement candidate;
    candidate.timestamp_ms = timestamp_ms;
    if (!crypto_.sign(identity_.private_key,
        std::as_bytes(std::span(message.data(), message.size())), candidate.signature)) return false;
    advertisement = candidate;
    return true;
}

bool NodeIdentityManager::apply_claim(const std::string& receipt) {
    if (!ready_) return false;
    VerifiedClaim claim;
    if (!crypto_.verify_claim(receipt, claim) || claim.node_id != identity_.node_id
        || claim.public_key != identity_.public_key || claim.organization_id.empty()) return false;
    if (claimed()) return claim.organization_id == identity_.organization_id;
    if (!in_claim_mode()) return false;
    NodeIdentity candidate = identity_;
    candidate.organization_id = claim.organization_id;
    candidate.organization_name = claim.organization_name;
    candidate.public_contact = claim.public_contact;
    candidate.claim_receipt = receipt;
    if (!store_.save(candidate)) { ready_ = false; return false; }
    identity_ = std::move(candidate);
    return true;
}

bool NodeIdentityManager::sign_session_challenge(std::span<const std::byte> challenge,
        std::span<std::byte, 64> signature) {
    // Domain separation prevents a session request from signing a claim proof.
    if (!ready_ || !claimed() || challenge.size() != 32) return false;
    constexpr char prefix[] = "MM-NODE-SESSION-v1\n";
    std::string message(prefix);
    message += uuid(identity_.node_id) + "\n" + base64url(challenge);
    return crypto_.sign(identity_.private_key,
        std::as_bytes(std::span(message.data(), message.size())), signature);
}
} // namespace miezmerker
