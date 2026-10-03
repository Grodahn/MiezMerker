#include "miezmerker/node_identity.hpp"
#include "miezmerker/core.hpp"
#include "miezmerker/memory_stores.hpp"
#include <cassert>
#include <iostream>
#include <limits>
#include <optional>
using namespace miezmerker;
struct TestRandom final : RandomSource {
    unsigned counter{0};
    void fill_random(std::span<std::uint8_t> out) override {
        for (auto& b : out) b = static_cast<std::uint8_t>((counter++ * 31 + 7) & 255);
    }
};
struct MemoryIdentityStore final : NodeIdentityStore {
    std::optional<NodeIdentity> saved;
    bool fail_save{false}, fail_load{false}, commit_then_fail{false};
    NodeIdentityLoadResult load(NodeIdentity& identity) override {
        if (fail_load) return NodeIdentityLoadResult::error;
        if (!saved) return NodeIdentityLoadResult::missing;
        identity = *saved;
        return NodeIdentityLoadResult::loaded;
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
struct TestClaimClock final : ClaimClock {
    std::uint64_t time{123456};
    std::uint64_t epoch_ms() const override { return time; }
};
struct TestStorage final : Storage {
    bool open() override { return true; }
};

static void capture_uses_claimed_identity() {
    MemoryIdentityStore cell;
    TestRandom random;
    FakeClaimMode mode;
    FakeCrypto crypto;
    TestClaimClock claim_clock;
    ManualClock clock;
    TestStorage storage;
    InMemoryObservationStore log(16);
    NodeIdentityManager node(cell, mode, random, crypto, claim_clock);
    Core capture(clock, storage, log, node, random, CoreConfig{});
    assert(capture.initialize()); // Capture-first boot also provisions the P-256 key.
    const auto first = node.identity();
    mode.enabled = true;
    crypto.claim = {first.node_id, first.public_key, "org-a", "Org A", "contact"};
    assert(node.apply_claim("receipt"));
    assert(capture.record_chip_read("chip-a") == RecordResult::RECORDED);
    assert(node.identity().organization_id == "org-a"); // Sequence write preserves claim.
    auto records = capture.load_observations();
    assert(records.size() == 1 && records[0].sequence == 1);
    for (std::size_t i = 0; i < first.node_id.size(); ++i)
        assert(records[0].node_id.bytes[i] == std::to_integer<unsigned>(first.node_id[i]));
    assert(!node.factory_reset()); // Cannot bypass capture's log/reset journal.
    assert(node.next_sequence() == 0); // Capture is the single sequence allocator.

    NodeIdentityManager reboot(cell, mode, random, crypto, claim_clock);
    Core reboot_capture(clock, storage, log, reboot, random, CoreConfig{});
    assert(reboot_capture.initialize());
    assert(reboot.identity().node_id == first.node_id && reboot.identity().public_key == first.public_key);
    assert(reboot.claimed());
    assert(reboot_capture.record_chip_read("chip-b") == RecordResult::RECORDED);
    assert(reboot_capture.load_observations().back().sequence == 2);
    assert(reboot_capture.set_ack_watermark(2));

    crypto.fail_generate = true;
    assert(!reboot_capture.factory_reset());
    assert(cell.saved->node_id == first.node_id && cell.saved->organization_id == "org-a");
    assert(log.size() == 2 && log.ack_watermark() == 2);
    crypto.fail_generate = false;
    assert(reboot_capture.initialize());

    log.set_fail_next_clear(true);
    assert(!reboot_capture.factory_reset()); // Identity/key rotation commits before log clear.
    assert(!reboot.ready());
    assert(reboot.identity().node_id != first.node_id);
    assert(reboot.identity().public_key != first.public_key);
    std::array<std::byte, 32> nonce{};
    std::array<std::byte, 64> signature{};
    assert(!reboot.sign_session_challenge(nonce, signature));
    NodeIdentityManager reset_boot(cell, mode, random, crypto, claim_clock);
    assert(!reset_boot.initialize()); // Incomplete reset cannot claim/authenticate.
    Core reset_capture(clock, storage, log, reset_boot, random, CoreConfig{});
    assert(reset_capture.initialize()); // Resumes journal and clears old log/cursor.
    assert(reset_boot.ready() && !reset_boot.claimed());
    assert(reset_capture.observation_count() == 0 && reset_capture.ack_watermark() == 0);
    assert(reset_capture.record_chip_read("chip-c") == RecordResult::RECORDED);
    auto new_record = reset_capture.load_observations().front();
    assert(new_record.node_id != records[0].node_id && new_record.sequence == 1);
    assert(reset_boot.identity().private_key != first.private_key);

    // A stale manager must reload capture's latest sequence before saving ownership.
    crypto.claim = {reset_boot.identity().node_id, reset_boot.identity().public_key, "org-a", "Org A", "contact"};
    assert(reset_boot.apply_claim("new-receipt"));
    assert(reset_boot.identity().sequence == 1);
    assert(reset_capture.record_chip_read("chip-d") == RecordResult::RECORDED);
    assert(reset_capture.load_observations().back().sequence == 2 && reset_boot.claimed());
    cell.commit_then_fail = true;
    assert(reset_capture.record_chip_read("chip-e") == RecordResult::IO_ERROR);
    cell.commit_then_fail = false;
    assert(reset_capture.initialize());
    assert(reset_capture.record_chip_read("chip-f") == RecordResult::RECORDED);
    assert(reset_capture.load_observations().back().sequence == 4);

    MemoryIdentityStore node_first_cell;
    NodeIdentityManager node_first(node_first_cell, mode, random, crypto, claim_clock);
    assert(node_first.initialize());
    const auto pre_capture = node_first.identity();
    assert(node_first.next_sequence() == 1);
    InMemoryObservationStore second_log;
    Core second_capture(clock, storage, second_log, node_first, random);
    assert(second_capture.initialize());
    assert(node_first.identity().node_id == pre_capture.node_id
        && node_first.identity().public_key == pre_capture.public_key);
    assert(second_capture.record_chip_read("chip") == RecordResult::RECORDED);
    assert(second_capture.load_observations().front().sequence == 2);
}

static void reset_rejects_repeated_entropy() {
    MemoryIdentityStore cell;
    TestRandom random;
    FakeClaimMode mode;
    FakeCrypto crypto;
    TestClaimClock clock;
    NodeIdentityManager node(cell, mode, random, crypto, clock);
    assert(node.initialize());
    auto original = node.identity();
    assert(node.next_sequence() == 1);
    random.counter = 0;
    NodeIdentityManager before_boot(cell, mode, random, crypto, clock);
    assert(!before_boot.factory_reset()); // Same UUID must never restart sequence.
    assert(cell.saved->node_id == original.node_id && cell.saved->sequence == 1);
    crypto.generation = 0;
    assert(!node.factory_reset()); // Fresh UUID with repeated key is rejected too.
    assert(cell.saved->node_id == original.node_id && cell.saved->sequence == 1);
    assert(node.initialize() && node.next_sequence() == 2);
    cell.saved->node_id = {};
    assert(!node.initialize()); // Corruption is not an invitation to reprovision.
    struct ZeroRandom final : RandomSource {
        void fill_random(std::span<std::uint8_t> out) override { std::fill(out.begin(), out.end(), 0); }
    } zeros;
    MemoryIdentityStore empty;
    NodeIdentityManager no_entropy(empty, mode, zeros, crypto, clock);
    assert(!no_entropy.initialize() && !empty.saved);
}
int main() {
    capture_uses_claimed_identity();
    reset_rejects_repeated_entropy();
    MemoryIdentityStore store;
    TestRandom random;
    FakeClaimMode mode;
    FakeCrypto crypto;
    TestClaimClock clock;
    NodeIdentityManager mgr(store, mode, random, crypto, clock);
    assert(!mgr.in_claim_mode());
    assert(mgr.initialize());
    auto initial = mgr.identity();
    assert((std::to_integer<unsigned>(initial.node_id[6]) & 0xf0) == 0x40);
    assert((std::to_integer<unsigned>(initial.node_id[8]) & 0xc0) == 0x80);
    assert(mgr.next_sequence() == 1);
    assert(mgr.next_sequence() == 2);
    NodeIdentityManager reboot(store, mode, random, crypto, clock);
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
    ClaimAdvertisement advertisement;
    assert(!reboot.sign_claim(advertisement));
    mode.enabled = true;
    assert(reboot.sign_claim(advertisement));
    assert(advertisement.timestamp_ms == clock.time);
    assert(crypto.signed_message == "MM-CLAIM-v1\ne7062544-6382-41c0-9ffe-1d3c5b7a99b8\n"
        "AgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgI\n"
        "AgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgI\n123456\nclaim-mode");
    clock.time = 0;
    assert(!reboot.sign_claim(advertisement));
    assert(advertisement.timestamp_ms == 0);
    clock.time = 123457;
    assert(reboot.sign_claim(advertisement));
    assert(advertisement.timestamp_ms == 123457);
    assert(crypto.signed_message.ends_with("\n123457\nclaim-mode"));
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
    assert(!reboot.sign_claim(advertisement));
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
