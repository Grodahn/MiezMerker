// Strict interval authorization for offline credentials and claim receipts.
// Proves L >= iat && U < exp with real JWS fixtures; no midpoint fallback,
// no grace period, revalidated per operation. Bootstrap alone grants neither
// node:sync nor claiming.
#include <array>
#include <cassert>
#include <fstream>
#include <iostream>
#include <iterator>
#include <span>
#include <string>
#include <vector>

#include "cJSON.h"
#include "mbedtls/base64.h"
#include "miezmerker/node_time.hpp"
#include "miezmerker/session_phone_clock.hpp"
#include "miezmerker/ble_gatt.hpp"
#include "miezmerker/memory_stores.hpp"
#include "../../firmware-core/tests/claim_fixture.hpp"
#include "miezmerker/offline_auth.hpp"

using namespace miezmerker;

namespace {
std::string read_file(const char* path) {
    std::ifstream input(path);
    assert(input);
    return std::string((std::istreambuf_iterator<char>(input)), {});
}
std::string field(cJSON* object, const char* name) {
    auto* item = cJSON_GetObjectItemCaseSensitive(object, name);
    assert(cJSON_IsString(item) && item->valuestring);
    return item->valuestring;
}
std::vector<unsigned char> decode_b64u(const std::string& text) {
    std::string padded = text;
    for (auto& c : padded) {
        if (c == '-') c = '+';
        if (c == '_') c = '/';
    }
    while (padded.size() % 4) padded += '=';
    std::vector<unsigned char> out(padded.size());
    std::size_t length = 0;
    assert(mbedtls_base64_decode(out.data(), out.size(), &length,
                                  reinterpret_cast<const unsigned char*>(padded.data()),
                                  padded.size()) == 0);
    out.resize(length);
    return out;
}
bool fill_nonce(std::span<unsigned char> out) {
    for (std::size_t i = 0; i < out.size(); ++i) out[i] = static_cast<unsigned char>(i);
    return true;
}
}  // namespace

int main(int argc, char** argv) {
    assert(argc == 2);
    const std::string text = read_file(argv[1]);
    cJSON* json = cJSON_Parse(text.c_str());
    assert(json);
    auto* issuer_json = cJSON_GetObjectItemCaseSensitive(json, "issuer_public_key");
    const auto x = decode_b64u(field(issuer_json, "x"));
    const auto y = decode_b64u(field(issuer_json, "y"));
    assert(x.size() == 32 && y.size() == 32);
    std::array<unsigned char, 65> issuer{};
    issuer[0] = 4;
    std::copy(x.begin(), x.end(), issuer.begin() + 1);
    std::copy(y.begin(), y.end(), issuer.begin() + 33);
    const std::string org = field(json, "organization_id");
    const std::string credential = field(json, "credential_jwt");
    const std::string receipt = field(json, "claim_receipt_jwt");
    const auto proof_bytes = decode_b64u(field(json, "challenge_signature_b64u"));
    assert(proof_bytes.size() == 64);
    std::array<unsigned char, 64> proof{};
    std::copy(proof_bytes.begin(), proof_bytes.end(), proof.begin());

    // #171: real pinned credential/PoP/claim verification with an untrusted,
    // per-connection clock, including the deliberately accepted backdating risk.
    {
        struct Timer : Clock {
            std::uint64_t now{1000}; bool valid{true};
            std::uint64_t monotonic_ms() const override { return now; }
            bool monotonic_valid() const override { return valid; }
            WallClockReading wall_clock() const override { return {}; }
        } timer;
        SessionPhoneClock phone(timer);
        std::int64_t l=0, u=0;
        OfflineAuthSession session(org, issuer);
        std::array<unsigned char,32> challenge{};
        assert(!phone.interval(l,u));
        assert(phone.sample(1791748800000ULL));
        assert(phone.interval(l,u));
        assert(!session.can_sync_with_bounds(l,u)); // Sample alone grants nothing.
        assert(session.begin(fill_nonce,challenge));
        assert(session.authorize_with_bounds(credential,proof,l,u));
        assert(!phone.sample(1790899200000ULL)); // No in-session rollback/extension.
        timer.now += 2000;
        assert(phone.interval(l,u) && l==1791748802000LL && u==l);
        assert(session.can_sync_with_bounds(l,u));
        ClaimReceiptInfo info;
        assert(verify_claim_receipt_with_bounds(receipt,issuer,l,u,info)); // No UTC-proof handshake.
        timer.now += 1917129600000ULL-1791748802000ULL;
        assert(phone.interval(l,u) && l==1917129600000LL);
        assert(!session.can_sync_with_bounds(l,u)); // Strict expiry on every operation.
        phone.disconnect(); session.disconnect();
        assert(!phone.interval(l,u));
        assert(phone.sample(1917129600000ULL)); // Honest expired phone time denies.
        assert(phone.interval(l,u)); assert(session.begin(fill_nonce,challenge));
        assert(!session.authorize_with_bounds(credential,proof,l,u));
        phone.disconnect(); session.disconnect();
        assert(phone.sample(1791748800000ULL)); // Accepted field-alpha risk: deliberately backdated.
        assert(phone.interval(l,u)); assert(session.begin(fill_nonce,challenge));
        assert(session.authorize_with_bounds(credential,proof,l,u));
        // Timer rollback latches denial even if it subsequently catches up.
        --timer.now; assert(!phone.interval(l,u)); ++timer.now; assert(!phone.interval(l,u));
        SessionPhoneClock reboot(timer); assert(!reboot.interval(l,u));
        phone.disconnect(); session.disconnect();
        assert(phone.sample(1791748800000ULL)); assert(phone.interval(l,u));
        assert(session.begin(fill_nonce,challenge));
        assert(!session.authorize_with_bounds("",proof,l,u));
        assert(session.begin(fill_nonce,challenge));
        assert(!session.authorize_with_bounds(credential+".",proof,l,u));
        auto wrong=proof; wrong[0]^=1;
        assert(session.begin(fill_nonce,challenge));
        assert(!session.authorize_with_bounds(credential,wrong,l,u));
        OfflineAuthSession foreign("foreign",issuer);
        assert(foreign.begin(fill_nonce,challenge));
        assert(!foreign.authorize_with_bounds(credential,proof,l,u));
        // A fresh challenge defeats replay of the old fixture PoP.
        auto other_nonce=[](std::span<unsigned char> out) { std::fill(out.begin(),out.end(),static_cast<unsigned char>(0x42)); return true; };
        assert(session.begin(other_nonce,challenge));
        assert(!session.authorize_with_bounds(credential,proof,l,u));
        phone.disconnect(); assert(!phone.sample(0));
        assert(!phone.sample(0xffffffffffffffffULL));
        assert(phone.sample(9223372036854775807ULL)); ++timer.now;
        assert(!phone.interval(l,u));
        phone.disconnect(); timer.valid=false; assert(!phone.sample(1791748800000ULL));
    }

    {
        ManualClock timer;
        struct Store : Storage { bool open() override { return true; } } storage;
        InMemoryObservationStore observations(64);
        InMemoryIdentityStore identity; FixedRandomSource random(0xC3);
        Core first(timer,storage,observations,identity,random,CoreConfig{0});
        assert(first.initialize()); claim_capture_fixture(first,identity);
        const auto boot=first.boot_counter();
        // Full core recreation models power loss with durable stores preserved.
        Core reboot(timer,storage,observations,identity,random,CoreConfig{0});
        assert(reboot.initialize() && reboot.boot_counter()==boot+1);
        assert(reboot.record_chip_read("immutable-unknown") == RecordResult::RECORDED);
        SessionPhoneClock phone(timer);
        struct Auth : ble::SyncAuthorizer {
            SessionPhoneClock& phone; OfflineAuthSession session;
            Auth(SessionPhoneClock& p,const std::string& o,const std::array<unsigned char,65>& pin):phone(p),session(o,pin) {}
            bool begin(std::array<std::uint8_t,32>& c) override { return session.begin(fill_nonce,c); }
            bool authorize(const std::string& jwt,const std::array<std::uint8_t,64>& sig,std::int64_t) override {
                std::int64_t l=0,u=0; return phone.interval(l,u) && session.authorize_with_bounds(jwt,sig,l,u);
            }
            bool can_sync(std::int64_t) const override {
                std::int64_t l=0,u=0; return phone.interval(l,u) && session.can_sync_with_bounds(l,u);
            }
            void disconnect() override { session.disconnect(); }
        } auth(phone,org,issuer);
        struct Rtc : ble::RtcControl { bool set_corrected_epoch_ms(std::uint64_t) override { return false; } } rtc;
        struct Signer : ble::NodeSigner {
            bool sign_session(const std::array<std::uint8_t,32>&,std::array<std::uint8_t,64>&) override { return false; }
        } signer;
        ble::SyncServer server(reboot,auth,rtc,signer);
        ble_gatt::OwnerMetadata owner; ble_gatt::GattRouter router(server,owner);
        router.set_alpha_phone_clock(&phone);
        auto exchange=[&](ble::Opcode op,const std::vector<std::uint8_t>& payload=std::vector<std::uint8_t>{}) {
            const auto response=ble::decode_frame(router.handle_frame(ble::encode_frame({1,op,payload}),0));
            assert(response); return *response;
        };
        std::vector<std::uint8_t> sample{1};
        for(unsigned i=0;i<8;++i) sample.push_back(static_cast<std::uint8_t>(1791748800000ULL>>(8*i)));
        // An adapter can reject before crypto when no clock candidate exists.
        // The domain boundary must still consume that attempt's challenge.
        assert(exchange(ble::Opcode::ChallengeRequest).opcode==ble::Opcode::ChallengeResponse);
        const auto no_clock=exchange(ble::Opcode::AuthRequest,ble::encode_auth_request({credential,proof}));
        assert(!ble::decode_auth_response(no_clock.payload)->ok);
        assert(exchange(ble::Opcode::AlphaPhoneClockRequest,sample).opcode==ble::Opcode::AlphaPhoneClockResponse);
        const auto replay=exchange(ble::Opcode::AuthRequest,ble::encode_auth_request({credential,proof}));
        assert(!ble::decode_auth_response(replay.payload)->ok);
        assert(exchange(ble::Opcode::StatusRequest).opcode==ble::Opcode::Error);
        assert(exchange(ble::Opcode::AckRequest,ble::encode_ack_request(1)).opcode==ble::Opcode::Error);
        assert(exchange(ble::Opcode::CompactRequest).opcode==ble::Opcode::Error);
        assert(reboot.ack_watermark()==0 && reboot.observation_count()==1);
        assert(exchange(ble::Opcode::ChallengeRequest).opcode==ble::Opcode::ChallengeResponse);
        auto auth_frame=exchange(ble::Opcode::AuthRequest,ble::encode_auth_request({credential,proof}));
        assert(ble::decode_auth_response(auth_frame.payload)->ok);
        const auto batch=exchange(ble::Opcode::BatchRequest,ble::encode_batch_request({1,1}));
        const auto decoded=ble::decode_batch_response(batch.payload);
        assert(decoded && decoded->records.size()==1);
        assert(decoded->records[0].chip_id.value=="immutable-unknown" && !decoded->records[0].observed_at_epoch_ms);
        const auto ack=exchange(ble::Opcode::AckRequest,ble::encode_ack_request(1));
        assert(ble::decode_ack_response(ack.payload)->error==ble::SyncError::Ok && reboot.ack_watermark()==1);
        router.disconnect();
        assert(exchange(ble::Opcode::StatusRequest).opcode==ble::Opcode::Error);
        assert(exchange(ble::Opcode::CompactRequest).opcode==ble::Opcode::Error);
        assert(reboot.observation_count()==1 && reboot.ack_watermark()==1);
    }

    // Security-clock interval from the time bootstrap (TV5-style): S is the
    // backend issuance, E=1000ms, D=300ms, sampling=2ms. Credential iat
    // 1790899200 / exp 1917129600 wholly contains it; claim receipt
    // iat 1790899200 / exp 2106259200 does as well.
    node_time::AcceptanceInterval interval;
    assert(node_time::compute_acceptance_interval(1791748800123LL, 1000, 1000, 1300, 2,
                                                  interval));
    const std::int64_t lower = interval.lower_ms;  // 1791748799121
    const std::int64_t upper = interval.upper_ms;  // 1791748801425
    assert(lower == 1791748800123LL - 1002 && upper == 1791748800123LL + 1302);

    // Valid credential + PoP authorizes under the full interval.
    {
        OfflineAuthSession session(org, issuer);
        std::array<unsigned char, 32> challenge{};
        assert(session.begin(fill_nonce, challenge));
        assert(session.authorize_with_bounds(
            credential, std::span<const unsigned char>(proof.data(), 64), lower, upper));
        assert(session.can_sync_with_bounds(lower, upper));
        // Rechecked during a long connection: expiry inside the new bounds denies.
        assert(!session.can_sync_with_bounds(lower, 1917129600000LL));
        assert(!session.can_sync_with_bounds(1790899199000LL, upper));
        assert(!session.can_sync_with_bounds(1917129600000LL, 1917129600000LL));
        session.disconnect();
        assert(!session.can_sync_with_bounds(lower, upper));
    }
    // Expiry/iat overlapping the uncertainty interval denies (no grace).
    {
        OfflineAuthSession session(org, issuer);
        std::array<unsigned char, 32> challenge{};
        assert(session.begin(fill_nonce, challenge));
        // Upper exactly at exp_ms is not strictly inside (U < exp required).
        assert(!session.authorize_with_bounds(
            credential, std::span<const unsigned char>(proof.data(), 64), lower,
            1917129600000LL));
        assert(session.begin(fill_nonce, challenge));
        assert(!session.authorize_with_bounds(
            credential, std::span<const unsigned char>(proof.data(), 64),
            1790899199000LL, upper));
        // Unknown bounds fail closed.
        assert(session.begin(fill_nonce, challenge));
        assert(!session.authorize_with_bounds(
            credential, std::span<const unsigned char>(proof.data(), 64), 0, 0));
    }
    // Existing invalid signature/scope/org/PoP behavior remains rejected.
    {
        OfflineAuthSession session(org, issuer);
        std::array<unsigned char, 32> challenge{};
        auto bad_proof = proof;
        bad_proof[0] ^= 1;
        assert(session.begin(fill_nonce, challenge));
        assert(!session.authorize_with_bounds(
            credential, std::span<const unsigned char>(bad_proof.data(), 64), lower,
            upper));
        OfflineAuthSession foreign("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", issuer);
        assert(foreign.begin(fill_nonce, challenge));
        assert(!foreign.authorize_with_bounds(
            credential, std::span<const unsigned char>(proof.data(), 64), lower, upper));
        assert(session.begin(fill_nonce, challenge));
        assert(!session.authorize_with_bounds(
            credential + ".", std::span<const unsigned char>(proof.data(), 64), lower,
            upper));
        // Bootstrap alone grants no sync without a credential.
        OfflineAuthSession empty(org, issuer);
        assert(!empty.can_sync_with_bounds(lower, upper));
    }
    // Claim receipts use the same strict interval guard.
    {
        ClaimReceiptInfo info;
        assert(verify_claim_receipt_with_bounds(
            receipt, std::span<const unsigned char, 65>(issuer), lower, upper, info));
        assert(info.organization_id == org);
        assert(!verify_claim_receipt_with_bounds(
            receipt, std::span<const unsigned char, 65>(issuer), lower, 2106259200000LL,
            info));
        assert(!verify_claim_receipt_with_bounds(
            receipt, std::span<const unsigned char, 65>(issuer), 0, 0, info));
        auto wrong = issuer;
        wrong[5] ^= 1;
        assert(!verify_claim_receipt_with_bounds(
            receipt, std::span<const unsigned char, 65>(wrong), lower, upper, info));
    }

    cJSON_Delete(json);
    std::cout << "node-time interval authorization passed\n";
    return 0;
}
