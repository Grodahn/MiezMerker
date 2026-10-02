#include "miezmerker/node_identity.hpp"
#include <cassert>
#include <iostream>
#include <limits>
#include <optional>
using namespace miezmerker;
struct TestRandom final : RandomSource {
    unsigned counter{0};
    void random_bytes(std::span<std::byte> out) override {
        for (auto& b : out) b = static_cast<std::byte>((counter++ * 31 + 7) & 255);
    }
};
struct MemoryIdentityStore final : NodeIdentityStore {
    std::optional<NodeIdentity> saved;
    bool fail_save{false}, fail_load{false}, commit_then_fail{false};
    IdentityLoadResult load(NodeIdentity& identity) override {
        if (fail_load) return IdentityLoadResult::error;
        if (!saved) return IdentityLoadResult::missing;
        identity = *saved;
        return IdentityLoadResult::loaded;
    }
    bool save(const NodeIdentity& identity) override {
        if (commit_then_fail) { saved = identity; return false; }
        if (fail_save) return false;
        saved = identity; return true;
    }
    bool clear() override { saved.reset(); return true; }
};
struct FakeClaimMode final : ClaimMode {
    bool enabled{false};
    bool active() const override { return enabled; }
};
// Deliberately fake crypto: these tests exercise the domain port contract, not ECDSA.
struct FakeCrypto final : NodeCrypto {
    unsigned generation{0};
    bool fail_generate{false}, valid_receipt{true};
    VerifiedClaim claim;
    std::string signed_message;
    bool generate_keypair(std::span<std::byte, 65> pub, std::span<std::byte, 32> priv) override {
        if (fail_generate) return false;
        ++generation;
        std::fill(pub.begin(), pub.end(), static_cast<std::byte>(generation));
        std::fill(priv.begin(), priv.end(), static_cast<std::byte>(generation));
        pub[0] = std::byte{4}; return true;
    }
    bool sign(std::span<const std::byte, 32>, std::span<const std::byte> message,
              std::span<std::byte, 64> sig) override {
        signed_message.assign(reinterpret_cast<const char*>(message.data()), message.size());
        std::fill(sig.begin(), sig.end(), std::byte{1}); return true;
    }
    bool verify_claim(const std::string&, VerifiedClaim& out) override {
        out = claim; return valid_receipt;
    }
};
int main() {
    MemoryIdentityStore store;
    TestRandom random;
    FakeClaimMode mode;
    FakeCrypto crypto;
    NodeIdentityManager mgr(store, mode, random, crypto);
    assert(!mgr.in_claim_mode());
    assert(mgr.initialize());
    auto initial = mgr.identity();
    assert((std::to_integer<unsigned>(initial.node_id[6]) & 0xf0) == 0x40);
    assert((std::to_integer<unsigned>(initial.node_id[8]) & 0xc0) == 0x80);
    assert(mgr.next_sequence() == 1);
    assert(mgr.next_sequence() == 2);
    NodeIdentityManager reboot(store, mode, random, crypto);
    assert(reboot.initialize());
    assert(reboot.identity().node_id == initial.node_id);
    assert(reboot.identity().public_key == initial.public_key);
    assert(reboot.identity().private_key == initial.private_key);
    assert(reboot.identity().sequence == 2);

    store.fail_save = true;
    assert(reboot.next_sequence() == 0);
    assert(!reboot.ready());
    assert(reboot.next_sequence() == 0);
    store.fail_save = false;
    assert(reboot.initialize());
    assert(reboot.next_sequence() == 3);
    store.commit_then_fail = true;
    assert(reboot.next_sequence() == 0);
    store.commit_then_fail = false;
    assert(reboot.initialize());
    assert(reboot.next_sequence() == 5);
    store.saved->sequence = std::numeric_limits<std::uint64_t>::max();
    assert(reboot.initialize());
    assert(reboot.next_sequence() == 0);
    assert(reboot.identity().sequence == std::numeric_limits<std::uint64_t>::max());
    store.fail_load = true;
    assert(!reboot.initialize());
    assert(store.saved->node_id == initial.node_id);
    store.fail_load = false;
    crypto.fail_generate = true;
    assert(!reboot.factory_reset());
    assert(store.saved->node_id == initial.node_id);
    crypto.fail_generate = false;
    assert(reboot.factory_reset());
    auto reset = reboot.identity();
    assert(reset.node_id != initial.node_id && reset.public_key != initial.public_key);
    assert(reset.private_key != initial.private_key && reset.sequence == 0);
    assert(reboot.initialize());
    assert(reboot.identity().node_id == reset.node_id);

    std::array<std::byte, 64> signature{};
    assert(!reboot.sign_claim(123456, signature));
    mode.enabled = true;
    assert(reboot.sign_claim(123456, signature));
    assert(crypto.signed_message == "MM-CLAIM-v1\ne7062544-6382-41c0-9ffe-1d3c5b7a99b8\n"
        "AgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgI\n"
        "AgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgI\n123456\nclaim-mode");
    crypto.claim = {reset.node_id, reset.public_key, "org-a", "Org A", "help@example.org"};
    mode.enabled = false;
    assert(!reboot.apply_claim("receipt"));
    mode.enabled = true;
    crypto.valid_receipt = false;
    assert(!reboot.apply_claim("receipt"));
    crypto.valid_receipt = true;
    crypto.claim.node_id = initial.node_id;
    assert(!reboot.apply_claim("receipt"));
    crypto.claim.node_id = reset.node_id;
    crypto.claim.public_key = initial.public_key;
    assert(!reboot.apply_claim("receipt"));
    crypto.claim.public_key = reset.public_key;
    store.fail_save = true;
    assert(!reboot.apply_claim("receipt"));
    assert(!reboot.ready());
    store.fail_save = false;
    assert(reboot.initialize());
    assert(!reboot.claimed());
    assert(reboot.apply_claim("receipt"));
    assert(reboot.claimed() && !reboot.in_claim_mode());
    assert(!reboot.sign_claim(123456, signature));
    assert(reboot.initialize());
    assert(reboot.identity().organization_name == "Org A");
    assert(reboot.identity().claim_receipt == "receipt");
    assert(reboot.apply_claim("receipt"));
    crypto.claim.organization_id = "org-b";
    assert(!reboot.apply_claim("foreign receipt"));
    std::array<std::byte, 32> challenge{};
    assert(reboot.sign_session_challenge(challenge, signature));
    assert(crypto.signed_message.starts_with("MM-NODE-SESSION-v1\n"));
    assert(!reboot.sign_session_challenge({}, signature));
    assert(reboot.factory_reset());
    assert(!reboot.claimed() && reboot.identity().claim_receipt.empty());
    assert(!reboot.sign_session_challenge(challenge, signature));
    auto before_failed_reset = reboot.identity();
    store.fail_save = true;
    assert(!reboot.factory_reset());
    assert(!reboot.ready());
    store.fail_save = false;
    assert(reboot.initialize());
    assert(reboot.identity().node_id == before_failed_reset.node_id);
    crypto.claim = {reboot.identity().node_id, reboot.identity().public_key,
        "org-a", "Org A", "help@example.org"};
    store.commit_then_fail = true;
    assert(!reboot.apply_claim("receipt"));
    assert(!reboot.ready());
    store.commit_then_fail = false;
    assert(reboot.initialize());
    assert(reboot.claimed());
    assert(reboot.apply_claim("receipt"));
    std::cout << "Node identity, durable sequence, claim commit and session signing passed\n";
}
