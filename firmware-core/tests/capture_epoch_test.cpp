#include "claim_fixture.hpp"
#include "miezmerker/memory_stores.hpp"
#include <cassert>
#include <limits>
#include <iostream>
using namespace miezmerker;
namespace {
struct Storage : miezmerker::Storage { bool open() override { return true; } };
struct Reader : RfidReader {
    unsigned polls{0};
    std::optional<std::string> pending;
    std::optional<std::string> poll() override { ++polls; auto chip = std::move(pending); pending.reset(); return chip; }
};
struct Identity : DeviceIdentityStore {
    InMemoryIdentityStore cell;
    bool ambiguous{false}, invalid{false};
    IdentityLoadResult load(IdentityRecord& out) override {
        const auto result = cell.load(out);
        if (invalid) out.boot_counter = 0;
        return result;
    }
    bool store(const IdentityRecord& id) override { const auto ok = cell.store(id); return ok && !ambiguous; }
    bool erase() override { return cell.erase(); }
};
void capture_gate_and_epochs() {
    ManualClock clock; Storage storage; Identity identity; InMemoryObservationStore log;
    FixedRandomSource random; Reader reader;
    Core core(clock, storage, log, identity, random, CoreConfig{0});
    assert(core.initialize() && core.boot_counter() == 1);
    for (auto status : {ClockStatus::UNKNOWN, ClockStatus::SYNCED}) {
        clock.set_wall_clock(status, status == ClockStatus::UNKNOWN ? std::nullopt : std::optional<std::uint64_t>{1700000000000ULL});
        assert(core.record_chip_read("private-unclaimed-chip") == RecordResult::REJECTED_UNCLAIMED);
        assert(core.record_chip_read("SYNTHETIC-MM-1") == RecordResult::REJECTED_UNCLAIMED);
        reader.pending = "private-reader-chip";
        assert(core.poll_reader(reader) == RecordResult::REJECTED_UNCLAIMED);
        assert(!reader.pending && !core.poll_reader(reader));
        assert(core.next_sequence() == 1 && log.size() == 0 && log.append_attempts() == 0);
    }
    claim_capture_fixture(core, identity); // Host-only atomic claimed cell.
    clock.set_wall_clock(ClockStatus::UNKNOWN, std::nullopt);
    clock.set_monotonic_ms((1ULL << 40) + 100);
    assert(core.record_chip_read("claimed") == RecordResult::RECORDED);
    clock.advance_monotonic_ms(130);
    assert(core.record_chip_read("claimed") == RecordResult::RECORDED);
    auto records = core.load_observations();
    assert(records[1].monotonic_ms - records[0].monotonic_ms == 130);
    assert(records[0].clock_status == ClockStatus::UNKNOWN && !records[0].observed_at_epoch_ms);
    // Global discontinuity, even with a different chip and dedup disabled.
    clock.set_monotonic_ms(2);
    assert(core.record_chip_read("other") == RecordResult::NOT_READY && !core.ready());
    assert(core.initialize() && core.boot_counter() == 2);
    assert(core.record_chip_read("new-epoch") == RecordResult::RECORDED);
    clock.set_monotonic_ms(0); // Deep-sleep-like restart: new Core reserves new epoch.
    Core reboot(clock, storage, log, identity, random, CoreConfig{0});
    assert(reboot.initialize() && reboot.boot_counter() == 3);
    assert(reboot.record_chip_read("third") == RecordResult::RECORDED);
    records = reboot.load_observations();
    assert(records[0].boot_counter == 1 && records[2].boot_counter == 2 && records[3].boot_counter == 3);
    assert(records[0].monotonic_ms > records[3].monotonic_ms); // No cross-boot elapsed inference.
    clock.set_wall_clock(ClockStatus::SYNCED, 1700000000000ULL);
    assert(reboot.load_observations()[0].clock_status == ClockStatus::UNKNOWN);
    // Stale RAM ownership is not enough; durable transition takes effect immediately.
    IdentityRecord id; assert(identity.load(id) == IdentityLoadResult::OK);
    id.claim_state = ClaimState::UNCLAIMED; assert(identity.store(id));
    const auto next = reboot.next_sequence();
    assert(reboot.record_chip_read("revoked") == RecordResult::REJECTED_UNCLAIMED && reboot.next_sequence() == next);
    claim_capture_fixture(reboot, identity);
    assert(reboot.factory_reset());
    assert(reboot.record_chip_read("reset") == RecordResult::REJECTED_UNCLAIMED);
    assert(log.size() == 0 && reboot.next_sequence() == 1);
    // Corrupted persistent record and exhausted epoch both fail closed.
    identity.invalid = true; assert(reboot.record_chip_read("corrupt") == RecordResult::NOT_READY);
    identity.invalid = false; assert(identity.load(id) == IdentityLoadResult::OK);
    id.boot_counter = std::numeric_limits<std::uint32_t>::max(); assert(identity.store(id));
    assert(!reboot.initialize());
}
void ambiguous_reservation() {
    ManualClock clock; Storage storage; Identity identity; InMemoryObservationStore log; FixedRandomSource random;
    Core core(clock, storage, log, identity, random, CoreConfig{0});
    assert(core.initialize()); claim_capture_fixture(core, identity);
    identity.ambiguous = true;
    assert(core.record_chip_read("lost") == RecordResult::IO_ERROR);
    assert(core.record_chip_read("retry") == RecordResult::NOT_READY);
    identity.ambiguous = false;
    assert(core.initialize() && core.next_sequence() == 2);
    assert(core.record_chip_read("after") == RecordResult::RECORDED);
    assert(core.load_observations()[0].sequence == 2);
    // Before/during/after boot reservation: failures never enable capture.
    const auto before = core.boot_counter(); identity.ambiguous = true;
    assert(!core.initialize() && !core.ready());
    identity.ambiguous = false;
    assert(core.initialize() && core.boot_counter() == before + 2);
    identity.cell.set_fail_next_store(true);
    assert(!core.initialize());
    assert(core.record_chip_read("blocked") == RecordResult::NOT_READY);
    identity.cell.set_fail_next_load(true); assert(!core.initialize());
    assert(core.initialize());
}
}
int main() { capture_gate_and_epochs(); ambiguous_reservation(); std::cout << "capture gate and boot epochs passed\n"; }
