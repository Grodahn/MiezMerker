#include <cstdlib>
#include <iostream>
#include <limits>
#include <locale>
#include <string>

#include "miezmerker/core.hpp"
#include "miezmerker/memory_stores.hpp"
#include "miezmerker/protocol_view.hpp"

namespace {

int checks = 0;
#define CHECK(condition) do { ++checks; if (!(condition)) { \
    std::cerr << "FAIL " << __LINE__ << ": " << #condition << '\n'; std::exit(1); \
} } while (0)

struct PowerLoss {};
struct Storage final : miezmerker::Storage {
    bool open() override { return true; }
};

// Inject interruption AFTER a durable operation, as well as failures BEFORE
// it. Reboot reconstructs only Core; these cells represent durable media.
struct Identity final : miezmerker::DeviceIdentityStore {
    miezmerker::InMemoryIdentityStore cell;
    int stores = 0;
    int fail_at = 0;
    int crash_at = 0;
    bool invalid_load = false;
    miezmerker::IdentityLoadResult load(miezmerker::IdentityRecord& out) override {
        const auto result = cell.load(out);
        if (invalid_load && result == miezmerker::IdentityLoadResult::OK) out.next_sequence = 0;
        return result;
    }
    bool store(const miezmerker::IdentityRecord& record) override {
        ++stores;
        if (stores == fail_at) return false;
        const bool result = cell.store(record);
        if (stores == crash_at) throw PowerLoss{};
        return result;
    }
    bool erase() override { return cell.erase(); }
};

struct Observations final : miezmerker::ObservationStore {
    miezmerker::InMemoryObservationStore cell{20};
    bool crash_after_clear = false;
    bool crash_after_append = false;
    miezmerker::ManualClock* delay_status_clock = nullptr;
    miezmerker::PutResult append(const miezmerker::RawObservation& obs) override {
        const auto result = cell.append(obs);
        if (crash_after_append) {
            crash_after_append = false;
            throw PowerLoss{};
        }
        return result;
    }
    std::vector<miezmerker::RawObservation> load_all() override { return cell.load_all(); }
    std::size_t size() const override { return cell.size(); }
    std::size_t capacity() const override { return cell.capacity(); }
    miezmerker::StoreStatus status() const override {
        if (delay_status_clock != nullptr) {
            delay_status_clock->set_wall_clock(miezmerker::ClockStatus::SYNCED, 9999);
        }
        return cell.status();
    }
    std::uint64_t ack_watermark() const override { return cell.ack_watermark(); }
    bool set_ack_watermark(std::uint64_t sequence) override {
        return cell.set_ack_watermark(sequence);
    }
    bool prune_acked() override { return cell.prune_acked(); }
    bool clear() override {
        const bool result = cell.clear();
        if (crash_after_clear) {
            crash_after_clear = false;
            throw PowerLoss{};
        }
        return result;
    }
};

struct Fixture {
    Storage storage;
    miezmerker::ManualClock clock;
    Identity identity;
    Observations observations;
    miezmerker::FixedRandomSource random{0xA5};
    miezmerker::Core make_core() {
        return {clock, storage, observations, identity, random, miezmerker::CoreConfig{0}};
    }
    void seed() {
        auto core = make_core();
        CHECK(core.initialize());
        CHECK(core.record_chip_read("old") == miezmerker::RecordResult::RECORDED);
        CHECK(core.set_ack_watermark(1));
    }
};

void identity_read_errors() {
    Fixture f;
    f.seed();
    const auto before = f.observations.cell.load_all();
    f.identity.cell.set_fail_next_load(true);
    auto core = f.make_core();
    CHECK(!core.initialize());
    CHECK(!core.ready());
    CHECK(core.record_chip_read("blocked") == miezmerker::RecordResult::NOT_READY);
    CHECK(core.initialize());
    CHECK(core.node_id() == before[0].node_id);
    CHECK(core.next_sequence() == 2);
    CHECK(core.ack_watermark() == 1);
    f.identity.invalid_load = true;
    CHECK(!core.initialize());
    CHECK(!core.ready());
    CHECK(f.observations.size() == 1);
    f.identity.invalid_load = false;
    f.identity.cell.set_has_record(false);
    CHECK(!core.initialize());
    CHECK(!f.identity.cell.has_record());
    CHECK(f.observations.size() == 1);
    std::cout << "PASS identity_read_errors\n";
}

void reset_write_failures() {
    // Failure of the marker, the log clear, or the completion commit.
    for (int stage = 0; stage < 3; ++stage) {
        Fixture f;
        f.seed();
        const auto old_node = f.observations.cell.load_all()[0].node_id;
        auto core = f.make_core();
        CHECK(core.initialize());
        if (stage == 0) f.identity.fail_at = f.identity.stores + 1;
        if (stage == 1) f.observations.cell.set_fail_next_clear(true);
        if (stage == 2) f.identity.fail_at = f.identity.stores + 2;
        CHECK(!core.factory_reset());
        CHECK(!core.ready());
        CHECK(core.load_observations().empty());
        CHECK(core.record_chip_read("blocked") == miezmerker::RecordResult::NOT_READY);
        if (stage < 2) {
            CHECK(f.observations.size() == 1);
            CHECK(f.observations.ack_watermark() == 1);
        }
        auto reboot = f.make_core();
        CHECK(reboot.initialize());
        if (stage == 0) {
            CHECK(reboot.node_id() == old_node);
            CHECK(reboot.next_sequence() == 2);
            CHECK(reboot.observation_count() == 1);
        } else {
            CHECK(reboot.node_id() != old_node);
            CHECK(reboot.next_sequence() == 1);
            CHECK(reboot.observation_count() == 0);
            CHECK(reboot.ack_watermark() == 0);
        }
        CHECK(reboot.record_chip_read("after") == miezmerker::RecordResult::RECORDED);
    }
    std::cout << "PASS reset_write_failures\n";
}

void reset_power_loss() {
    for (int stage = 0; stage < 3; ++stage) {
        Fixture f;
        f.seed();
        const auto old_node = f.observations.cell.load_all()[0].node_id;
        auto core = f.make_core();
        CHECK(core.initialize());
        if (stage == 0) f.identity.crash_at = f.identity.stores + 1;
        if (stage == 1) f.observations.crash_after_clear = true;
        if (stage == 2) f.identity.crash_at = f.identity.stores + 2;
        bool crashed = false;
        try { core.factory_reset(); } catch (const PowerLoss&) { crashed = true; }
        CHECK(crashed);
        auto reboot = f.make_core();
        CHECK(reboot.initialize());
        CHECK(reboot.node_id() != old_node);
        CHECK(reboot.next_sequence() == 1);
        CHECK(reboot.observation_count() == 0);
        CHECK(reboot.ack_watermark() == 0);
        CHECK(reboot.record_chip_read("after") == miezmerker::RecordResult::RECORDED);
        miezmerker::IdentityRecord durable;
        CHECK(f.identity.load(durable) == miezmerker::IdentityLoadResult::OK);
        CHECK(!durable.reset_pending);
    }
    std::cout << "PASS reset_power_loss\n";
}

void reset_identity_collision() {
    Fixture f;
    f.seed();
    const auto old = f.observations.cell.load_all()[0];
    // Simulate a reboot restarting a broken/deterministic entropy stream.
    miezmerker::FixedRandomSource repeated{0xA5};
    miezmerker::Core core(f.clock, f.storage, f.observations, f.identity, repeated);
    CHECK(!core.factory_reset()); // also valid before initialize()
    CHECK(!core.ready());
    CHECK(f.observations.size() == 1);
    auto reboot = f.make_core();
    CHECK(reboot.initialize());
    CHECK(reboot.node_id() == old.node_id);
    CHECK(reboot.next_sequence() == 2);
    // An orphaned log must also prevent collision on explicit recovery/reset.
    f.identity.cell.set_has_record(false);
    miezmerker::FixedRandomSource orphan_repeat{0xA5};
    miezmerker::Core orphan(f.clock, f.storage, f.observations, f.identity, orphan_repeat);
    CHECK(!orphan.factory_reset());
    CHECK(f.observations.size() == 1);
    CHECK(reboot.factory_reset());
    CHECK(reboot.node_id() != old.node_id);
    struct ZeroEntropy final : miezmerker::RandomSource {
        void fill_random(std::span<std::uint8_t> out) override {
            for (auto& byte : out) byte = 0;
        }
    } zero;
    Fixture empty;
    miezmerker::Core no_entropy(empty.clock, empty.storage, empty.observations, empty.identity, zero);
    CHECK(!no_entropy.initialize());
    CHECK(!empty.identity.cell.has_record());
    CHECK(!no_entropy.ready());
    std::cout << "PASS reset_identity_collision\n";
}

void capture_power_loss() {
    for (int stage = 0; stage < 2; ++stage) {
        Fixture f;
        f.seed();
        auto core = f.make_core();
        CHECK(core.initialize());
        const auto node = core.node_id();
        if (stage == 0) f.identity.crash_at = f.identity.stores + 1;
        if (stage == 1) f.observations.crash_after_append = true;
        bool crashed = false;
        try { core.record_chip_read("interrupted"); } catch (const PowerLoss&) { crashed = true; }
        CHECK(crashed);
        auto reboot = f.make_core();
        CHECK(reboot.initialize());
        CHECK(reboot.node_id() == node);
        CHECK(reboot.observation_count() == (stage == 0 ? 1u : 2u));
        CHECK(reboot.next_sequence() == 3);
        CHECK(reboot.ack_watermark() == 1);
        CHECK(reboot.record_chip_read("after") == miezmerker::RecordResult::RECORDED);
        const auto all = reboot.load_observations();
        CHECK(all.front().sequence == 1);
        CHECK(all.back().sequence == 3);
        if (stage == 1) CHECK(all[1].sequence == 2);
    }
    std::cout << "PASS capture_power_loss\n";
}

void reconcile_node_lifetime() {
    Fixture f;
    f.seed();
    miezmerker::IdentityRecord durable;
    CHECK(f.identity.load(durable) == miezmerker::IdentityLoadResult::OK);
    auto obs = f.observations.cell.load_all()[0];
    obs.sequence = 42;
    obs.incarnation.bytes[0] ^= 1;
    CHECK(f.observations.append(obs) == miezmerker::PutResult::OK);
    durable.next_sequence = 1;
    CHECK(f.identity.store(durable));
    auto core = f.make_core();
    CHECK(core.initialize());
    CHECK(core.next_sequence() == 43);
    CHECK(core.record_chip_read("after") == miezmerker::RecordResult::RECORDED);
    obs.sequence = std::numeric_limits<miezmerker::Sequence>::max();
    CHECK(f.observations.append(obs) == miezmerker::PutResult::OK);
    CHECK(!core.initialize());
    CHECK(!core.ready());
    std::cout << "PASS reconcile_node_lifetime\n";
}

void acknowledgement_bounds() {
    Fixture f;
    auto core = f.make_core();
    CHECK(core.initialize());
    CHECK(!core.set_ack_watermark(1));
    CHECK(!f.observations.cell.set_ack_watermark(1));
    CHECK(core.record_chip_read("first") == miezmerker::RecordResult::RECORDED);
    CHECK(!core.set_ack_watermark(100));
    CHECK(!f.observations.cell.set_ack_watermark(100));
    CHECK(core.ack_watermark() == 0);
    f.observations.cell.set_fail_next_append(true);
    CHECK(core.record_chip_read("lost") == miezmerker::RecordResult::IO_ERROR);
    CHECK(!core.set_ack_watermark(2));
    CHECK(core.record_chip_read("third") == miezmerker::RecordResult::RECORDED);
    CHECK(core.set_ack_watermark(3)); // sequence gaps are valid
    CHECK(!core.set_ack_watermark(2));
    auto reboot = f.make_core();
    CHECK(reboot.initialize());
    CHECK(reboot.ack_watermark() == 3);
    CHECK(!reboot.set_ack_watermark(4));
    CHECK(reboot.record_chip_read("fourth") == miezmerker::RecordResult::RECORDED);
    f.observations.cell.set_fail_next_watermark(true);
    CHECK(!reboot.set_ack_watermark(4));
    CHECK(reboot.ack_watermark() == 3);
    CHECK(reboot.set_ack_watermark(4));
    std::cout << "PASS acknowledgement_bounds\n";
}

void capacity_rounding() {
    Fixture f;
    f.seed();
    auto obs = f.observations.cell.load_all()[0];
    for (const std::size_t capacity : {2u, 9u, 11u}) {
        miezmerker::InMemoryObservationStore store(capacity);
        for (std::size_t i = 1; i <= capacity; ++i) {
            obs.sequence = i;
            CHECK(store.append(obs) == miezmerker::PutResult::OK);
            const auto expected = i == capacity ? miezmerker::StoreStatus::FULL :
                (i * 10 >= capacity * 9 ? miezmerker::StoreStatus::NEARLY_FULL :
                                         miezmerker::StoreStatus::OK);
            CHECK(store.status() == expected);
        }
    }
    miezmerker::InMemoryObservationStore huge(std::numeric_limits<std::size_t>::max() / 9 + 1);
    CHECK(huge.append(obs) == miezmerker::PutResult::OK);
    CHECK(huge.status() == miezmerker::StoreStatus::OK);
    std::cout << "PASS capacity_rounding\n";
}

void metadata_validation_and_read_time() {
    Fixture f;
    f.seed();
    auto obs = f.observations.cell.load_all()[0];
    obs.clock_status = static_cast<miezmerker::ClockStatus>(255);
    obs.observed_at_epoch_ms = 1;
    CHECK(!obs.valid());
    CHECK(f.observations.append(obs) == miezmerker::PutResult::IO_ERROR);
    miezmerker::IdentityRecord durable;
    CHECK(f.identity.load(durable) == miezmerker::IdentityLoadResult::OK);
    durable.claim_state = static_cast<miezmerker::ClaimState>(255);
    CHECK(!durable.valid());
    CHECK(!f.identity.store(durable));
    f.clock.set_wall_clock(miezmerker::ClockStatus::RTC_ONLY, 1234);
    f.observations.delay_status_clock = &f.clock;
    auto core = f.make_core();
    CHECK(core.initialize());
    CHECK(core.record_chip_read("read-time") == miezmerker::RecordResult::RECORDED);
    const auto last = core.load_observations().back();
    CHECK(last.observed_at_epoch_ms == 1234);
    CHECK(last.clock_status == miezmerker::ClockStatus::RTC_ONLY);
    std::cout << "PASS metadata_validation_and_read_time\n";
}

struct GroupedNumbers final : std::numpunct<char> {
    char do_thousands_sep() const override { return ','; }
    std::string do_grouping() const override { return "\3"; }
};

void protocol_locale() {
    Fixture f;
    f.seed();
    auto obs = f.observations.cell.load_all()[0];
    obs.sequence = 1234567;
    obs.monotonic_ms = 7654321;
    const auto previous = std::locale::global(std::locale(std::locale::classic(), new GroupedNumbers));
    const auto json = miezmerker::observation_to_protocol_json(obs);
    std::locale::global(previous);
    CHECK(json.find("\"sequence\":\"1234567\"") != std::string::npos);
    CHECK(json.find("\"monotonic_ms\":\"7654321\"") != std::string::npos);
    std::cout << "PASS protocol_locale\n";
}

} // namespace

int main() {
    identity_read_errors();
    reset_write_failures();
    reset_power_loss();
    reset_identity_collision();
    capture_power_loss();
    reconcile_node_lifetime();
    acknowledgement_bounds();
    capacity_rounding();
    metadata_validation_and_read_time();
    protocol_locale();
    std::cout << "All recovery tests passed (" << checks << " checks)\n";
}
