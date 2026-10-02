// Deterministic firmware-core tests for issue #5.
//
// No hardware, no randomness, no wall-clock dependency: ManualClock,
// FixedRandomSource and in-memory stores simulate flash across reboot by
// outliving the Core object. A reboot is modelled by destroying the Core and
// constructing a new one over the same store objects.

#include <iostream>
#include <memory>
#include <set>
#include <string>
#include <tuple>
#include <utility>
#include <vector>

#include "miezmerker/core.hpp"
#include "miezmerker/memory_stores.hpp"
#include "miezmerker/protocol_view.hpp"

namespace {

int failures = 0;
int checks = 0;

#define CHECK(cond)                                                            \
    do {                                                                       \
        ++checks;                                                              \
        if (!(cond)) {                                                         \
            ++failures;                                                        \
            std::cout << "FAIL " << __LINE__ << ": " << #cond << "\n";         \
        }                                                                      \
    } while (0)

struct FakeStorage final : miezmerker::Storage {
    bool available{true};
    bool open() override { return available; }
};

struct Fixture {
    miezmerker::ManualClock clock;
    FakeStorage storage;
    miezmerker::InMemoryObservationStore observations{4096};
    miezmerker::InMemoryIdentityStore identity;
    miezmerker::FixedRandomSource random{0xA5};
    miezmerker::CoreConfig config;

    std::unique_ptr<miezmerker::Core> make_core() {
        return std::make_unique<miezmerker::Core>(clock, storage, observations, identity, random,
                                                 config);
    }
};

void sync_clock(miezmerker::ManualClock& clock, std::uint64_t mono, miezmerker::ClockStatus st,
                std::optional<std::uint64_t> epoch) {
    clock.set_monotonic_ms(mono);
    clock.set_wall_clock(st, epoch);
}

bool sequences_unique(const std::vector<miezmerker::RawObservation>& obs) {
    std::set<std::tuple<std::string, std::string, miezmerker::Sequence>> seen;
    for (const auto& o : obs) {
        auto key = std::make_tuple(o.node_id.to_string(), o.incarnation.to_string(), o.sequence);
        if (!seen.insert(key).second) return false;
    }
    return seen.empty() || seen.size() == obs.size();
}

void test_first_read() {
    Fixture f;
    sync_clock(f.clock, 1000, miezmerker::ClockStatus::SYNCED, 1700000000000ULL);
    auto core = f.make_core();
    CHECK(core->initialize());
    CHECK(core->ready());
    CHECK(core->next_sequence() == miezmerker::kFirstSequence);
    const auto r = core->record_chip_read("chip-A");
    CHECK(r == miezmerker::RecordResult::RECORDED);
    CHECK(core->observation_count() == 1u);
    CHECK(core->next_sequence() == 2ULL);
    const auto all = core->load_observations();
    CHECK(all.size() == 1u);
    CHECK(all[0].sequence == 1ULL);
    CHECK(all[0].chip_id.value == "chip-A");
    CHECK(all[0].clock_status == miezmerker::ClockStatus::SYNCED);
    CHECK(all[0].observed_at_epoch_ms.has_value());
    CHECK(*all[0].observed_at_epoch_ms == 1700000000000ULL);
    CHECK(all[0].monotonic_ms == 1000ULL);
    CHECK(all[0].node_id == core->node_id());
    CHECK(all[0].valid());
    CHECK(sequences_unique(all));
    std::cout << "PASS first_read\n";
}

void test_many_reads_same_chip_debounced() {
    Fixture f;
    f.config.debounce_interval_ms = 2000;
    sync_clock(f.clock, 0, miezmerker::ClockStatus::SYNCED, 1000ULL);
    auto core = f.make_core();
    CHECK(core->initialize());
    int recorded = 0;
    int debounced = 0;
    for (int i = 0; i < 10; ++i) {
        f.clock.set_monotonic_ms(static_cast<std::uint64_t>(i) * 100);  // 0..900ms
        const auto r = core->record_chip_read("same-chip");
        if (r == miezmerker::RecordResult::RECORDED)
            ++recorded;
        else if (r == miezmerker::RecordResult::DEBOUNCED)
            ++debounced;
        else
            CHECK(false);
    }
    CHECK(recorded == 1);
    CHECK(debounced == 9);
    CHECK(core->observation_count() == 1u);
    CHECK(core->next_sequence() == 2ULL);  // suppressed reads consume no sequence
    std::cout << "PASS many_same_chip\n";
}

void test_dedup_semantics() {
    Fixture f;
    f.config.debounce_interval_ms = 2000;
    sync_clock(f.clock, 0, miezmerker::ClockStatus::SYNCED, 5000ULL);
    auto core = f.make_core();
    CHECK(core->initialize());
    CHECK(core->record_chip_read("chip-X") == miezmerker::RecordResult::RECORDED);
    // Same chip inside window: suppressed, store untouched, sequence untouched.
    f.clock.advance_monotonic_ms(500);
    CHECK(core->record_chip_read("chip-X") == miezmerker::RecordResult::DEBOUNCED);
    CHECK(core->observation_count() == 1u);
    CHECK(core->next_sequence() == 2ULL);
    // Different chip in the same window: independent, always recorded.
    CHECK(core->record_chip_read("chip-Y") == miezmerker::RecordResult::RECORDED);
    CHECK(core->observation_count() == 2u);
    CHECK(core->next_sequence() == 3ULL);
    // Same chip after the window: new observation (sampling, not visit logic).
    f.clock.advance_monotonic_ms(2000);
    CHECK(core->record_chip_read("chip-X") == miezmerker::RecordResult::RECORDED);
    CHECK(core->observation_count() == 3u);
    // Suppression never deletes: all three records intact, sequences 1..3.
    const auto all = core->load_observations();
    CHECK(all.size() == 3u);
    CHECK(all[0].sequence == 1ULL && all[1].sequence == 2ULL && all[2].sequence == 3ULL);
    CHECK(all[0].chip_id.value == "chip-X");
    CHECK(all[1].chip_id.value == "chip-Y");
    CHECK(all[2].chip_id.value == "chip-X");
    std::cout << "PASS dedup_semantics\n";
}

void test_dedup_disabled_with_zero_interval() {
    Fixture f;
    f.config.debounce_interval_ms = 0;
    sync_clock(f.clock, 0, miezmerker::ClockStatus::SYNCED, 7ULL);
    auto core = f.make_core();
    CHECK(core->initialize());
    for (int i = 0; i < 3; ++i) {
        CHECK(core->record_chip_read("chip-Z") == miezmerker::RecordResult::RECORDED);
    }
    CHECK(core->observation_count() == 3u);
    std::cout << "PASS dedup_disabled\n";
}

void test_different_chips() {
    Fixture f;
    f.config.debounce_interval_ms = 2000;
    sync_clock(f.clock, 10, miezmerker::ClockStatus::SYNCED, 9000ULL);
    auto core = f.make_core();
    CHECK(core->initialize());
    CHECK(core->record_chip_read("chip-1") == miezmerker::RecordResult::RECORDED);
    CHECK(core->record_chip_read("chip-2") == miezmerker::RecordResult::RECORDED);
    CHECK(core->record_chip_read("chip-3") == miezmerker::RecordResult::RECORDED);
    const auto all = core->load_observations();
    CHECK(all.size() == 3u);
    CHECK(all[0].sequence == 1ULL && all[1].sequence == 2ULL && all[2].sequence == 3ULL);
    CHECK(sequences_unique(all));
    std::cout << "PASS different_chips\n";
}

void test_invalid_chip_rejected() {
    Fixture f;
    sync_clock(f.clock, 0, miezmerker::ClockStatus::SYNCED, 1ULL);
    auto core = f.make_core();
    CHECK(core->initialize());
    CHECK(core->record_chip_read("") == miezmerker::RecordResult::REJECTED_INVALID);
    CHECK(core->record_chip_read(std::string(65, 'a')) == miezmerker::RecordResult::REJECTED_INVALID);
    CHECK(core->observation_count() == 0u);
    CHECK(core->next_sequence() == 1ULL);
    std::cout << "PASS invalid_chip\n";
}

void test_reboot_between_reads() {
    Fixture f;
    f.config.debounce_interval_ms = 2000;
    sync_clock(f.clock, 100, miezmerker::ClockStatus::SYNCED, 100000ULL);
    auto core = f.make_core();
    CHECK(core->initialize());
    const auto node_before = core->node_id();
    const auto incarn_before = core->incarnation();
    const auto boot_before = core->boot_counter();
    CHECK(core->record_chip_read("chip-A") == miezmerker::RecordResult::RECORDED);
    core.reset();  // power cycle: RAM lost, stores survive
    f.clock.set_monotonic_ms(0);  // real reboot resets the monotonic clock
    f.clock.set_wall_clock(miezmerker::ClockStatus::SYNCED, 100500ULL);
    auto core2 = f.make_core();
    CHECK(core2->initialize());
    CHECK(core2->node_id() == node_before);  // normal reboot keeps identity
    CHECK(core2->incarnation() == incarn_before);
    CHECK(core2->boot_counter() == boot_before + 1);
    CHECK(core2->next_sequence() == 2ULL);
    // Debounce memory is RAM-only: the same chip right after reboot records.
    CHECK(core2->record_chip_read("chip-A") == miezmerker::RecordResult::RECORDED);
    const auto all = core2->load_observations();
    CHECK(all.size() == 2u);
    CHECK(all[0].sequence == 1ULL && all[1].sequence == 2ULL);
    CHECK(sequences_unique(all));
    std::cout << "PASS reboot_between_reads\n";
}

void test_sequence_persistence_across_reboot() {
    Fixture f;
    f.config.debounce_interval_ms = 0;
    sync_clock(f.clock, 0, miezmerker::ClockStatus::SYNCED, 42ULL);
    auto core = f.make_core();
    CHECK(core->initialize());
    for (int i = 0; i < 5; ++i) {
        f.clock.advance_monotonic_ms(10);
        CHECK(core->record_chip_read("chip-" + std::to_string(i)) ==
              miezmerker::RecordResult::RECORDED);
    }
    CHECK(core->next_sequence() == 6ULL);
    core.reset();
    auto core2 = f.make_core();
    CHECK(core2->initialize());
    CHECK(core2->next_sequence() == 6ULL);
    f.clock.advance_monotonic_ms(10);
    CHECK(core2->record_chip_read("chip-late") == miezmerker::RecordResult::RECORDED);
    const auto all = core2->load_observations();
    CHECK(all.size() == 6u);
    CHECK(all.back().sequence == 6ULL);
    CHECK(sequences_unique(all));
    std::cout << "PASS sequence_persistence\n";
}

void test_unknown_rtc() {
    Fixture f;
    f.config.debounce_interval_ms = 0;
    sync_clock(f.clock, 50, miezmerker::ClockStatus::UNKNOWN, std::nullopt);
    auto core = f.make_core();
    CHECK(core->initialize());
    CHECK(core->record_chip_read("chip-t") == miezmerker::RecordResult::RECORDED);
    const auto all = core->load_observations();
    CHECK(all.size() == 1u);
    CHECK(all[0].clock_status == miezmerker::ClockStatus::UNKNOWN);
    CHECK(!all[0].observed_at_epoch_ms.has_value());
    CHECK(all[0].monotonic_ms == 50ULL);
    CHECK(all[0].valid());
    // RTC_ONLY without an epoch value degrades to UNKNOWN (never pretend).
    f.clock.set_wall_clock(miezmerker::ClockStatus::SYNCED, std::nullopt);
    f.clock.advance_monotonic_ms(5);
    CHECK(core->record_chip_read("chip-u") == miezmerker::RecordResult::RECORDED);
    const auto all2 = core->load_observations();
    CHECK(all2.back().clock_status == miezmerker::ClockStatus::UNKNOWN);
    CHECK(!all2.back().observed_at_epoch_ms.has_value());
    std::cout << "PASS unknown_rtc\n";
}

void test_rtc_correction_future_only() {
    Fixture f;
    f.config.debounce_interval_ms = 0;
    sync_clock(f.clock, 0, miezmerker::ClockStatus::RTC_ONLY, 1000ULL);
    auto core = f.make_core();
    CHECK(core->initialize());
    CHECK(core->record_chip_read("chip-1") == miezmerker::RecordResult::RECORDED);
    // App-Sync corrects the RTC for future reads.
    f.clock.set_wall_clock(miezmerker::ClockStatus::SYNCED, 1700000000000ULL);
    f.clock.advance_monotonic_ms(100);
    CHECK(core->record_chip_read("chip-2") == miezmerker::RecordResult::RECORDED);
    const auto all = core->load_observations();
    CHECK(all.size() == 2u);
    // Old record is byte-identical: never rewritten or redated.
    CHECK(all[0].clock_status == miezmerker::ClockStatus::RTC_ONLY);
    CHECK(all[0].observed_at_epoch_ms.has_value());
    CHECK(*all[0].observed_at_epoch_ms == 1000ULL);
    CHECK(all[1].clock_status == miezmerker::ClockStatus::SYNCED);
    CHECK(*all[1].observed_at_epoch_ms == 1700000000000ULL);
    // Still unchanged after another reboot.
    core.reset();
    auto core2 = f.make_core();
    CHECK(core2->initialize());
    const auto reloaded = core2->load_observations();
    CHECK(reloaded.size() == 2u);
    CHECK(reloaded[0].observed_at_epoch_ms == all[0].observed_at_epoch_ms);
    CHECK(reloaded[0].clock_status == all[0].clock_status);
    CHECK(reloaded[1].observed_at_epoch_ms == all[1].observed_at_epoch_ms);
    std::cout << "PASS rtc_correction_future_only\n";
}

void test_power_loss_during_append_keeps_store_intact() {
    Fixture f;
    f.config.debounce_interval_ms = 0;
    sync_clock(f.clock, 0, miezmerker::ClockStatus::SYNCED, 11ULL);
    auto core = f.make_core();
    CHECK(core->initialize());
    CHECK(core->record_chip_read("chip-1") == miezmerker::RecordResult::RECORDED);
    CHECK(core->observation_count() == 1u);
    // Next append dies mid-write: nothing committed, earlier records intact.
    f.observations.set_fail_next_append(true);
    f.clock.advance_monotonic_ms(10);
    CHECK(core->record_chip_read("chip-2") == miezmerker::RecordResult::IO_ERROR);
    CHECK(core->observation_count() == 1u);
    const auto kept = core->load_observations();
    CHECK(kept.size() == 1 && kept[0].chip_id.value == "chip-1");
    // Reserve-then-append skipped the lost sequence: uniqueness over gaplessness.
    CHECK(core->next_sequence() == 3ULL);
    f.clock.advance_monotonic_ms(10);
    CHECK(core->record_chip_read("chip-3") == miezmerker::RecordResult::RECORDED);
    const auto all = core->load_observations();
    CHECK(all.size() == 2u);
    CHECK(all[0].sequence == 1ULL && all[1].sequence == 3ULL);
    CHECK(sequences_unique(all));
    // And the gap survives a reboot without reuse.
    core.reset();
    auto core2 = f.make_core();
    CHECK(core2->initialize());
    CHECK(core2->next_sequence() == 4ULL);
    f.clock.advance_monotonic_ms(10);
    CHECK(core2->record_chip_read("chip-4") == miezmerker::RecordResult::RECORDED);
    const auto all2 = core2->load_observations();
    CHECK(all2.back().sequence == 4ULL);
    CHECK(sequences_unique(all2));
    std::cout << "PASS power_loss_append\n";
}

void test_power_loss_around_sequence_allocation() {
    Fixture f;
    f.config.debounce_interval_ms = 0;
    sync_clock(f.clock, 0, miezmerker::ClockStatus::SYNCED, 22ULL);
    auto core = f.make_core();
    CHECK(core->initialize());
    CHECK(core->record_chip_read("chip-1") == miezmerker::RecordResult::RECORDED);
    // Identity write dies before the observation exists: no record, no gap.
    f.identity.set_fail_next_store(true);
    f.clock.advance_monotonic_ms(10);
    CHECK(core->record_chip_read("chip-2") == miezmerker::RecordResult::IO_ERROR);
    CHECK(core->observation_count() == 1u);
    CHECK(core->next_sequence() == 2ULL);
    // Retry reuses the same sequence safely (nothing was persisted for it).
    f.clock.advance_monotonic_ms(10);
    CHECK(core->record_chip_read("chip-2") == miezmerker::RecordResult::RECORDED);
    const auto all = core->load_observations();
    CHECK(all.size() == 2u);
    CHECK(all[1].sequence == 2ULL);
    CHECK(sequences_unique(all));
    std::cout << "PASS power_loss_sequence_alloc\n";
}

void test_pending_observations_survive_reboot() {
    Fixture f;
    f.config.debounce_interval_ms = 0;
    sync_clock(f.clock, 0, miezmerker::ClockStatus::SYNCED, 33ULL);
    auto core = f.make_core();
    CHECK(core->initialize());
    CHECK(core->record_chip_read("chip-1") == miezmerker::RecordResult::RECORDED);
    CHECK(core->record_chip_read("chip-2") == miezmerker::RecordResult::RECORDED);
    CHECK(core->set_ack_watermark(1));  // first synced, second still pending
    core.reset();
    auto core2 = f.make_core();
    CHECK(core2->initialize());
    // Reboot preserves persisted observations AND the ack cursor.
    const auto all = core2->load_observations();
    CHECK(all.size() == 2u);
    CHECK(all[0].sequence == 1ULL && all[1].sequence == 2ULL);
    CHECK(core2->ack_watermark() == 1ULL);
    // Watermark is monotonic: going backwards is rejected.
    CHECK(!core2->set_ack_watermark(0));
    CHECK(core2->set_ack_watermark(2));
    CHECK(core2->ack_watermark() == 2ULL);
    std::cout << "PASS pending_after_reboot\n";
}

void test_store_nearly_full_and_full() {
    Fixture f;
    f.observations = miezmerker::InMemoryObservationStore(10);
    f.config.debounce_interval_ms = 0;
    sync_clock(f.clock, 0, miezmerker::ClockStatus::SYNCED, 44ULL);
    auto core = f.make_core();
    CHECK(core->initialize());
    CHECK(core->store_status() == miezmerker::StoreStatus::OK);
    for (int i = 0; i < 8; ++i) {
        f.clock.advance_monotonic_ms(1);
        CHECK(core->record_chip_read("chip-" + std::to_string(i)) ==
              miezmerker::RecordResult::RECORDED);
    }
    CHECK(core->store_status() == miezmerker::StoreStatus::OK);
    f.clock.advance_monotonic_ms(1);
    CHECK(core->record_chip_read("chip-8") == miezmerker::RecordResult::RECORDED);
    CHECK(core->store_status() == miezmerker::StoreStatus::NEARLY_FULL);
    // NEARLY_FULL still accepts writes.
    f.clock.advance_monotonic_ms(1);
    CHECK(core->record_chip_read("chip-9") == miezmerker::RecordResult::RECORDED);
    CHECK(core->store_status() == miezmerker::StoreStatus::FULL);
    CHECK(core->observation_count() == 10u);
    // FULL rejects without deleting or overwriting anything.
    const auto seq_before = core->next_sequence();
    f.clock.advance_monotonic_ms(1);
    CHECK(core->record_chip_read("chip-overflow") == miezmerker::RecordResult::REJECTED_FULL);
    CHECK(core->observation_count() == 10u);
    CHECK(core->next_sequence() == seq_before);  // pre-check avoids a gap
    const auto all = core->load_observations();
    CHECK(all.size() == 10u);
    for (const auto& o : all) CHECK(o.chip_id.value != "chip-overflow");
    CHECK(sequences_unique(all));
    // Acknowledging does NOT silently free space in #5 (compaction is #6).
    CHECK(core->set_ack_watermark(10));
    CHECK(core->store_status() == miezmerker::StoreStatus::FULL);
    CHECK(core->observation_count() == 10u);
    std::cout << "PASS store_full\n";
}

void test_no_silent_deletion() {
    Fixture f;
    f.config.debounce_interval_ms = 0;
    sync_clock(f.clock, 0, miezmerker::ClockStatus::RTC_ONLY, 55ULL);
    auto core = f.make_core();
    CHECK(core->initialize());
    CHECK(core->record_chip_read("chip-a") == miezmerker::RecordResult::RECORDED);
    CHECK(core->record_chip_read("chip-b") == miezmerker::RecordResult::RECORDED);
    const auto before = core->load_observations();
    // Failed and rejected reads must never mutate persisted data.
    f.observations.set_fail_next_append(true);
    CHECK(core->record_chip_read("chip-c") == miezmerker::RecordResult::IO_ERROR);
    CHECK(core->record_chip_read("") == miezmerker::RecordResult::REJECTED_INVALID);
    const auto after = core->load_observations();
    CHECK(after.size() == before.size());
    for (std::size_t i = 0; i < before.size(); ++i) {
        CHECK(after[i].sequence == before[i].sequence);
        CHECK(after[i].chip_id == before[i].chip_id);
        CHECK(after[i].observed_at_epoch_ms == before[i].observed_at_epoch_ms);
    }
    std::cout << "PASS no_silent_deletion\n";
}

void test_factory_reset_new_identity() {
    Fixture f;
    f.config.debounce_interval_ms = 0;
    sync_clock(f.clock, 0, miezmerker::ClockStatus::SYNCED, 66ULL);
    auto core = f.make_core();
    CHECK(core->initialize());
    CHECK(core->record_chip_read("chip-1") == miezmerker::RecordResult::RECORDED);
    CHECK(core->record_chip_read("chip-2") == miezmerker::RecordResult::RECORDED);
    const auto old_node = core->node_id();
    const auto old_incarnation = core->incarnation();
    CHECK(core->factory_reset());
    CHECK(core->node_id() != old_node);  // same node_id + reset counter is forbidden
    CHECK(core->incarnation() != old_incarnation);
    CHECK(core->next_sequence() == miezmerker::kFirstSequence);
    CHECK(core->observation_count() == 0u);  // old lifetime does not leak into the new one
    CHECK(core->ack_watermark() == 0ULL);
    f.clock.advance_monotonic_ms(10);
    CHECK(core->record_chip_read("chip-new") == miezmerker::RecordResult::RECORDED);
    const auto all = core->load_observations();
    CHECK(all.size() == 1u);
    CHECK(all[0].sequence == 1ULL);
    CHECK(all[0].node_id == core->node_id());
    CHECK(all[0].node_id != old_node);
    std::cout << "PASS factory_reset\n";
}

void test_normal_reboot_keeps_identity() {
    Fixture f;
    sync_clock(f.clock, 5, miezmerker::ClockStatus::SYNCED, 77ULL);
    auto core = f.make_core();
    CHECK(core->initialize());
    const auto node = core->node_id();
    const auto incarnation = core->incarnation();
    for (int i = 0; i < 3; ++i) {
        core.reset();
        auto rebooted = f.make_core();
        CHECK(rebooted->initialize());
        CHECK(rebooted->node_id() == node);
        CHECK(rebooted->incarnation() == incarnation);
        core = std::move(rebooted);
    }
    std::cout << "PASS reboot_keeps_identity\n";
}

void test_poll_reader() {
    struct QueueReader final : miezmerker::RfidReader {
        std::vector<std::string> queue;
        std::optional<std::string> poll() override {
            if (queue.empty()) return std::nullopt;
            std::string next = queue.front();
            queue.erase(queue.begin());
            return next;
        }
    };
    Fixture f;
    f.config.debounce_interval_ms = 0;
    sync_clock(f.clock, 0, miezmerker::ClockStatus::SYNCED, 88ULL);
    auto core = f.make_core();
    CHECK(core->initialize());
    QueueReader reader;
    CHECK(!core->poll_reader(reader).has_value());  // no tag present
    reader.queue.push_back("chip-poll");
    const auto result = core->poll_reader(reader);
    CHECK(result.has_value());
    CHECK(*result == miezmerker::RecordResult::RECORDED);
    CHECK(core->observation_count() == 1u);
    std::cout << "PASS poll_reader\n";
}

void test_protocol_view_mapping() {
    Fixture f;
    f.config.debounce_interval_ms = 0;
    sync_clock(f.clock, 12, miezmerker::ClockStatus::SYNCED, 123456789ULL);
    auto core = f.make_core();
    CHECK(core->initialize());
    CHECK(core->record_chip_read("276098106000001") == miezmerker::RecordResult::RECORDED);
    f.clock.set_wall_clock(miezmerker::ClockStatus::RTC_ONLY, 999ULL);
    f.clock.advance_monotonic_ms(5);
    CHECK(core->record_chip_read("276098106000002") == miezmerker::RecordResult::RECORDED);
    f.clock.set_wall_clock(miezmerker::ClockStatus::UNKNOWN, std::nullopt);
    f.clock.advance_monotonic_ms(5);
    CHECK(core->record_chip_read("276098106000003") == miezmerker::RecordResult::RECORDED);
    const auto all = core->load_observations();
    CHECK(all.size() == 3u);
    const std::string synced = miezmerker::observation_to_protocol_json(all[0]);
    const std::string rtc_only = miezmerker::observation_to_protocol_json(all[1]);
    const std::string unknown = miezmerker::observation_to_protocol_json(all[2]);
    // SYNCED and RTC_ONLY both map to "known" with a decimal timestamp string.
    CHECK(synced.find("\"clock_status\":\"known\"") != std::string::npos);
    CHECK(synced.find("\"observed_at_epoch_ms\":\"123456789\"") != std::string::npos);
    CHECK(rtc_only.find("\"clock_status\":\"known\"") != std::string::npos);
    CHECK(rtc_only.find("\"observed_at_epoch_ms\":\"999\"") != std::string::npos);
    // UNKNOWN maps to null timestamp.
    CHECK(unknown.find("\"clock_status\":\"unknown\"") != std::string::npos);
    CHECK(unknown.find("\"observed_at_epoch_ms\":null") != std::string::npos);
    // 64-bit values stay decimal strings; identity fields are nonempty.
    CHECK(synced.find("\"sequence\":\"1\"") != std::string::npos);
    CHECK(synced.find("\"monotonic_ms\":\"12\"") != std::string::npos);
    CHECK(synced.find("\"protocol_version\":1") != std::string::npos);
    std::cout << "PASS protocol_view\n";
}

void test_store_status_small_capacity() {
    miezmerker::InMemoryObservationStore store(1);
    // An empty capacity-1 store is OK, not NEARLY_FULL (threshold would be 0).
    CHECK(store.status() == miezmerker::StoreStatus::OK);
    miezmerker::RawObservation obs{};
    const auto node = miezmerker::NodeId::parse("11121314-1516-4718-991a-1b1c1d1e1f20");
    const auto incarn = miezmerker::IncarnationId::parse("21222324-2526-4728-a92a-2b2c2d2e2f30");
    CHECK(node.has_value() && incarn.has_value());
    obs.node_id = *node;
    obs.incarnation = *incarn;
    obs.sequence = 1;
    obs.chip_id.value = "chip-1";
    obs.clock_status = miezmerker::ClockStatus::SYNCED;
    obs.observed_at_epoch_ms = 100;
    obs.monotonic_ms = 1;
    CHECK(store.append(obs) == miezmerker::PutResult::OK);
    CHECK(store.status() == miezmerker::StoreStatus::FULL);
    std::cout << "PASS store_status_small_capacity\n";
}

void test_protocol_view_escaping() {
    miezmerker::RawObservation obs{};
    const auto node = miezmerker::NodeId::parse("11121314-1516-4718-991a-1b1c1d1e1f20");
    const auto incarn = miezmerker::IncarnationId::parse("21222324-2526-4728-a92a-2b2c2d2e2f30");
    CHECK(node.has_value() && incarn.has_value());
    obs.node_id = *node;
    obs.incarnation = *incarn;
    obs.sequence = 1;
    obs.chip_id.value = "chip\"with\\control\x01";
    obs.clock_status = miezmerker::ClockStatus::SYNCED;
    obs.observed_at_epoch_ms = 42;
    obs.monotonic_ms = 7;
    const std::string json = miezmerker::observation_to_protocol_json(obs);
    CHECK(json.find("\\\"") != std::string::npos);
    CHECK(json.find("\\\\") != std::string::npos);
    CHECK(json.find("\\u0001") != std::string::npos);
    std::cout << "PASS protocol_view_escaping\n";
}

void test_uuid_format_and_claim_placeholder() {
    Fixture f;
    sync_clock(f.clock, 0, miezmerker::ClockStatus::UNKNOWN, std::nullopt);
    auto core = f.make_core();
    CHECK(core->initialize());
    const std::string node = core->node_id().to_string();
    const std::string incarnation = core->incarnation().to_string();
    CHECK(node.size() == 36 && incarnation.size() == 36u);
    CHECK(node[8] == '-' && node[13] == '-' && node[18] == '-' && node[23] == '-');
    CHECK(miezmerker::NodeId::parse(node).has_value());
    CHECK(miezmerker::IncarnationId::parse(incarnation).has_value());
    CHECK(!miezmerker::NodeId::parse("not-a-uuid").has_value());
    // #18 placeholder: fresh nodes start unclaimed; the core persists the flag.
    CHECK(core->claim_state() == miezmerker::ClaimState::UNCLAIMED);
    std::cout << "PASS uuid_claim\n";
}

}  // namespace

int main() {
    test_first_read();
    test_many_reads_same_chip_debounced();
    test_dedup_semantics();
    test_dedup_disabled_with_zero_interval();
    test_different_chips();
    test_invalid_chip_rejected();
    test_reboot_between_reads();
    test_sequence_persistence_across_reboot();
    test_unknown_rtc();
    test_rtc_correction_future_only();
    test_power_loss_during_append_keeps_store_intact();
    test_power_loss_around_sequence_allocation();
    test_pending_observations_survive_reboot();
    test_store_nearly_full_and_full();
    test_no_silent_deletion();
    test_factory_reset_new_identity();
    test_normal_reboot_keeps_identity();
    test_poll_reader();
    test_protocol_view_mapping();
    test_store_status_small_capacity();
    test_protocol_view_escaping();
    test_uuid_format_and_claim_placeholder();
    if (failures == 0) {
        std::cout << "All observation tests passed (" << checks << " checks)\n";
        return 0;
    }
    std::cout << failures << " failures / " << checks << " checks\n";
    return 1;
}
