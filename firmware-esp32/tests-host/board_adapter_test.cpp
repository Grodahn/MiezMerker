#include "miezmerker/persistent_state.hpp"
#include "miezmerker/gatt_endpoint.hpp"
#include "miezmerker/memory_stores.hpp"
#include <cassert>
#include <algorithm>
#include <iostream>
using namespace miezmerker;
using namespace miezmerker::esp32;
using namespace miezmerker::ble;
using namespace miezmerker::ble_gatt;
namespace {
// Durable cells model atomic old/new NVS blobs, including ambiguous failures.
struct Cell : AtomicBlob {
    Bytes data;
    bool fail_before{false}, fail_after{false}, read_error{false};
    BlobResult read(Bytes& out) override {
        if (read_error) return BlobResult::error;
        if (data.empty()) return BlobResult::missing;
        out = data; return BlobResult::loaded;
    }
    bool replace(const Bytes& value) override {
        if (fail_before) { fail_before = false; return false; }
        data = value;
        if (fail_after) { fail_after = false; return false; }
        return true;
    }
};
struct Physical : ClaimMode { bool active() const override { return true; } };
struct Time : ClaimClock { std::uint64_t epoch_ms() const override { return 0; } };
struct FakeCrypto : NodeCrypto {
    unsigned counter{0};
    bool generate_keypair(std::span<std::byte, 65> pub, std::span<std::byte, 32> priv) override {
        std::fill(pub.begin(), pub.end(), static_cast<std::byte>(++counter)); pub[0] = std::byte{4};
        std::fill(priv.begin(), priv.end(), static_cast<std::byte>(counter)); return true;
    }
    bool sign(std::span<const std::byte, 32>, std::span<const std::byte>, std::span<std::byte, 64>) override { return false; }
    bool verify_claim(const std::string&, VerifiedClaim&) override { return false; }
};
struct Boot {
    PersistentIdentity store;
    PersistentObservations observations;
    NodeIdentityManager identity;
    Core core;
    Boot(Cell& id, Cell& log, ManualClock& clock, Physical& mode, FixedRandomSource& random, FakeCrypto& crypto, Time& time)
        : store(id), observations(log), identity(store, mode, random, crypto, time),
          core(clock, observations, observations, identity, random, CoreConfig{0}) {}
};
void persistence() {
    Cell id, log; ManualClock clock; Physical mode; Time time; FixedRandomSource random{42}; FakeCrypto crypto;
    Boot first(id, log, clock, mode, random, crypto, time);
    assert(first.core.initialize());
    const auto node = first.core.node_id();
    const auto incarnation_node = first.identity.identity().node_id;
    const auto key = first.identity.identity().private_key;
    assert(first.core.record_chip_read("SYNTHETIC-MM-1") == RecordResult::RECORDED);
    const auto original = encode_record(first.core.load_observations().front());
    Boot reboot(id, log, clock, mode, random, crypto, time);
    assert(reboot.core.initialize());
    assert(reboot.core.node_id() == node && reboot.identity.identity().private_key == key);
    assert(reboot.identity.identity().node_id == incarnation_node);
    assert(reboot.core.next_sequence() == 2 && encode_record(reboot.core.load_observations().front()) == original);
    assert(!reboot.core.load_observations().front().observed_at_epoch_ms);
    assert(!reboot.core.set_ack_watermark(2));
    assert(reboot.core.set_ack_watermark(1));
    assert(reboot.core.compact_acked());
    Boot compacted(id, log, clock, mode, random, crypto, time);
    assert(compacted.core.initialize() && compacted.core.ack_watermark() == 1);
    assert(compacted.core.observation_count() == 0 && compacted.core.next_sequence() == 2);
    assert(compacted.core.set_ack_watermark(1));
    assert(compacted.core.record_chip_read("SYNTHETIC-MM-2") == RecordResult::RECORDED);
    assert(compacted.core.load_observations().front().sequence == 2);
    // Reset journal survives failed log cleanup and rotates keys only once.
    log.fail_before = true;
    assert(!compacted.core.factory_reset());
    NodeIdentity pending; assert(compacted.store.load(pending) == NodeIdentityLoadResult::loaded);
    assert(pending.observations->reset_pending && pending.private_key != key);
    Boot resumed(id, log, clock, mode, random, crypto, time);
    assert(resumed.core.initialize());
    assert(resumed.identity.identity().private_key == pending.private_key);
    assert(resumed.core.node_id() != node && resumed.core.observation_count() == 0 && resumed.core.ack_watermark() == 0);
    assert(resumed.core.next_sequence() == 1);
    // Checksums and I/O errors never turn an existing identity into "first boot".
    id.data[10] ^= 1;
    Boot corrupted(id, log, clock, mode, random, crypto, time);
    const auto saved = id.data;
    assert(!corrupted.core.initialize() && id.data == saved);
}
void interruption() {
    for (bool identity_failure : {false, true}) for (bool committed : {false, true}) {
        Cell id, log; ManualClock clock; Physical mode; Time time; FixedRandomSource random{3}; FakeCrypto crypto;
        Boot first(id, log, clock, mode, random, crypto, time); assert(first.core.initialize());
        assert(first.core.record_chip_read("old") == RecordResult::RECORDED);
        auto& cell = identity_failure ? id : log;
        if (committed) cell.fail_after = true; else cell.fail_before = true;
        assert(first.core.record_chip_read("interrupted") != RecordResult::RECORDED);
        Boot reboot(id, log, clock, mode, random, crypto, time); assert(reboot.core.initialize());
        assert(reboot.core.load_observations().front().chip_id.value == "old");
        const auto next = reboot.core.next_sequence();
        assert(reboot.core.record_chip_read("after") == RecordResult::RECORDED);
        const auto records = reboot.core.load_observations();
        assert(records.back().sequence == next && next > records.front().sequence);
        for (std::size_t i = 1; i < records.size(); ++i) assert(records[i].sequence > records[i-1].sequence);
    }
    for (bool committed : {false, true}) {
        Cell id, log; ManualClock clock; Physical mode; Time time; FixedRandomSource random{9}; FakeCrypto crypto;
        Boot first(id, log, clock, mode, random, crypto, time); assert(first.core.initialize());
        assert(first.core.record_chip_read("one") == RecordResult::RECORDED);
        assert(first.core.record_chip_read("two") == RecordResult::RECORDED);
        if (committed) log.fail_after = true; else log.fail_before = true;
        assert(!first.core.set_ack_watermark(1));
        assert(!first.core.set_ack_watermark(2)); // Failure latch prevents stale overwrite.
        Boot reboot(id, log, clock, mode, random, crypto, time); assert(reboot.core.initialize());
        assert(reboot.core.ack_watermark() == (committed ? 1U : 0U));
        assert(reboot.core.observation_count() == 2);
        assert(!reboot.core.set_ack_watermark(3));
        assert(reboot.core.set_ack_watermark(1));
        if (committed) log.fail_after = true; else log.fail_before = true;
        assert(!reboot.core.compact_acked());
        Boot after_prune(id, log, clock, mode, random, crypto, time); assert(after_prune.core.initialize());
        assert(after_prune.core.load_observations().back().chip_id.value == "two");
        assert(after_prune.core.ack_watermark() == 1);
    }
}
void exhaustion() {
    Cell id, log; ManualClock clock; Physical mode; Time time; FixedRandomSource random{7}; FakeCrypto crypto;
    Boot boot(id, log, clock, mode, random, crypto, time); assert(boot.core.initialize());
    for (unsigned i = 0; i < PersistentObservations::kCapacity; ++i)
        assert(boot.core.record_chip_read(std::to_string(i)) == RecordResult::RECORDED);
    const auto before = log.data;
    assert(boot.core.record_chip_read("extra") == RecordResult::REJECTED_FULL);
    assert(log.data == before);
    Boot reboot(id, log, clock, mode, random, crypto, time); assert(reboot.core.initialize());
    assert(reboot.core.observation_count() == PersistentObservations::kCapacity);
    assert(reboot.core.next_sequence() == PersistentObservations::kCapacity + 1);
    log.data.back() ^= 1;
    Boot damaged(id, log, clock, mode, random, crypto, time); assert(!damaged.core.initialize());
}
struct Auth : SyncAuthorizer {
    bool pending{false}, allowed{false};
    bool begin(std::array<std::uint8_t, 32>& out) override { allowed = false; pending = true; out.fill(5); return true; }
    bool authorize(const std::string& token, const std::array<std::uint8_t, 64>&, std::int64_t now) override {
        allowed = pending && token == "test" && now > 0; pending = false; return allowed;
    }
    bool can_sync(std::int64_t now) const override { return allowed && now > 0; }
    void disconnect() override { pending = allowed = false; }
};
struct Rtc : RtcControl { bool set_corrected_epoch_ms(std::uint64_t) override { return false; } };
struct Signer : NodeSigner { bool sign_session(const std::array<std::uint8_t, 32>&, std::array<std::uint8_t, 64>&) override { return false; } };
void endpoint() {
    Cell id, log; ManualClock clock; Physical mode; Time time; FixedRandomSource random{11}; FakeCrypto crypto;
    Boot boot(id, log, clock, mode, random, crypto, time); assert(boot.core.initialize());
    NodeIdentity claimed = boot.identity.identity();
    claimed.organization_id = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
    claimed.organization_name = "Test"; claimed.claim_receipt = "test-only";
    claimed.observations->claim_state = ClaimState::CLAIMED;
    assert(boot.store.save(claimed) && boot.core.refresh_identity());
    for (int i = 0; i < 4; ++i) assert(boot.core.record_chip_read(std::string(64, static_cast<char>('a' + i))) == RecordResult::RECORDED);
    Auth auth; Rtc rtc; Signer signer; OwnerMetadata owner;
    SyncServer sync(boot.core, auth, rtc, signer); GattRouter router(sync, owner); GattEndpoint transport(router, sync);
    transport.connect();
    assert(transport.read(Characteristic::Status, false, 1, 1) == AccessResult::unauthorized);
    assert(transport.write(Characteristic::Ack, encode_frame({1, Opcode::AckRequest, encode_ack_request(4)}), 23, 2, 1) == AccessResult::unauthorized);
    assert(transport.read(Characteristic::Challenge, false, 3, 1) == AccessResult::ok);
    const auto challenge = transport.value(Characteristic::Challenge);
    assert(transport.read(Characteristic::Challenge, true, 4, 1) == AccessResult::ok && challenge == transport.value(Characteristic::Challenge));
    const auto credential = encode_frame({1, Opcode::AuthRequest, encode_auth_request({"test", {}})});
    assert(transport.write(Characteristic::ClaimReceipt, credential, 23, 5, 1) == AccessResult::invalid);
    assert(transport.write(Characteristic::Auth, credential, 185, 6, 1) == AccessResult::ok);
    assert(transport.read(Characteristic::Status, false, 7, 1) == AccessResult::ok);
    std::uint64_t tick = 8;
    for (unsigned mtu : {23U, 185U, 277U, 517U, 1000U}) {
        assert(transport.write(Characteristic::Batch, encode_frame({1, Opcode::BatchRequest, encode_batch_request({1, 64})}), mtu, tick++, 1) == AccessResult::ok);
        const auto& value = transport.value(Characteristic::Batch);
        assert(value.size() <= 512);
        if (mtu >= 185) assert(value.size() <= std::min(mtu, 517U) - 3);
        assert(transport.read(Characteristic::Batch, true, tick++, 1) == AccessResult::ok);
    }
    // Re-check authority even for a cached long-read continuation.
    assert(transport.read(Characteristic::Batch, true, tick++, 0) == AccessResult::unauthorized);
    assert(transport.read(Characteristic::Challenge, false, tick++, 1) == AccessResult::ok);
    assert(transport.read(Characteristic::Batch, true, tick++, 1) == AccessResult::unauthorized);
    transport.disconnect(); transport.connect();
    assert(transport.read(Characteristic::Auth, false, tick++, 1) == AccessResult::unavailable);
    assert(transport.read(Characteristic::Status, false, tick++, 1) == AccessResult::unauthorized);
    assert(transport.write(Characteristic::Batch, {0x4d,0x4d,1,1,70,0,0,0,1}, 23, tick++, 1) == AccessResult::unauthorized);
    assert(transport.read(Characteristic::Challenge, false, tick++, 1) == AccessResult::ok);
    assert(transport.write(Characteristic::Auth, credential, 185, tick++, 1) == AccessResult::ok);
    assert(transport.read(Characteristic::Status, false, tick + 10001, 1) == AccessResult::unauthorized);
}
}
int main() { persistence(); interruption(); exhaustion(); endpoint(); std::cout << "board persistence and GATT adapter regression passed\n"; }
