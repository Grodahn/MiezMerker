// BLE sync v1 codec + domain tests (issue #6).
//
// Host verification of transport-independent encoding and the portable sync
// state machine. No BLE, no crypto, no hardware: fakes implement
// SyncAuthorizer/RtcControl/NodeSigner with the same single-use,
// fail-closed semantics as OfflineAuthSession.

#include <array>
#include <cassert>
#include <iostream>
#include <vector>

#include "miezmerker/ble_codec.hpp"
#include "miezmerker/ble_sync.hpp"
#include "miezmerker/core.hpp"
#include "miezmerker/memory_stores.hpp"

namespace {

int checks = 0;
#define CHECK(cond) do { ++checks; if (!(cond)) { \
    std::cerr << "FAIL " << __LINE__ << ": " << #cond << '\n'; std::exit(1); \
} } while (0)

std::string hex(const std::vector<std::uint8_t>& b) {
    static const char* h = "0123456789abcdef";
    std::string out;
    for (auto c : b) { out += h[c >> 4]; out += h[c & 15]; }
    return out;
}
std::vector<std::uint8_t> unhex(const std::string& s) {
    auto v = [](char c) -> int {
        if (c >= '0' && c <= '9') return c - '0';
        return c - 'a' + 10;
    };
    std::vector<std::uint8_t> out;
    for (std::size_t i = 0; i < s.size(); i += 2)
        out.push_back(static_cast<std::uint8_t>((v(s[i]) << 4) | v(s[i + 1])));
    return out;
}

struct Storage final : miezmerker::Storage {
    bool open() override { return true; }
};

// Fake authorizer with OfflineAuthSession semantics: single-use challenge,
// org binding, expiry against trusted time, foreign rejection.
struct FakeAuthorizer final : miezmerker::ble::SyncAuthorizer {
    std::array<std::uint8_t, 32> challenge{};
    bool pending{false};
    bool authorized{false};
    std::int64_t expires{0};
    std::int64_t issued{0};
    bool foreign{false};
    bool begin(std::array<std::uint8_t, 32>& out) override {
        pending = true;
        authorized = false;
        expires = 0;
        for (std::size_t i = 0; i < 32; ++i) challenge[i] = static_cast<std::uint8_t>(i);
        out = challenge;
        return true;
    }
    bool authorize(const std::string& cred, const std::array<std::uint8_t, 64>& proof,
                   std::int64_t now) override {
        bool was_pending = pending;
        pending = false;
        // Every authorize attempt consumes the challenge and clears the session state
        // (matches OfflineAuthSession: pending=false, expires=0 on every attempt).
        authorized = false;
        expires = 0;
        if (!was_pending || now <= 0) return false;
        if (foreign) return false;
        if (cred != "valid-credential") return false;
        // Proof must be 0xAA pattern (stand-in for ECDSA over challenge).
        for (auto b : proof)
            if (b != 0xAA) return false;
        if (now >= 1917129600 || now < 1790899200) return false;
        authorized = true;
        issued = 1790899200;
        expires = 1917129600;
        return true;
    }
    bool can_sync(std::int64_t now) const override {
        return authorized && now > 0 && issued <= now && now < expires;
    }
    void disconnect() override {
        pending = false;
        authorized = false;
        expires = 0;
        issued = 0;
        challenge.fill(0);
    }
};

struct FakeRtc final : miezmerker::ble::RtcControl {
    std::uint64_t applied{0};
    bool fail{false};
    miezmerker::ManualClock* clock{nullptr};
    bool set_corrected_epoch_ms(std::uint64_t v) override {
        if (fail) return false;
        applied = v;
        if (clock) clock->set_wall_clock(miezmerker::ClockStatus::SYNCED, v);
        return true;
    }
};

struct FakeSigner final : miezmerker::ble::NodeSigner {
    bool fail{false};
    bool sign_session(const std::array<std::uint8_t, 32>&, std::array<std::uint8_t, 64>& sig) override {
        if (fail) return false;
        sig.fill(0x5A);
        return true;
    }
};

struct Fixture {
    Storage storage;
    miezmerker::ManualClock clock;
    miezmerker::InMemoryObservationStore observations{64};
    miezmerker::InMemoryIdentityStore identity;
    miezmerker::FixedRandomSource random{0xC3};
    FakeAuthorizer auth;
    FakeRtc rtc;
    FakeSigner signer;
    miezmerker::ble::SyncConfig config;
    Fixture() { rtc.clock = &clock; }
    std::unique_ptr<miezmerker::Core> make_core() {
        return std::make_unique<miezmerker::Core>(clock, storage, observations, identity, random,
                                                  miezmerker::CoreConfig{0});
    }
};

void codec_golden_vectors() {
    using namespace miezmerker::ble;
    // Fixture frames from protocol/fixtures/ble-sync-v1.json.
    CHECK(hex(miezmerker::ble::encode_frame(
              Frame{1, Opcode::HelloRequest, encode_hello_request(1, 0x001F)})) ==
          "01010300011f00");
    auto node = miezmerker::NodeId::parse("44444444-4444-4444-8444-444444444444");
    auto inc = miezmerker::IncarnationId::parse("55555555-5555-4555-9555-555555555555");
    CHECK(node && inc);
    HelloPublic hello;
    hello.server_ver = 1;
    hello.caps = 0x001F;
    hello.node_id = *node;
    hello.incarnation = *inc;
    hello.firmware_version = "miezmerker-esp32c3-1.0.0";
    hello.claim_state = miezmerker::ClaimState::CLAIMED;
    hello.clock_status = miezmerker::ClockStatus::SYNCED;
    CHECK(hex(encode_frame(Frame{1, Opcode::HelloPublic, encode_hello_public(hello)})) ==
          "01023e00011f004444444444444444844444444444444455555555555545559555555555555555186d69657a6d65726b65722d657370333263332d312e302e300102");
    CHECK(decode_hello_public(unhex("011f004444444444444444844444444444444455555555555545559555555555555555"
                                    "186d69657a6d65726b65722d657370333263332d312e302e300102")) != std::nullopt);
    // Record golden.
    miezmerker::RawObservation obs;
    obs.node_id = *node;
    obs.incarnation = *inc;
    obs.sequence = 101;
    obs.chip_id.value = "276098106000001";
    obs.clock_status = miezmerker::ClockStatus::SYNCED;
    obs.observed_at_epoch_ms = 1790899200000ULL;
    obs.monotonic_ms = 1000;
    obs.boot_counter = 7;
    CHECK(hex(encode_record(obs)) ==
          "444444444444444484444444444444445555555555554555955555555555555565000000000000000f"
          "00323736303938313036303030303031020020e9f9a0010000e80300000000000007000000");
    std::size_t off = 0;
    auto rec_bytes = unhex("4444444444444444844444444444444455555555555545559555555555555555650000000000"
                           "00000f00323736303938313036303030303031020020e9f9a0010000e8030000000000000700"
                           "0000");
    auto back = decode_record(rec_bytes, off);
    CHECK(back && off == rec_bytes.size() && back->sequence == 101);
    // Batch request golden.
    CHECK(hex(encode_frame(Frame{1, Opcode::BatchRequest, encode_batch_request({101, 16})})) ==
          "010b0a0065000000000000001000");
    // Ack golden.
    CHECK(hex(encode_frame(Frame{1, Opcode::AckRequest, encode_ack_request(103)})) ==
          "010d08006700000000000000");
    // Status golden.
    StatusResponse st;
    st.pending = 3;
    st.ack_watermark = 100;
    st.store_status = miezmerker::StoreStatus::OK;
    st.clock_status = miezmerker::ClockStatus::SYNCED;
    st.epoch_ms = 1790899200000ULL;
    st.next_sequence = 104;
    CHECK(hex(encode_frame(Frame{1, Opcode::StatusResponse, encode_status_response(st)})) ==
          "01121e0003000000640000000000000000020020e9f9a00100006800000000000000");
    // Advertisement: no identity/chip/pending/org.
    Advertisement adv{1, true, false};
    CHECK(hex(encode_advertisement(adv)) == "01010000");
    CHECK(decode_advertisement(unhex("01010000"))->claimed);
    // Unknown version frame decodes (gating happens in server/router).
    auto f = decode_frame(unhex("02ff010000"));
    CHECK(f && f->version == 2);
    // Truncated / trailing-byte frames fail.
    CHECK(!decode_frame(unhex("010d080067000000000000")));
    CHECK(!decode_frame(unhex("010d0800670000000000000000ff")));
    // Invalid record (zero sequence, bad clock, epoch mismatch) rejected.
    miezmerker::RawObservation bad = obs;
    bad.sequence = 0;
    CHECK(encode_record(bad).empty());
    bad = obs;
    bad.clock_status = static_cast<miezmerker::ClockStatus>(9);
    CHECK(encode_record(bad).empty());
    std::cout << "PASS codec golden_vectors\n";
}

void sync_gating_and_transfers() {
    using namespace miezmerker::ble;
    Fixture f;
    auto core = f.make_core();
    f.clock.set_wall_clock(miezmerker::ClockStatus::SYNCED, 1790899200000ULL);
    CHECK(core->initialize());
    // Claim the node for sync tests (core placeholder; GATT overlays org strings).
    CHECK(core->record_chip_read("276098106000001") == miezmerker::RecordResult::RECORDED);
    CHECK(core->record_chip_read("276098106000002") == miezmerker::RecordResult::RECORDED);
    CHECK(core->record_chip_read("276098106000003") == miezmerker::RecordResult::RECORDED);
    // Force CLAIMED via identity record rewrite through a fresh core? Core has no
    // claim API; claim_state is set by NodeIdentityManager attachment. For the
    // portable SyncServer test, CLAIMED is simulated by patching the durable
    // record directly (mirrors what apply_claim persists).
    miezmerker::IdentityRecord id;
    CHECK(f.identity.load(id) == miezmerker::IdentityLoadResult::OK);
    id.claim_state = miezmerker::ClaimState::CLAIMED;
    CHECK(f.identity.store(id));
    auto core2 = f.make_core();
    CHECK(core2->initialize());
    CHECK(core2->claim_state() == miezmerker::ClaimState::CLAIMED);
    SyncServer server(*core2, f.auth, f.rtc, f.signer, f.config);
    constexpr std::int64_t now = 1790899200 + 100;
    // Public subset available before auth; protected ops gated.
    CHECK(server.public_hello().node_id == core2->node_id());
    CHECK(server.public_owner().claim_state == miezmerker::ClaimState::CLAIMED);
    CHECK(!server.status(now));
    CHECK(server.batch(1, 8, now).error == SyncError::Unauthorized);
    CHECK(server.ack(1, now).error == SyncError::Unauthorized);
    CHECK(server.correct_time(1790900000000ULL, now).error == SyncError::Unauthorized);
    CHECK(server.compact(now).error == SyncError::Unauthorized);
    // Challenge + authorize.
    std::array<std::uint8_t, 32> ch{};
    CHECK(server.begin_challenge(ch));
    std::array<std::uint8_t, 64> good_proof{};
    good_proof.fill(0xAA);
    CHECK(server.authorize("valid-credential", good_proof, now));
    // Replay of the same challenge fails (consumed).
    CHECK(!server.authorize("valid-credential", good_proof, now));
    CHECK(!server.authorized(now));
    // Re-challenge and succeed again.
    CHECK(server.begin_challenge(ch));
    CHECK(server.authorize("valid-credential", good_proof, now));
    CHECK(server.authorized(now));
    auto st = server.status(now);
    CHECK(st && st->pending == 3 && st->ack_watermark == 0);
    // Batch pages are idempotent.
    auto b1 = server.batch(1, 8, now);
    CHECK(b1.error == SyncError::Ok && b1.batch.records.size() == 3 && !b1.batch.more);
    auto b1dup = server.batch(1, 8, now);
    CHECK(b1dup.error == SyncError::Ok && b1dup.batch.records.size() == 3);
    CHECK(b1dup.batch.records[0].chip_id.value == "276098106000001");
    // Unknown from_seq beyond last+1 rejected; last+1 is empty success.
    CHECK(server.batch(99, 8, now).error == SyncError::InvalidSequence);
    auto empty = server.batch(4, 8, now);
    CHECK(empty.error == SyncError::Ok && empty.batch.records.empty() && !empty.batch.more);
    // ACK beyond last rejected; monotonic enforced; same value idempotent.
    CHECK(server.ack(99, now).error == SyncError::InvalidSequence);
    CHECK(server.ack(2, now).error == SyncError::Ok);
    CHECK(server.ack(2, now).error == SyncError::Ok);
    CHECK(server.ack(1, now).error == SyncError::InvalidSequence);
    CHECK(server.ack(3, now).error == SyncError::Ok);
    CHECK(server.ack_watermark() == 3);
    // Lost-ACK path: re-ACK same value still ok after "reconnect".
    server.disconnect();
    CHECK(!server.authorized(now));
    CHECK(server.begin_challenge(ch));
    CHECK(server.authorize("valid-credential", good_proof, now));
    CHECK(server.ack(3, now).error == SyncError::Ok);
    // Compact frees only the acked prefix.
    auto c = server.compact(now);
    CHECK(c.error == SyncError::Ok && c.freed == 3 && c.remaining == 0 && c.ack_watermark == 3);
    CHECK(server.pending_count() == 0);
    // Re-ACK after compact stays idempotent.
    CHECK(server.ack(3, now).error == SyncError::Ok);
    // Time correction: bad values rejected, good value disciplines future reads only.
    CHECK(server.correct_time(0, now).error == SyncError::InvalidFrame);
    auto t = server.correct_time(1790900000000ULL, now);
    CHECK(t.ok && t.applied_epoch_ms == 1790900000000ULL);
    CHECK(f.rtc.applied == 1790900000000ULL);
    CHECK(core2->record_chip_read("276098106000004") == miezmerker::RecordResult::RECORDED);
    auto all = core2->load_observations();
    CHECK(all.back().observed_at_epoch_ms == 1790900000000ULL);
    // Node proof requires authorization.
    std::array<std::uint8_t, 32> nonce{};
    nonce.fill(0xB0);
    CHECK(server.node_proof(nonce, now).error == SyncError::Ok);
    server.disconnect();
    CHECK(server.node_proof(nonce, now).error == SyncError::Unauthorized);
    // Foreign org: no observations/chip/pending leak.
    f.auth.foreign = true;
    CHECK(server.begin_challenge(ch));
    CHECK(!server.authorize("valid-credential", good_proof, now));
    CHECK(!server.status(now));
    CHECK(server.batch(1, 8, now).error == SyncError::Unauthorized);
    CHECK(server.public_owner().claim_state == miezmerker::ClaimState::CLAIMED);
    std::cout << "PASS sync_gating_and_transfers\n";
}

void watermark_math() {
    using namespace miezmerker::ble;
    CHECK(contiguous_watermark(100, {101, 102, 103}) == 103);
    CHECK(contiguous_watermark(100, {100, 101, 103}) == 101);  // gap at 102 blocks
    CHECK(contiguous_watermark(100, {}) == 100);
    CHECK(contiguous_watermark(0, {1, 1, 2}) == 2);  // duplicates harmless
    CHECK(contiguous_watermark(5, {7, 6}) == 7);
    std::cout << "PASS watermark_math\n";
}

void invalid_inputs() {
    using namespace miezmerker::ble;
    // Codec rejects malformed payloads without crashing.
    CHECK(!decode_hello_request({1}));
    CHECK(!decode_hello_public({1, 2}));
    CHECK(!decode_owner_response({0}));
    CHECK(!decode_auth_request({0, 5}));
    CHECK(!decode_batch_request({1}));
    CHECK(!decode_ack_request({1, 2, 3}));
    CHECK(!decode_time_request({}));
    CHECK(!decode_status_response(std::vector<std::uint8_t>(30, 0)));
    CHECK(!decode_compact_response({1}));
    CHECK(!decode_error_payload({99}));
    CHECK(!decode_advertisement({1, 0xFF, 0, 0}));
    CHECK(!decode_challenge_response(std::vector<std::uint8_t>(31, 0)));
    // Batch with zero max or zero cursor rejected.
    CHECK(!decode_batch_request(encode_batch_request({0, 8})));
    CHECK(!decode_batch_request(encode_batch_request({1, 0})));
    std::cout << "PASS invalid_inputs\n";
}

}  // namespace

int main() {
    codec_golden_vectors();
    watermark_math();
    invalid_inputs();
    sync_gating_and_transfers();
    std::cout << "All BLE sync tests passed (" << checks << " checks)\n";
    return 0;
}
