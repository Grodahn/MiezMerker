#include "miezmerker/dev_button_reader.hpp"
#include "miezmerker/core.hpp"
#include "miezmerker/persistent_state.hpp"
#include "miezmerker/memory_stores.hpp"
#include "miezmerker/ble_codec.hpp"
#include <algorithm>
#include <cassert>
#include <iostream>
using namespace miezmerker;
using namespace miezmerker::esp32;
namespace {
struct Input : ButtonInput { bool level{true}; bool high() const override { return level; } };
struct Random : RandomSource {
    std::uint8_t value{0}; unsigned calls{0}; bool reject_once{false};
    void fill_random(std::span<std::uint8_t> out) override {
        ++calls;
        for (auto& byte : out) byte = reject_once ? 255 : value;
        reject_once = false;
    }
};
struct Cell : AtomicBlob {
    Bytes data;
    BlobResult read(Bytes& out) override { out = data; return data.empty() ? BlobResult::missing : BlobResult::loaded; }
    bool replace(const Bytes& value) override { data = value; return true; }
};
struct Crypto : NodeCrypto {
    VerifiedClaim claim;
    bool generate_keypair(std::span<std::byte, 65> pub, std::span<std::byte, 32> priv) override {
        std::fill(pub.begin(), pub.end(), std::byte{1}); pub[0] = std::byte{4};
        std::fill(priv.begin(), priv.end(), std::byte{1}); return true;
    }
    bool sign(std::span<const std::byte, 32>, std::span<const std::byte>, std::span<std::byte, 64>) override { return false; }
    bool verify_claim(const std::string& receipt, VerifiedClaim& out) override { out = claim; return receipt == "fixture-receipt"; }
};
struct Mode : ClaimMode { bool active() const override { return true; } };
struct Time : ClaimClock { std::uint64_t epoch_ms() const override { return 0; } };
void debounce() {
    ManualClock clock; Input input; Random random;
    DevButtonReader reader(input, clock, random);
    assert(!reader.poll()); clock.advance_monotonic_ms(50); assert(!reader.poll());
    input.level = false; assert(!reader.poll());
    clock.advance_monotonic_ms(49); assert(!reader.poll());
    input.level = true; assert(!reader.poll()); // Short LOW spike.
    clock.advance_monotonic_ms(5); input.level = false; assert(!reader.poll());
    clock.advance_monotonic_ms(50); assert(reader.poll() == "1");
    clock.advance_monotonic_ms(10000); assert(!reader.poll());
    input.level = true; assert(!reader.poll());
    clock.advance_monotonic_ms(49); assert(!reader.poll());
    input.level = false; assert(!reader.poll()); // Short HIGH cannot rearm.
    clock.advance_monotonic_ms(50); assert(!reader.poll());
    input.level = true; assert(!reader.poll()); clock.advance_monotonic_ms(50); assert(!reader.poll());
    input.level = false; assert(!reader.poll()); clock.advance_monotonic_ms(50); assert(reader.poll() == "1");
    assert(random.calls == 2);
    // Held at startup is ignored until a stable release, including at time zero.
    DevButtonReader boot_held(input, clock, random);
    assert(!boot_held.poll()); clock.advance_monotonic_ms(5000); assert(!boot_held.poll());
    input.level = true; assert(!boot_held.poll()); clock.advance_monotonic_ms(50); assert(!boot_held.poll());
    input.level = false; assert(!boot_held.poll()); clock.advance_monotonic_ms(50); assert(boot_held.poll() == "1");
}
void selection() {
    ManualClock clock; Input input; Random random; DevButtonReader reader(input, clock, random);
    for (unsigned value = 0; value < 255; ++value) {
        random.value = static_cast<std::uint8_t>(value);
        random.reject_once = value == 0; // 255 must be retried.
        input.level = true; assert(!reader.poll()); clock.advance_monotonic_ms(50); assert(!reader.poll());
        input.level = false; assert(!reader.poll()); clock.advance_monotonic_ms(50);
        const auto chip = reader.poll();
        assert(chip && *chip == std::to_string(value % 5 + 1));
        assert(!reader.poll());
    }
    assert(random.calls == 256);
}
void capture() {
    Cell id, log; ManualClock clock; Mode mode; Time time; Crypto crypto;
    FixedRandomSource identity_random; Random random; Input input;
    PersistentIdentity identity_store(id); PersistentObservations observations(log);
    NodeIdentityManager identity(identity_store, mode, identity_random, crypto, time);
    Core core(clock, observations, observations, identity, identity_random);
    assert(core.initialize() && !identity.claimed());
    const auto node = core.node_id(); const auto key = identity.identity().private_key;
    DevButtonReader reader(input, clock, random);
    auto sample = [&](bool high, std::uint64_t delay) {
        input.level = high; clock.advance_monotonic_ms(delay); return core.poll_reader(reader);
    };
    auto press = [&]() {
        assert(!sample(true, 0)); assert(!sample(true, 50));
        assert(!sample(false, 0)); return sample(false, 50);
    };
    assert(press() == RecordResult::REJECTED_UNCLAIMED);
    assert(core.observation_count() == 0 && core.next_sequence() == 1);
    assert(!sample(false, 5000)); // Denied press is consumed, not queued until claim.
    assert(core.record_chip_read("SYNTHETIC-MM-1") == RecordResult::REJECTED_UNCLAIMED);
    clock.set_wall_clock(ClockStatus::SYNCED, 1700000000000ULL);
    assert(press() == RecordResult::REJECTED_UNCLAIMED);
    assert(core.observation_count() == 0 && core.next_sequence() == 1);
    clock.set_wall_clock(ClockStatus::UNKNOWN, std::nullopt);
    crypto.claim = {identity.identity().node_id, identity.identity().public_key,
        "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", "Test", ""};
    assert(identity.apply_claim("fixture-receipt"));
    assert(!sample(false, 5000));
    assert(press() == RecordResult::RECORDED);
    assert(!sample(false, 500));
    assert(press() == RecordResult::DEBOUNCED);
    assert(core.next_sequence() == 2 && core.observation_count() == 1);
    random.value = 1; assert(press() == RecordResult::RECORDED);
    clock.advance_monotonic_ms(2000); random.value = 0;
    assert(press() == RecordResult::RECORDED);
    const auto records = core.load_observations();
    assert(records.size() == 3 && core.next_sequence() == 4 && core.ack_watermark() == 0);
    for (std::size_t i = 0; i < records.size(); ++i) {
        assert(records[i].sequence == i + 1 && records[i].node_id == node);
        assert(records[i].clock_status == ClockStatus::UNKNOWN && !records[i].observed_at_epoch_ms);
        assert(records[i].chip_id.value == (i == 1 ? "2" : "1"));
        std::size_t consumed = 0;
        const auto decoded = ble::decode_record(ble::encode_record(records[i]), consumed);
        assert(decoded && decoded->chip_id.value == records[i].chip_id.value);
    }
    // Reconstruct actual persistent adapters; unacknowledged wire bytes survive.
    PersistentIdentity reloaded_id(id); PersistentObservations reloaded_log(log);
    NodeIdentityManager reloaded_identity(reloaded_id, mode, identity_random, crypto, time);
    Core reboot(clock, reloaded_log, reloaded_log, reloaded_identity, identity_random);
    assert(reboot.initialize() && reboot.node_id() == node && reboot.next_sequence() == 4);
    assert(reloaded_identity.identity().private_key == key && reloaded_identity.claimed());
    assert(reboot.ack_watermark() == 0 && reboot.observation_count() == 3);
    for (std::size_t i = 0; i < records.size(); ++i)
        assert(ble::encode_record(reboot.load_observations()[i]) == ble::encode_record(records[i]));
    DevButtonReader after_reboot(input, clock, random);
    input.level = true; assert(!reboot.poll_reader(after_reboot));
    clock.advance_monotonic_ms(50); assert(!reboot.poll_reader(after_reboot));
    input.level = false; assert(!reboot.poll_reader(after_reboot));
    clock.advance_monotonic_ms(50); assert(reboot.poll_reader(after_reboot) == RecordResult::RECORDED);
    assert(reboot.load_observations().back().sequence == 4);
    // A failed capture is consumed, not retried while held.
    Core unavailable(clock, reloaded_log, reloaded_log, reloaded_identity, identity_random);
    input.level = true; assert(!unavailable.poll_reader(after_reboot));
    clock.advance_monotonic_ms(50); assert(!unavailable.poll_reader(after_reboot));
    input.level = false; assert(!unavailable.poll_reader(after_reboot));
    clock.advance_monotonic_ms(50); assert(unavailable.poll_reader(after_reboot) == RecordResult::NOT_READY);
    clock.advance_monotonic_ms(5000); assert(!unavailable.poll_reader(after_reboot));
}
}
int main() { debounce(); selection(); capture(); std::cout << "development button reader passed\n"; }
