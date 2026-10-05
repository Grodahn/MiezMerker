// Host verification of the BLE GATT request router (issue #6).
//
// No NimBLE/ESP-IDF: the router is exercised with raw frames exactly as the
// board shim would deliver them. Authorization uses a fake SyncAuthorizer
// with OfflineAuthSession semantics (single-use challenge, org binding,
// trusted-time expiry).

#include <array>
#include <cassert>
#include <iostream>
#include <fstream>
#include <filesystem>
#include <memory>
#include <vector>

#include "miezmerker/ble_codec.hpp"
#include "miezmerker/ble_gatt.hpp"
#include "miezmerker/ble_sync.hpp"
#include "miezmerker/core.hpp"
#include "miezmerker/memory_stores.hpp"

namespace {

int checks = 0;
#define CHECK(cond) do { ++checks; if (!(cond)) { \
    std::cerr << "FAIL " << __LINE__ << ": " << #cond << '\n'; std::exit(1); \
} } while (0)

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
        if (cred != "valid-credential" && cred != std::string(900, 'c')) return false;
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
    miezmerker::ManualClock* clock{nullptr};
    bool set_corrected_epoch_ms(std::uint64_t v) override {
        applied = v;
        if (clock) clock->set_wall_clock(miezmerker::ClockStatus::SYNCED, v);
        return true;
    }
};

struct FakeSigner final : miezmerker::ble::NodeSigner {
    bool sign_session(const std::array<std::uint8_t, 32>&, std::array<std::uint8_t, 64>& sig) override {
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
    miezmerker::ble_gatt::OwnerMetadata owner;
    Fixture() {
        rtc.clock = &clock;
        owner.organization_id = "22222222-2222-4222-8222-222222222222";
        owner.organization_slug = "vector-org";
        owner.organization_name = "Vector Org";
        owner.public_contact = "help@vector.example";
    }
    std::unique_ptr<miezmerker::Core> make_core() {
        return std::make_unique<miezmerker::Core>(clock, storage, observations, identity, random,
                                                  miezmerker::CoreConfig{0});
    }
};

void router_public_and_auth() {
    using namespace miezmerker::ble;
    Fixture f;
    auto core = f.make_core();
    f.clock.set_wall_clock(miezmerker::ClockStatus::SYNCED, 1790899200000ULL);
    CHECK(core->initialize());
    CHECK(core->record_chip_read("276098106000001") == miezmerker::RecordResult::RECORDED);
    CHECK(core->record_chip_read("276098106000002") == miezmerker::RecordResult::RECORDED);
    miezmerker::IdentityRecord id;
    CHECK(f.identity.load(id) == miezmerker::IdentityLoadResult::OK);
    id.claim_state = miezmerker::ClaimState::CLAIMED;
    CHECK(f.identity.store(id));
    auto core2 = f.make_core();
    CHECK(core2->initialize());
    SyncServer server(*core2, f.auth, f.rtc, f.signer, f.config);
    miezmerker::ble_gatt::GattRouter router(server, f.owner);
    constexpr std::int64_t now = 1790899200 + 100;

    auto adv = router.advertisement_bytes();
    CHECK(adv.size() == 4 && adv[0] == 1 && (adv[1] & 0x01) != 0);

    auto hello = router.handle_frame(
        encode_frame(Frame{1, Opcode::HelloRequest, encode_hello_request(1, 0x001F)}), now);
    auto hf = decode_frame(hello);
    CHECK(hf && hf->opcode == Opcode::HelloPublic);
    auto hp = decode_hello_public(hf->payload);
    CHECK(hp && hp->server_ver == 1 && hp->caps == 0x001F);
    CHECK(hp->node_id == core2->node_id());
    CHECK(hp->claim_state == miezmerker::ClaimState::CLAIMED);

    auto owner = router.handle_frame(
        encode_frame(Frame{1, Opcode::OwnerRequest, {}}), now);
    auto of = decode_frame(owner);
    CHECK(of && of->opcode == Opcode::OwnerResponse);
    auto oi = decode_owner_response(of->payload);
    CHECK(oi && oi->organization_id == "22222222-2222-4222-8222-222222222222");
    CHECK(oi->organization_name == "Vector Org");

    auto st = router.handle_frame(encode_frame(Frame{1, Opcode::StatusRequest, {}}), now);
    auto stf = decode_frame(st);
    CHECK(stf && stf->opcode == Opcode::Error);
    auto err = decode_error_payload(stf->payload);
    CHECK(err && err->code == SyncError::Unauthorized);

    auto ch = router.handle_frame(
        encode_frame(Frame{1, Opcode::ChallengeRequest, {}}), now);
    auto chf = decode_frame(ch);
    CHECK(chf && chf->opcode == Opcode::ChallengeResponse);
    auto nonce = decode_challenge_response(chf->payload);
    CHECK(nonce && (*nonce)[0] == 0 && (*nonce)[31] == 31);

    std::array<std::uint8_t, 64> proof{};
    proof.fill(0xAA);
    auto auth_resp = router.handle_frame(
        encode_frame(Frame{1, Opcode::AuthRequest, encode_auth_request({"valid-credential", proof})}),
        now);
    auto af = decode_frame(auth_resp);
    CHECK(af && af->opcode == Opcode::AuthResponse);
    auto ar = decode_auth_response(af->payload);
    CHECK(ar && ar->ok && ar->error == SyncError::Ok);

    auto replay = router.handle_frame(
        encode_frame(Frame{1, Opcode::AuthRequest, encode_auth_request({"valid-credential", proof})}),
        now);
    auto rf = decode_frame(replay);
    CHECK(rf && rf->opcode == Opcode::AuthResponse);
    auto rr = decode_auth_response(rf->payload);
    CHECK(rr && !rr->ok);

    // Failed replay clears the session (expires=0); need fresh challenge + auth.
    auto st_fail = router.handle_frame(encode_frame(Frame{1, Opcode::StatusRequest, {}}), now);
    auto st_fail_f = decode_frame(st_fail);
    CHECK(st_fail_f && st_fail_f->opcode == Opcode::Error);
    auto err_fail = decode_error_payload(st_fail_f->payload);
    CHECK(err_fail && err_fail->code == SyncError::Unauthorized);

    // Re-challenge and succeed again.
    auto ch3 = router.handle_frame(
        encode_frame(Frame{1, Opcode::ChallengeRequest, {}}), now);
    auto ch3f = decode_frame(ch3);
    CHECK(ch3f && ch3f->opcode == Opcode::ChallengeResponse);
    auto auth3 = router.handle_frame(
        encode_frame(Frame{1, Opcode::AuthRequest, encode_auth_request({"valid-credential", proof})}),
        now);
    auto a3f = decode_frame(auth3);
    CHECK(a3f && a3f->opcode == Opcode::AuthResponse);
    auto a3r = decode_auth_response(a3f->payload);
    CHECK(a3r && a3r->ok && a3r->error == SyncError::Ok);

    // Status now works again.
    auto st2 = router.handle_frame(encode_frame(Frame{1, Opcode::StatusRequest, {}}), now);
    auto st2f = decode_frame(st2);
    CHECK(st2f && st2f->opcode == Opcode::StatusResponse);
    auto sr = decode_status_response(st2f->payload);
    CHECK(sr && sr->pending == 2 && sr->ack_watermark == 0);

    auto batch = router.handle_frame(
        encode_frame(Frame{1, Opcode::BatchRequest, encode_batch_request({1, 8})}), now);
    auto bf = decode_frame(batch);
    CHECK(bf && bf->opcode == Opcode::BatchResponse);
    auto br = decode_batch_response(bf->payload);
    CHECK(br && br->records.size() == 2 && !br->more);
    CHECK(br->records[0].chip_id.value == "276098106000001");
    CHECK(br->records[1].chip_id.value == "276098106000002");

    auto ack = router.handle_frame(
        encode_frame(Frame{1, Opcode::AckRequest, encode_ack_request(2)}), now);
    auto ackf = decode_frame(ack);
    CHECK(ackf && ackf->opcode == Opcode::AckResponse);
    auto ackr = decode_ack_response(ackf->payload);
    CHECK(ackr && ackr->new_watermark == 2 && ackr->error == SyncError::Ok);

    auto compact = router.handle_frame(
        encode_frame(Frame{1, Opcode::CompactRequest, {}}), now);
    auto cf = decode_frame(compact);
    CHECK(cf && cf->opcode == Opcode::CompactResponse);
    auto cr = decode_compact_response(cf->payload);
    CHECK(cr && cr->freed == 2 && cr->remaining == 0 && cr->ack_watermark == 2);

    std::array<std::uint8_t, 32> pwa_nonce{};
    pwa_nonce.fill(0xB0);
    auto np = router.handle_frame(
        encode_frame(Frame{1, Opcode::NodeProofRequest, encode_node_proof_request(pwa_nonce)}),
        now);
    auto npf = decode_frame(np);
    CHECK(npf && npf->opcode == Opcode::NodeProofResponse);
    auto npr = decode_node_proof_response(npf->payload);
    CHECK(npr && (*npr)[0] == 0x5A);

    auto tc = router.handle_frame(
        encode_frame(Frame{1, Opcode::TimeRequest, encode_time_request(1790900000000ULL)}),
        now);
    auto tcf = decode_frame(tc);
    CHECK(tcf && tcf->opcode == Opcode::TimeResponse);
    auto tcr = decode_time_response(tcf->payload);
    CHECK(tcr && tcr->ok && tcr->applied_epoch_ms == 1790900000000ULL);

    auto uv = router.handle_frame(unhex("02ff010000"), now);
    auto uvf = decode_frame(uv);
    CHECK(uvf && uvf->opcode == Opcode::Error);
    auto uve = decode_error_payload(uvf->payload);
    CHECK(uve && uve->code == SyncError::UnknownVersion);

    auto mf = router.handle_frame(unhex("01ff"), now);
    auto mff = decode_frame(mf);
    CHECK(mff && mff->opcode == Opcode::Error);
    auto mfe = decode_error_payload(mff->payload);
    CHECK(mfe && mfe->code == SyncError::InvalidFrame);

    f.auth.foreign = true;
    auto ch2 = router.handle_frame(
        encode_frame(Frame{1, Opcode::ChallengeRequest, {}}), now);
    auto auth2 = router.handle_frame(
        encode_frame(Frame{1, Opcode::AuthRequest, encode_auth_request({"valid-credential", proof})}),
        now);
    auto a2f = decode_frame(auth2);
    CHECK(a2f && a2f->opcode == Opcode::AuthResponse);
    auto a2r = decode_auth_response(a2f->payload);
    CHECK(a2r && !a2r->ok);

    router.disconnect();
    auto st3 = router.handle_frame(encode_frame(Frame{1, Opcode::StatusRequest, {}}), now);
    auto st3f = decode_frame(st3);
    CHECK(st3f && st3f->opcode == Opcode::Error);

    std::cout << "PASS router_public_and_auth\n";
}

void router_foreign_no_leak() {
    using namespace miezmerker::ble;
    Fixture f;
    auto core = f.make_core();
    f.clock.set_wall_clock(miezmerker::ClockStatus::SYNCED, 1790899200000ULL);
    CHECK(core->initialize());
    CHECK(core->record_chip_read("276098106000001") == miezmerker::RecordResult::RECORDED);
    miezmerker::IdentityRecord id;
    CHECK(f.identity.load(id) == miezmerker::IdentityLoadResult::OK);
    id.claim_state = miezmerker::ClaimState::CLAIMED;
    CHECK(f.identity.store(id));
    auto core2 = f.make_core();
    CHECK(core2->initialize());
    SyncServer server(*core2, f.auth, f.rtc, f.signer, f.config);
    miezmerker::ble_gatt::GattRouter router(server, f.owner);
    constexpr std::int64_t now = 1790899200 + 100;
    f.auth.foreign = true;

    auto owner = router.handle_frame(
        encode_frame(Frame{1, Opcode::OwnerRequest, {}}), now);
    auto of = decode_frame(owner);
    CHECK(of && of->opcode == Opcode::OwnerResponse);

    auto ch = router.handle_frame(
        encode_frame(Frame{1, Opcode::ChallengeRequest, {}}), now);
    auto chf = decode_frame(ch);
    CHECK(chf && chf->opcode == Opcode::ChallengeResponse);
    std::array<std::uint8_t, 64> proof{};
    proof.fill(0xAA);
    auto auth_resp = router.handle_frame(
        encode_frame(Frame{1, Opcode::AuthRequest, encode_auth_request({"valid-credential", proof})}),
        now);
    auto af = decode_frame(auth_resp);
    CHECK(af && af->opcode == Opcode::AuthResponse);
    auto ar = decode_auth_response(af->payload);
    CHECK(ar && !ar->ok);

    auto st = router.handle_frame(encode_frame(Frame{1, Opcode::StatusRequest, {}}), now);
    auto stf = decode_frame(st);
    CHECK(stf && stf->opcode == Opcode::Error);

    auto batch = router.handle_frame(
        encode_frame(Frame{1, Opcode::BatchRequest, encode_batch_request({1, 8})}), now);
    auto bf = decode_frame(batch);
    CHECK(bf && bf->opcode == Opcode::Error);

    // Even a guessed current watermark must return only an authorization error.
    CHECK(core2->set_ack_watermark(1));
    for (auto watermark : {std::uint64_t{0}, std::uint64_t{1}}) {
        auto ack = decode_frame(router.handle_frame(
            encode_frame(Frame{1, Opcode::AckRequest, encode_ack_request(watermark)}), now));
        CHECK(ack && ack->opcode == Opcode::Error);
        auto error = decode_error_payload(ack->payload);
        CHECK(error && error->code == SyncError::Unauthorized);
    }

    std::cout << "PASS router_foreign_no_leak\n";
}

void router_fragmented_auth() {
    using namespace miezmerker::ble;
    Fixture f;
    auto core = f.make_core();
    CHECK(core->initialize());
    miezmerker::IdentityRecord id;
    CHECK(f.identity.load(id) == miezmerker::IdentityLoadResult::OK);
    id.claim_state = miezmerker::ClaimState::CLAIMED;
    CHECK(f.identity.store(id));
    core = f.make_core();
    CHECK(core->initialize());
    SyncServer server(*core, f.auth, f.rtc, f.signer, f.config);
    miezmerker::ble_gatt::GattRouter router(server, f.owner);
    constexpr std::int64_t now = 1790899300;
    std::array<std::uint8_t, 64> proof{};
    proof.fill(0xAA);
    const auto frame = encode_frame(Frame{1, Opcode::AuthRequest, encode_auth_request({std::string(900, 'c'), proof})});
    auto fragment = [&](std::size_t offset) {
        std::vector<std::uint8_t> part{0x4d, 0x4d, 1, static_cast<std::uint8_t>(offset == 0 ? 1 : 0),
            static_cast<std::uint8_t>(frame.size()), static_cast<std::uint8_t>(frame.size() >> 8),
            static_cast<std::uint8_t>(offset), static_cast<std::uint8_t>(offset >> 8)};
        const auto end = std::min(offset + 12, frame.size());
        part.insert(part.end(), frame.begin() + offset, frame.begin() + end);
        return part;
    };
    router.handle_frame(encode_frame(Frame{1, Opcode::ChallengeRequest, {}}), now);
    for (std::size_t offset = 0; offset < frame.size(); offset += 12) {
        const auto response = router.handle_frame(fragment(offset), now);
        if (offset + 12 < frame.size()) {
            CHECK(response.empty());
            CHECK(!f.auth.authorized);
        } else {
            const auto decoded = decode_frame(response);
            CHECK(decoded && decoded->opcode == Opcode::AuthResponse);
            const auto auth = decode_auth_response(decoded->payload);
            CHECK(auth && auth->ok);
        }
    }
    router.disconnect();
    CHECK(router.handle_frame(fragment(0), now).empty());
    router.disconnect();
    auto rejected = decode_frame(router.handle_frame(fragment(12), now));
    CHECK(rejected && rejected->opcode == Opcode::Error);
    CHECK(router.handle_frame(fragment(0), now).empty());
    rejected = decode_frame(router.handle_frame(fragment(24), now));
    CHECK(rejected && rejected->opcode == Opcode::Error);
    CHECK(!f.auth.authorized);
}

// Domain-port fake; ES256 verification is separately covered by the shared
// simulator/backend fixtures. This tests GATT delivery through the real manager.
struct ClaimCell final : miezmerker::NodeIdentityStore {
    std::optional<miezmerker::NodeIdentity> saved;
    miezmerker::NodeIdentityLoadResult load(miezmerker::NodeIdentity& out) override {
        if (!saved) return miezmerker::NodeIdentityLoadResult::missing;
        out = *saved; return miezmerker::NodeIdentityLoadResult::loaded;
    }
    bool save(const miezmerker::NodeIdentity& value) override { saved = value; return true; }
    bool clear() override { saved.reset(); return true; }
};
struct PhysicalMode final : miezmerker::ClaimMode {
    bool enabled{false}; bool active() const override { return enabled; }
};
struct ClaimTime final : miezmerker::ClaimClock {
    std::uint64_t epoch_ms() const override { return 1790899300000ULL; }
};
struct ClaimCrypto final : miezmerker::NodeCrypto {
    miezmerker::VerifiedClaim claim;
    bool generate_keypair(std::span<std::byte, 65> pub, std::span<std::byte, 32> priv) override {
        std::fill(pub.begin(), pub.end(), std::byte{1}); pub[0] = std::byte{4};
        std::fill(priv.begin(), priv.end(), std::byte{1}); return true;
    }
    bool sign(std::span<const std::byte, 32>, std::span<const std::byte>, std::span<std::byte, 64> sig) override {
        std::fill(sig.begin(), sig.end(), std::byte{1}); return true;
    }
    bool verify_claim(const std::string& receipt, miezmerker::VerifiedClaim& out) override {
        if (receipt != std::string(900, 'r')) return false;
        out = claim; return true;
    }
};
void router_claim_round_trip() {
    using namespace miezmerker;
    using namespace miezmerker::ble;
    Fixture f; ClaimCell cell; PhysicalMode mode; ClaimTime time; ClaimCrypto crypto;
    NodeIdentityManager manager(cell, mode, f.random, crypto, time);
    Core core(f.clock, f.storage, f.observations, manager, f.random, CoreConfig{});
    CHECK(core.initialize());
    const auto boot = core.boot_counter();
    const auto id = manager.identity();
    crypto.claim = {id.node_id, id.public_key, "org-a", "Org A", "contact"};
    SyncServer server(core, f.auth, f.rtc, f.signer, f.config);
    ble_gatt::GattRouter router(server, f.owner); router.set_node_identity(&manager);
    const auto request = encode_frame(Frame{1, Opcode::ClaimAdvertisementRequest, {}});
    auto response = decode_frame(router.handle_frame(request, 1790899300));
    CHECK(response && response->opcode == Opcode::Error);
    mode.enabled = true;
    response = decode_frame(router.handle_frame(request, 1790899300));
    CHECK(response && response->opcode == Opcode::ClaimAdvertisementResponse);
    auto advertisement = decode_claim_advertisement(response->payload);
    CHECK(advertisement && advertisement->node_id == core.node_id());
    CHECK(advertisement->timestamp_ms == time.epoch_ms());
    const auto receipt = encode_frame(Frame{1, Opcode::ClaimReceiptRequest, encode_claim_receipt(std::string(900, 'r'))});
    for (std::size_t offset = 0; offset < receipt.size(); offset += 12) {
        std::vector<std::uint8_t> chunk{0x4e, 0x4d, 1, static_cast<std::uint8_t>(offset == 0),
            static_cast<std::uint8_t>(receipt.size()), static_cast<std::uint8_t>(receipt.size() >> 8),
            static_cast<std::uint8_t>(offset), static_cast<std::uint8_t>(offset >> 8)};
        chunk.insert(chunk.end(), receipt.begin() + offset, receipt.begin() + std::min(offset + 12, receipt.size()));
        const auto raw = router.handle_frame(chunk, 1790899300);
        if (offset + 12 < receipt.size()) { CHECK(raw.empty()); CHECK(!manager.claimed()); }
        else { response = decode_frame(raw); CHECK(response && response->opcode == Opcode::ClaimReceiptResponse && response->payload == std::vector<std::uint8_t>{0}); }
    }
    CHECK(manager.claimed()); CHECK(core.claim_state() == ClaimState::CLAIMED);
    CHECK(core.boot_counter() == boot); CHECK(f.owner.organization_id == "org-a");
    mode.enabled = false;
    response = decode_frame(router.handle_frame(receipt, 1790899300));
    CHECK(response && response->opcode == Opcode::ClaimReceiptResponse); // idempotent retry
    response = decode_frame(router.handle_frame(request, 1790899300));
    CHECK(response && response->opcode == Opcode::Error); // no new advertisement when claimed
    crypto.claim.organization_id = "foreign";
    response = decode_frame(router.handle_frame(receipt, 1790899300));
    CHECK(response && response->opcode == Opcode::Error);
    CHECK(manager.identity().organization_id == "org-a");
}

void claim_codec_golden() {
    const auto path = std::filesystem::path(__FILE__).parent_path().parent_path().parent_path() /
        "protocol/fixtures/claim-advertisement-v1.hex";
    std::ifstream input(path);
    std::string hex; input >> hex;
    CHECK(hex.size() == 306);
    const auto bytes = unhex(hex);
    const auto decoded = miezmerker::ble::decode_claim_advertisement(bytes);
    CHECK(decoded && decoded->node_id.to_string() == "3a3a3a3a-3a3a-4a3a-8a3a-3a3a3a3a3a3a");
    CHECK(decoded->timestamp_ms == 1790899200000ULL);
    CHECK(miezmerker::ble::encode_claim_advertisement(*decoded) == bytes);
}

}  // namespace

int main() {
    router_public_and_auth();
    router_foreign_no_leak();
    router_fragmented_auth();
    router_claim_round_trip();
    claim_codec_golden();
    std::cout << "All BLE GATT router tests passed (" << checks << " checks)\n";
    return 0;
}
