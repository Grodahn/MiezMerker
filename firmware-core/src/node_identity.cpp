#include "miezmerker/node_identity.hpp"
#include <limits>
#include <utility>
#include <algorithm>

namespace miezmerker {
namespace {
bool valid_identity(const NodeIdentity& identity) {
    return (std::to_integer<unsigned>(identity.node_id[6]) & 0xf0) == 0x40
        && (std::to_integer<unsigned>(identity.node_id[8]) & 0xc0) == 0x80
        && identity.public_key[0] == std::byte{4}
        && std::any_of(identity.private_key.begin(), identity.private_key.end(),
                       [](auto b) { return b != std::byte{0}; })
        && (!identity.observations || (identity.observations->valid()
            && identity.observations->claim_state == (identity.organization_id.empty()
                ? ClaimState::UNCLAIMED : ClaimState::CLAIMED)
            && std::equal(identity.node_id.begin(), identity.node_id.end(),
                          identity.observations->node_id.bytes.begin(),
                          [](auto a, auto b) { return std::to_integer<unsigned>(a) == b; })
            && identity.sequence == identity.observations->next_sequence - 1));
}
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
    if (result == NodeIdentityLoadResult::error) return false;
    if (result == NodeIdentityLoadResult::loaded) {
        if (!valid_identity(loaded)) return false;
        identity_ = std::move(loaded);
        ready_ = !identity_.observations || !identity_.observations->reset_pending;
        return ready_;
    }
    return provision_new();
}

bool NodeIdentityManager::provision_new() {
    NodeIdentity previous;
    const auto loaded = store_.load(previous);
    if (loaded == NodeIdentityLoadResult::error) { ready_ = false; return false; }
    // Capture resets must use Core's resumable identity/log reset transaction.
    if (loaded == NodeIdentityLoadResult::loaded && previous.observations) return false;
    NodeIdentity candidate;
    random_.fill_random(std::span(reinterpret_cast<std::uint8_t*>(candidate.node_id.data()), candidate.node_id.size()));
    if (std::all_of(candidate.node_id.begin(), candidate.node_id.end(), [](auto b) { return b == std::byte{0}; })) return false;
    candidate.node_id[6] = static_cast<std::byte>(
            (std::to_integer<unsigned>(candidate.node_id[6]) & 0x0f) | 0x40);
    candidate.node_id[8] = static_cast<std::byte>(
            (std::to_integer<unsigned>(candidate.node_id[8]) & 0x3f) | 0x80);
    if (loaded == NodeIdentityLoadResult::loaded && candidate.node_id == previous.node_id) return false;
    if (!crypto_.generate_keypair(candidate.public_key, candidate.private_key)) return false;
    if (loaded == NodeIdentityLoadResult::loaded
        && (candidate.public_key == previous.public_key || candidate.private_key == previous.private_key)) return false;
    if (!valid_identity(candidate)) return false;
    if (!store_.save(candidate)) { ready_ = false; return false; }
    identity_ = std::move(candidate);
    ready_ = true;
    return true;
}

std::uint64_t NodeIdentityManager::next_sequence() {
    if (!ready_ || !reload() || identity_.observations
        || identity_.sequence == std::numeric_limits<std::uint64_t>::max()) return 0;
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
    if (!ready_ || !reload()) return false;
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
    if (!ready_ || !reload()) return false;
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
    if (candidate.observations) candidate.observations->claim_state = ClaimState::CLAIMED;
    if (!store_.save(candidate)) { ready_ = false; return false; }
    identity_ = std::move(candidate);
    return true;
}

bool NodeIdentityManager::sign_session_challenge(std::span<const std::byte> challenge,
        std::span<std::byte, 64> signature) {
    // Domain separation prevents a session request from signing a claim proof.
    if (!ready_ || !reload() || !claimed() || challenge.size() != 32) return false;
    constexpr char prefix[] = "MM-NODE-SESSION-v1\n";
    std::string message(prefix);
    message += uuid(identity_.node_id) + "\n" + base64url(challenge);
    return crypto_.sign(identity_.private_key,
        std::as_bytes(std::span(message.data(), message.size())), signature);
}

bool NodeIdentityManager::reload() {
    NodeIdentity current;
    if (store_.load(current) != NodeIdentityLoadResult::loaded || !valid_identity(current)) {
        ready_ = false;
        return false;
    }
    identity_ = std::move(current);
    ready_ = !identity_.observations || !identity_.observations->reset_pending;
    return ready_;
}

IdentityLoadResult NodeIdentityManager::load(IdentityRecord& out) {
    NodeIdentity current;
    const auto result = store_.load(current);
    if (result == NodeIdentityLoadResult::missing) { ready_ = false; return IdentityLoadResult::NOT_FOUND; }
    if (result != NodeIdentityLoadResult::loaded || !valid_identity(current)) {
        ready_ = false; return IdentityLoadResult::IO_ERROR;
    }
    if (!current.observations) {
        if (current.sequence == std::numeric_limits<std::uint64_t>::max()) return IdentityLoadResult::IO_ERROR;
        IdentityRecord record;
        std::transform(current.node_id.begin(), current.node_id.end(), record.node_id.bytes.begin(),
                       [](auto b) { return static_cast<std::uint8_t>(b); });
        random_.fill_random(record.incarnation.bytes);
        if (!record.incarnation.is_set()) return IdentityLoadResult::IO_ERROR;
        apply_uuidv4_variant(record.incarnation.bytes);
        record.next_sequence = current.sequence + 1;
        record.boot_counter = 1;
        record.claim_state = current.organization_id.empty() ? ClaimState::UNCLAIMED : ClaimState::CLAIMED;
        current.observations = record;
        if (!store_.save(current)) { ready_ = false; return IdentityLoadResult::IO_ERROR; }
    }
    out = *current.observations;
    identity_ = std::move(current);
    ready_ = !out.reset_pending;
    return IdentityLoadResult::OK;
}

bool NodeIdentityManager::store(const IdentityRecord& record) {
    if (!record.valid()) return false;
    NodeIdentity previous;
    const auto result = store_.load(previous);
    if (result == NodeIdentityLoadResult::error) { ready_ = false; return false; }
    if (result == NodeIdentityLoadResult::loaded && !valid_identity(previous)) return false;
    NodeIdentity candidate = previous;
    std::array<std::byte, 16> node_id;
    std::transform(record.node_id.bytes.begin(), record.node_id.bytes.end(), node_id.begin(),
                   [](auto b) { return static_cast<std::byte>(b); });
    if (result == NodeIdentityLoadResult::missing || node_id != previous.node_id) {
        // Rotation and reset metadata are committed atomically with fresh keys.
        if (record.claim_state != ClaimState::UNCLAIMED || record.next_sequence != kFirstSequence
            || (result == NodeIdentityLoadResult::loaded && !record.reset_pending)) return false;
        candidate = {};
        candidate.node_id = node_id;
        if (!crypto_.generate_keypair(candidate.public_key, candidate.private_key)) return false;
        if (result == NodeIdentityLoadResult::loaded
            && (candidate.public_key == previous.public_key || candidate.private_key == previous.private_key)) return false;
    } else {
        if (record.next_sequence - 1 < previous.sequence
            || (previous.observations && record.incarnation != previous.observations->incarnation)) return false;
    }
    candidate.observations = record;
    candidate.observations->claim_state = candidate.organization_id.empty() ? ClaimState::UNCLAIMED : ClaimState::CLAIMED;
    candidate.sequence = record.next_sequence - 1;
    if (!valid_identity(candidate)) return false;
    if (!store_.save(candidate)) { ready_ = false; return false; }
    identity_ = std::move(candidate);
    ready_ = !record.reset_pending;
    return true;
}
} // namespace miezmerker
