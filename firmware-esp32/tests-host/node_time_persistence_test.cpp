// Verified time-evidence persistence compatibility (#130).
//
// Byte-exact storage (including embedded NUL), idempotent retries,
// conflicting-anchor rejection, power-loss recovery, bounded capacity,
// raw-compaction independence and factory-reset revocation. Uses the
// verifier-owned append_verified path; arbitrary verified writes via
// append_anchor remain rejected (see node-time portable tests).
#include <cassert>
#include <iostream>

#include "miezmerker/core.hpp"
#include "miezmerker/memory_stores.hpp"
#include "miezmerker/node_time.hpp"
#include "miezmerker/persistent_state.hpp"

using namespace miezmerker;
using namespace miezmerker::esp32;

namespace {
struct Cell : AtomicBlob {
    Bytes data;
    unsigned writes{0};
    bool before{false}, after{false}, error{false};
    BlobResult read(Bytes& out) override {
        if (error) return BlobResult::error;
        if (data.empty()) return BlobResult::missing;
        out = data;
        return BlobResult::loaded;
    }
    bool replace(const Bytes& value) override {
        ++writes;
        if (before) {
            before = false;
            return false;
        }
        data = value;
        if (after) {
            after = false;
            return false;
        }
        return true;
    }
};
NodeId node_id() {
    auto id = NodeId::parse("44444444-4444-4444-8444-444444444444");
    assert(id);
    return *id;
}
IncarnationId incarnation() {
    auto id = IncarnationId::parse("55555555-5555-4555-8555-555555555555");
    assert(id);
    return *id;
}
BootEpochV1 boot(std::uint32_t counter) {
    BootEpochV1 b;
    b.node_id = node_id();
    b.incarnation = incarnation();
    b.boot_counter = counter;
    b.reset_reason = 1;
    b.started_uptime_ms = 0;
    b.first_possible_sequence = 1;
    return b;
}
TimeAnchorV1 verified_anchor(std::uint8_t id_byte, const std::string& proof) {
    TimeAnchorV1 anchor;
    anchor.anchor_id.bytes.fill(id_byte);
    anchor.node_id = node_id();
    anchor.incarnation = incarnation();
    anchor.boot_counter = 42;
    anchor.uptime_ms = 1300;
    anchor.utc_ms = 1791748800123LL;
    anchor.source = AnchorSource::BACKEND_SIGNED;
    anchor.verified_security_time = true;
    anchor.uncertainty_minus_ms = 1002;
    anchor.uncertainty_plus_ms = 1302;
    anchor.drift_ppm_bound = 5000;
    anchor.proof_id_or_digest = "77777777-7777-4777-8777-777777777777";
    anchor.proof = proof;
    anchor.acceptance = std::string("accept\0bytes", 12);
    assert(anchor.valid_verified());
    return anchor;
}
}  // namespace

int main() {
    // Binary proof/acceptance (embedded NUL) survives storage and reopen.
    {
        Cell cell;
        PersistentTimeEvidence evidence(cell);
        assert(evidence.open());
        assert(evidence.append_boot(boot(42)) == PutResult::OK);
        std::string proof("proof\0exact", 11);
        proof += std::string("\0tail", 5);
        auto anchor = verified_anchor(0x66, proof);
        assert(evidence.append_verified(VerifiedAnchorTestAccess::wrap(anchor)) ==
               PutResult::OK);
        PersistentTimeEvidence reopen(cell);
        assert(reopen.open() && reopen.anchors().size() == 1);
        assert(reopen.anchors().front().proof == proof);
        assert(reopen.anchors().front().acceptance == std::string("accept\0bytes", 12));
        // Exact retry is idempotent without an extra write.
        const auto writes = cell.writes;
        assert(evidence.append_verified(VerifiedAnchorTestAccess::wrap(anchor)) ==
               PutResult::OK);
        assert(cell.writes == writes);
        // Conflicting anchor (same ID, different UTC) is rejected.
        auto conflict = anchor;
        ++conflict.utc_ms;
        assert(evidence.append_verified(VerifiedAnchorTestAccess::wrap(conflict)) ==
               PutResult::IO_ERROR);
        // Arbitrary verified writes via the unverified path stay rejected.
        assert(evidence.append_anchor(anchor) == PutResult::IO_ERROR);
    }
    // Power loss before/after commit fails closed without deleting data.
    for (bool committed : {false, true}) {
        Cell cell;
        PersistentTimeEvidence evidence(cell);
        assert(evidence.open());
        assert(evidence.append_boot(boot(42)) == PutResult::OK);
        auto anchor = verified_anchor(0x77, "proof-bytes");
        if (committed)
            cell.after = true;
        else
            cell.before = true;
        assert(evidence.append_verified(VerifiedAnchorTestAccess::wrap(anchor)) ==
               PutResult::IO_ERROR);
        // Failure latch until reopen.
        assert(evidence.append_verified(VerifiedAnchorTestAccess::wrap(anchor)) ==
               PutResult::IO_ERROR);
        assert(evidence.open());
        assert(evidence.anchors().size() == (committed ? 1U : 0U));
        assert(evidence.append_verified(VerifiedAnchorTestAccess::wrap(anchor)) ==
               PutResult::OK);
    }
    // Bounded storage fails closed; raw compaction never touches evidence.
    {
        Cell cell;
        PersistentTimeEvidence evidence(cell);
        assert(evidence.open());
        assert(evidence.append_boot(boot(42)) == PutResult::OK);
        for (unsigned i = 0; i < PersistentTimeEvidence::kMaxAnchors; ++i) {
            auto anchor = verified_anchor(static_cast<std::uint8_t>(0x20 + i),
                                          "p" + std::to_string(i));
            assert(evidence.append_verified(VerifiedAnchorTestAccess::wrap(anchor)) ==
                   PutResult::OK);
        }
        const auto before = cell.data;
        auto overflow = verified_anchor(0xFF, "overflow");
        assert(evidence.append_verified(VerifiedAnchorTestAccess::wrap(overflow)) ==
               PutResult::FULL);
        assert(cell.data == before);
        // Corruption never erases; open fails closed.
        cell.data.back() ^= 1;
        const auto damaged = cell.data;
        PersistentTimeEvidence corrupt(cell);
        assert(!corrupt.open() && cell.data == damaged);
    }
    // Factory reset clears evidence under the fresh identity journal.
    {
        Cell cell;
        PersistentTimeEvidence evidence(cell);
        assert(evidence.open());
        assert(evidence.append_boot(boot(42)) == PutResult::OK);
        auto anchor = verified_anchor(0x88, "proof");
        assert(evidence.append_verified(VerifiedAnchorTestAccess::wrap(anchor)) ==
               PutResult::OK);
        assert(evidence.clear_for_factory_reset());
        assert(evidence.boots().empty() && evidence.anchors().empty());
    }
    std::cout << "verified time-evidence persistence passed\n";
    return 0;
}
