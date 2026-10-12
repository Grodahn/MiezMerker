#include "miezmerker/persistent_state.hpp"
#include "miezmerker/core.hpp"
#include "miezmerker/memory_stores.hpp"
#include "../../firmware-core/tests/claim_fixture.hpp"
#include "miezmerker/ble_codec.hpp"
#include <cassert>
#include <iostream>
using namespace miezmerker;
using namespace miezmerker::esp32;
namespace {
struct Cell : AtomicBlob {
    Bytes data; unsigned writes{0}; bool before{false}, after{false}, error{false};
    BlobResult read(Bytes& out) override { out = data; return error ? BlobResult::error : data.empty() ? BlobResult::missing : BlobResult::loaded; }
    bool replace(const Bytes& bytes) override {
        ++writes; if (before) { before = false; return false; }
        data = bytes; if (after) { after = false; return false; } return true;
    }
};
struct Storage : miezmerker::Storage { bool open() override { return true; } };
struct Clock : miezmerker::Clock {
    std::uint64_t now{0}; bool valid{true}; std::uint32_t reason{1};
    std::uint64_t monotonic_ms() const override { return now; }
    WallClockReading wall_clock() const override { return {}; }
    std::uint32_t reset_reason() const override { return reason; }
    bool monotonic_valid() const override { return valid; }
};
TimeAnchorV1 anchor(const BootEpochV1& b) {
    TimeAnchorV1 a; a.anchor_id.bytes.fill(1); a.node_id = b.node_id; a.incarnation = b.incarnation;
    a.boot_counter = b.boot_counter; a.uptime_ms = 200; a.utc_ms = 1700000000000LL;
    a.uncertainty_minus_ms = 100; a.uncertainty_plus_ms = 400; a.drift_ppm_bound = 50;
    a.proof_id_or_digest = "deterministic-proof"; a.proof = "opaque-unverified-proof";
    a.acceptance = std::string("opaque\0unverified-acceptance", 26);
    a.proof += std::string("\0exact", 6);
    return a;
}
void evidence_lifecycle() {
    Cell cell; PersistentTimeEvidence evidence(cell); Storage storage; Clock clock;
    InMemoryObservationStore log; InMemoryIdentityStore identity; FixedRandomSource random;
    Core core(clock, storage, log, identity, random, CoreConfig{0, &evidence});
    assert(core.initialize() && evidence.boots().size() == 1);
    const auto b = evidence.boots().front(); assert(b.boot_counter == 1 && b.reset_reason == 1);
    auto writes = cell.writes;
    assert(evidence.append_boot(b) == PutResult::OK && cell.writes == writes);
    auto conflict = b; ++conflict.reset_reason; assert(evidence.append_boot(conflict) == PutResult::IO_ERROR);
    claim_capture_fixture(core, identity); clock.now = 100;
    assert(core.record_chip_read("one") == RecordResult::RECORDED);
    const auto original = ble::encode_record(core.load_observations().front());
    assert(core.set_ack_watermark(1) && core.compact_acked()); // Anchor need not exist yet.
    assert(log.size() == 0 && evidence.boots().size() == 1);
    auto a = anchor(b); assert(evidence.append_anchor(a) == PutResult::OK);
    writes = cell.writes; assert(evidence.append_anchor(a) == PutResult::OK && cell.writes == writes);
    auto altered = a; ++altered.utc_ms; assert(evidence.append_anchor(altered) == PutResult::IO_ERROR);
    altered = a; altered.anchor_id.bytes[0] = 2; altered.verified_security_time = true;
    assert(evidence.append_anchor(altered) == PutResult::IO_ERROR);
    altered.verified_security_time = false; ++altered.boot_counter;
    assert(evidence.append_anchor(altered) == PutResult::IO_ERROR);
    clock.reason = 8; clock.now = 0; assert(core.initialize()); // Deep sleep/new timeline.
    assert(evidence.boots().size() == 2 && evidence.boots().back().reset_reason == 8);
    assert(evidence.anchors().front() == a);
    assert(core.record_chip_read("two") == RecordResult::RECORDED);
    assert(core.load_observations().front().boot_counter == 2);
    // Stored evidence never changes clocks or old raw wire payloads.
    std::size_t consumed = 0; auto decoded = ble::decode_record(original, consumed);
    assert(decoded && decoded->clock_status == ClockStatus::UNKNOWN && !decoded->observed_at_epoch_ms);
    PersistentTimeEvidence reload(cell); assert(reload.open() && reload.anchors().front() == a);
    assert(core.factory_reset() && evidence.anchors().empty() && evidence.boots().size() == 1);
    assert(evidence.boots().front().node_id != b.node_id);
    assert(core.record_chip_read("reset") == RecordResult::REJECTED_UNCLAIMED);
    cell.data.back() ^= 1;
    const auto damaged = cell.data;
    assert(!reload.open() && cell.data == damaged); // No automatic erase.
    assert(reload.append_anchor(a) == PutResult::IO_ERROR);
}
void crash_boundaries() {
    for (bool committed : {false, true}) {
        Cell cell; PersistentTimeEvidence evidence(cell); Storage storage; Clock clock;
        InMemoryObservationStore log; InMemoryIdentityStore identity; FixedRandomSource random;
        Core core(clock, storage, log, identity, random, CoreConfig{0, &evidence});
        if (committed) cell.after = true; else cell.before = true;
        assert(!core.initialize() && !core.ready()); // Identity reserved, marker uncertain.
        assert(core.record_chip_read("blocked") == RecordResult::NOT_READY);
        assert(core.initialize() && core.boot_counter() == 2);
        assert(evidence.boots().size() == (committed ? 2U : 1U));
        auto a = anchor(evidence.boots().back());
        if (committed) cell.after = true; else cell.before = true;
        assert(evidence.append_anchor(a) == PutResult::IO_ERROR);
        assert(evidence.append_anchor(a) == PutResult::IO_ERROR); // Failure latch.
        assert(evidence.open() && evidence.anchors().size() == (committed ? 1U : 0U));
        assert(evidence.append_anchor(a) == PutResult::OK);
        // Interrupted factory-reset evidence purge remains journalled.
        if (committed) cell.after = true; else cell.before = true;
        assert(!core.factory_reset() && !core.ready());
        assert(core.initialize());
        assert(evidence.anchors().empty() && evidence.boots().size() == 1);
        assert(core.record_chip_read("reset") == RecordResult::REJECTED_UNCLAIMED);
    }
}
void bounded_storage_and_corruption() {
    Cell cell; PersistentTimeEvidence evidence(cell); assert(evidence.open());
    BootEpochV1 b; b.node_id.bytes.fill(2); b.incarnation.bytes.fill(3);
    for (unsigned i = 1; i <= PersistentTimeEvidence::kMaxBoots; ++i) { b.boot_counter = i; assert(evidence.append_boot(b) == PutResult::OK); }
    const auto saved = cell.data; ++b.boot_counter;
    assert(evidence.append_boot(b) == PutResult::FULL && cell.data == saved);
    auto a = anchor(evidence.boots().back()); a.proof.assign(4096, 'x'); a.acceptance.assign(512, 'a');
    // Byte budget fails closed before overwriting evidence, even below count cap.
    assert(evidence.append_anchor(a) == PutResult::OK);
    auto result = PutResult::OK;
    for (unsigned i = 2; i < 17 && result == PutResult::OK; ++i) { a.anchor_id.bytes[0] = static_cast<std::uint8_t>(i); result = evidence.append_anchor(a); }
    assert(result == PutResult::FULL);
    const auto prior = cell.data;
    cell.data.resize(9); assert(!evidence.open()); cell.data = prior;
    cell.error = true; assert(!evidence.open() && cell.data == prior);
    cell.error = false; assert(evidence.open());
}
void invalid_timer_and_rollback() {
    Cell cell; PersistentTimeEvidence evidence(cell); Storage storage; Clock clock;
    InMemoryObservationStore log; InMemoryIdentityStore identity; FixedRandomSource random;
    Core core(clock, storage, log, identity, random, CoreConfig{0, &evidence});
    clock.valid = false;
    assert(!core.initialize() && !identity.has_record() && cell.writes == 0);
    assert(core.record_chip_read("overflow") == RecordResult::NOT_READY);
    clock.valid = true; assert(core.initialize()); claim_capture_fixture(core, identity);
    assert(core.record_chip_read("one") == RecordResult::RECORDED);
    const auto next = core.next_sequence(); clock.now = 100; clock.valid = false;
    assert(core.record_chip_read("invalid-timer") == RecordResult::NOT_READY);
    assert(core.next_sequence() == next && log.size() == 1);
    const auto writes = cell.writes; assert(!core.initialize() && cell.writes == writes);
    clock.valid = true; assert(core.initialize() && core.boot_counter() == 2);
    // A valid but rolled-back identity cannot reuse persisted evidence epoch 2.
    IdentityRecord id; assert(identity.load(id) == IdentityLoadResult::OK);
    id.boot_counter = 1; assert(identity.store(id));
    assert(!core.initialize() && !core.ready());
    assert(core.record_chip_read("reused-epoch") == RecordResult::NOT_READY);
    assert(evidence.boots().size() == 2 && log.size() == 1);
}
void missing_identity_with_retained_evidence() {
    for (bool with_anchor : {false, true}) {
        Cell cell; PersistentTimeEvidence evidence(cell); Storage storage; Clock clock;
        InMemoryObservationStore log; InMemoryIdentityStore identity; FixedRandomSource random;
        Core core(clock, storage, log, identity, random, CoreConfig{0, &evidence});
        assert(core.initialize()); // Truly empty first boot remains supported.
        if (with_anchor) assert(evidence.append_anchor(anchor(evidence.boots().front())) == PutResult::OK);
        assert(log.size() == 0 && log.ack_watermark() == 0);
        const auto saved = cell.data;
        const auto writes = cell.writes;
        const auto identity_writes = identity.store_attempts();
        identity.set_has_record(false); // Missing key/identity blob despite durable lifetime evidence.
        assert(!core.initialize() && !core.ready());
        assert(!identity.has_record() && identity.store_attempts() == identity_writes);
        assert(cell.writes == writes && cell.data == saved);
        assert(core.record_chip_read("orphan") == RecordResult::NOT_READY);
    }
}
}
int main() { evidence_lifecycle(); crash_boundaries(); bounded_storage_and_corruption(); invalid_timer_and_rollback(); missing_identity_with_retained_evidence(); std::cout << "time evidence durability passed\n"; }
