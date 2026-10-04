#include "miezmerker/sim/scenario_runner.hpp"
#include "miezmerker/sim/parse_utils.hpp"

#include <algorithm>
#include <cstring>
#include <fstream>
#include <iostream>
#include <limits>
#include <sstream>
#include <stdexcept>

#include "miezmerker/ble_gatt.hpp"
#include "miezmerker/offline_auth.hpp"

namespace miezmerker::sim {

namespace {

// Deterministic challenge injector for OfflineAuthSession::begin.
// Production boards use a hardware CSPRNG; the simulator uses fixture
// challenges (fixed 00..1f / alt 42...) so proofs stay precomputed and
// deterministic. Set before every begin_challenge.
std::array<unsigned char, 32> g_next_challenge{};
bool g_challenge_set{false};

bool sim_random_fill(std::span<unsigned char> out) {
    if (!g_challenge_set || out.size() != 32) return false;
    std::copy(g_next_challenge.begin(), g_next_challenge.end(), out.begin());
    return true;
}

std::string format_record_result(RecordResult r) {
    switch (r) {
        case RecordResult::RECORDED: return "RECORDED";
        case RecordResult::DEBOUNCED: return "DEBOUNCED";
        case RecordResult::REJECTED_FULL: return "REJECTED_FULL";
        case RecordResult::REJECTED_INVALID: return "REJECTED_INVALID";
        case RecordResult::NOT_READY: return "NOT_READY";
        case RecordResult::IO_ERROR: return "IO_ERROR";
    }
    return "IO_ERROR";
}

}  // namespace

// Real BLE session for issue #7: production SyncServer + GattRouter +
// OfflineAuthSession (real ES256/JWT verification). Challenge bytes come from
// deterministic fixtures (see sim_random_fill); proofs/credentials are
// canonical fixture strings verified by the real session object.
// Holders (auth/rtc/signer) are owned here so SyncServer/GattRouter references
// stay valid for the session lifetime.
struct ScenarioRunner::BleSession {
    struct Authorizer : ble::SyncAuthorizer {
        std::unique_ptr<OfflineAuthSession> session;
        bool begin(std::array<std::uint8_t, 32>& challenge) override {
            if (!session) return false;
            std::array<unsigned char, 32> raw{};
            if (!session->begin(sim_random_fill, std::span<unsigned char, 32>(raw))) return false;
            for (std::size_t i = 0; i < 32; ++i) challenge[i] = raw[i];
            return true;
        }
        bool authorize(const std::string& credential,
                       const std::array<std::uint8_t, 64>& proof,
                       ble::TrustedEpochSeconds now_s) override {
            if (!session) return false;
            std::array<unsigned char, 64> raw_proof{};
            for (std::size_t i = 0; i < 64; ++i) raw_proof[i] = proof[i];
            return session->authorize(credential, std::span<const unsigned char>(raw_proof),
                                      now_s);
        }
        bool can_sync(ble::TrustedEpochSeconds now_s) const override {
            if (!session) return false;
            return session->can_sync(now_s);
        }
        void disconnect() override {
            if (session) session->disconnect();
        }
    };
    struct Rtc : ble::RtcControl {
        SimRtc& rtc;
        explicit Rtc(SimRtc& r) : rtc(r) {}
        bool set_corrected_epoch_ms(std::uint64_t ms) override { rtc.correct(ms); return true; }
    };
    struct Signer : ble::NodeSigner {
        NodeIdentityManager* identity{nullptr};
        bool force_fail{false};
        bool sign_session(const std::array<std::uint8_t, 32>& challenge,
                          std::array<std::uint8_t, 64>& signature) override {
            if (force_fail || !identity) return false;
            std::array<std::byte, 32> nonce;
            std::array<std::byte, 64> proof;
            for (std::size_t i = 0; i < 32; ++i) nonce[i] = static_cast<std::byte>(challenge[i]);
            if (!identity->sign_session_challenge(nonce, proof)) return false;
            for (std::size_t i = 0; i < 64; ++i) signature[i] = static_cast<std::uint8_t>(proof[i]);
            return true;
        }
    };
    Authorizer auth;
    Rtc rtc;
    Signer signer;
    std::unique_ptr<ble::SyncServer> server;
    std::unique_ptr<ble_gatt::GattRouter> router;
    std::unique_ptr<SimBleTransport> transport;
    ble_gatt::OwnerMetadata owner_meta;
    bool claim_mode{false};
    BleSession(Core& core, SimRtc& r) : rtc(r) {
        server = std::make_unique<ble::SyncServer>(core, auth, rtc, signer);
        router = std::make_unique<ble_gatt::GattRouter>(*server, owner_meta);
        transport = std::make_unique<SimBleTransport>(*router);
    }
};

// Legacy deterministic authorization for #22 transfer scenarios (kept for
// backward compatibility with 027-031). New #7 scenarios use BleSession with
// the real OfflineAuthSession; legacy sync_* events keep working unchanged.
struct ScenarioRunner::SyncFixture {
    struct Authorizer : ble::SyncAuthorizer {
        bool active{false};
        bool begin(std::array<std::uint8_t, 32>& challenge) override {
            active = false;
            challenge.fill(0xA5);
            return true;
        }
        bool authorize(const std::string&, const std::array<std::uint8_t, 64>&,
                       ble::TrustedEpochSeconds) override { active = true; return true; }
        bool can_sync(ble::TrustedEpochSeconds) const override { return active; }
        void disconnect() override { active = false; }
    } auth;
    struct Rtc : ble::RtcControl {
        SimRtc& rtc;
        explicit Rtc(SimRtc& r) : rtc(r) {}
        bool set_corrected_epoch_ms(std::uint64_t ms) override { rtc.correct(ms); return true; }
    } rtc;
    struct Signer : ble::NodeSigner {
        bool sign_session(const std::array<std::uint8_t, 32>&,
                          std::array<std::uint8_t, 64>& signature) override {
            signature.fill(0x5A); return true;
        }
    } signer;
    ble::SyncServer server;
    static constexpr ble::TrustedEpochSeconds now = 1790899200;
    SyncFixture(Core& core, SimRtc& clock) : rtc(clock), server(core, auth, rtc, signer) {
        std::array<std::uint8_t, 32> challenge{};
        auth.begin(challenge);
        auth.authorize("simulator", {}, now);
    }
};

ScenarioRunner::ScenarioRunner(const Scenario& scenario)
    : scenario_(scenario),
      clock_(rtc_),
      random_(scenario.seed),
      claim_crypto_(std::array<unsigned char, 65>{}),
      capacity_(scenario.capacity),
      debounce_ms_(scenario.debounce_ms) {
    observations_ = SimObservationStore(capacity_);
    identity_ = SimDeviceIdentity();
    const auto& fx = sim_fixtures();
    if (fx.loaded) {
        trusted_now_s_ = fx.trusted_now_s;
        claim_crypto_.set_issuer(fx.issuer_key);
    }
}

ScenarioRunner::~ScenarioRunner() = default;

void ScenarioRunner::destroy_core() {
    sync_.reset();
    ble_.reset();
    ble_connected_ = false;
    has_challenge_ = false;
    last_batch_.reset();
    last_status_.reset();
    core_.reset();
    node_manager_.reset();
}

bool ScenarioRunner::rebuild_ble_session() {
    ble_.reset();
    ble_connected_ = false;
    has_challenge_ = false;
    if (!core_ || !core_->ready()) return true;
    ble_ = std::make_unique<BleSession>(*core_, rtc_);
    ble_->signer.identity = node_manager_.get();
    ble_->owner_meta.organization_id = owner_.organization_id;
    ble_->owner_meta.organization_slug = owner_.organization_slug;
    ble_->owner_meta.organization_name = owner_.organization_name;
    ble_->owner_meta.public_contact = owner_.public_contact;
    ble_->claim_mode = claim_mode_.enabled;
    if (ble_->router) ble_->router->set_claim_mode(claim_mode_.enabled);
    // Authorizer session is (re)created on phone_connect with the current
    // owner org + pinned issuer. Until then the session stays disconnected.
    return true;
}

DeviceIdentityStore& ScenarioRunner::device_identity() {
    if (!identity_.has_node_identity()) return identity_;
    node_manager_ = std::make_unique<NodeIdentityManager>(identity_, claim_mode_, random_,
                                                         claim_crypto_, identity_clock_);
    // Core loads through the manager, preserving one-shot load failures and
    // resuming pending reset metadata before signing or claiming is allowed.
    return *node_manager_;
}

void ScenarioRunner::refresh_owner() {
    if (!node_manager_) return;
    const auto& node = node_manager_->identity();
    const auto& fx = sim_fixtures();
    owner_.claimed = node_manager_->claimed();
    owner_.organization_id = node.organization_id;
    owner_.organization_name = node.organization_name;
    owner_.public_contact = node.public_contact;
    owner_.organization_slug = node.organization_id == fx.org_a_id ? fx.org_a_slug
        : node.organization_id == fx.org_b_id ? fx.org_b_slug
        : node.organization_id == fx.vector_org_id ? "vector-org" : "";
    node_keys_.provisioned = true;
    for (std::size_t i = 0; i < 65; ++i)
        node_keys_.public_key[i] = static_cast<unsigned char>(node.public_key[i]);
    node_keys_.node_id = core_->node_id().to_string();
}

bool ScenarioRunner::boot_core() {
    destroy_core();
    // Physical claim mode is volatile like a button: cleared on every boot.
    claim_mode_.enabled = false;
    core_ = std::make_unique<Core>(clock_, storage_, observations_, device_identity(), random_,
                                   CoreConfig{debounce_ms_});
    if (!core_->initialize()) {
        // initialize() can fail (e.g., storage unavailable, identity load error)
        // This is not a scenario failure; assertions can check ready() state.
        return true;
    }
    last_node_id_ = core_->node_id();
    last_incarnation_ = core_->incarnation();
    refresh_owner();
    if (core_->claim_state() == ClaimState::CLAIMED) {
        sync_ = std::make_unique<SyncFixture>(*core_, rtc_);
    }
    rebuild_ble_session();
    return true;
}

bool ScenarioRunner::reboot_core() {
    destroy_core();
    return boot_core();
}

bool ScenarioRunner::factory_reset_core() {
    // Save current node_id as "previous" for changed assertion
    previous_node_id_ = last_node_id_;
    previous_incarnation_ = last_incarnation_;

    destroy_core();
    core_ = std::make_unique<Core>(clock_, storage_, observations_, device_identity(), random_,
                                   CoreConfig{debounce_ms_});
    if (!core_->factory_reset()) {
        // factory_reset can fail; assertions can check ready() state.
        return true;
    }
    last_node_id_ = core_->node_id();
    last_incarnation_ = core_->incarnation();
    // Factory reset clears ownership and rotates node-side key material so the previous
    // organization cannot leak into the new identity (production NodeIdentity
    // rotation). Collector (phone-side) intentionally survives: old batches
    // stay keyed by the old node_id and never satisfy the new watermark.
    owner_.clear();
    node_keys_.clear();
    claim_mode_.enabled = false;
    refresh_owner();
    rebuild_ble_session();
    return true;
}

void ScenarioRunner::reset_fault_counters() {
    observations_.reset_counters();
    identity_.reset_counters();
}

void ScenarioRunner::disarm_all_faults() {
    observations_.disarm();
    identity_.disarm();
    crash_armed_ = false;
}

void ScenarioRunner::check_crash_expected(const std::string& /*op_name*/) {
    bool identity_crash = identity_.has_crash_armed();
    bool obs_crash = observations_.has_crash_armed();
    crash_expected_ = crash_armed_ || identity_crash || obs_crash;
    crash_armed_ = false;
}

bool ScenarioRunner::execute_event(const ScenarioEvent& event) {
    ++events_executed_;

    // Events that touch durable state (may trigger faults).
    // Counters reset so fail/crash_at(N) means "Nth attempt within the next
    // consuming operation". Faults persist across unrelated mutating ops
    // until the consuming operation triggers them; they are cleared on
    // trigger or on PowerLoss resume, not after every mutating event.
    bool is_mutating = (event.type == "boot" || event.type == "reboot" ||
                        event.type == "factory_reset" || event.type == "read" ||
                        event.type == "expect_read" || event.type == "ack" ||
                        event.type == "expect_ack" ||
                        event.type == "sync_batch" || event.type == "sync_ack" ||
                        event.type == "sync_compact" || event.type == "sync_claim" ||
                        event.type == "phone_connect" || event.type == "ble_connect" ||
                        event.type == "phone_disconnect" || event.type == "ble_disconnect" ||
                        event.type == "disconnect" || event.type == "reconnect" ||
                        event.type == "ble_reconnect" || event.type == "hello" ||
                        event.type == "provide_credential" || event.type == "auth" ||
                        event.type == "credential" || event.type == "claim_as" ||
                        event.type == "claim" || event.type == "import_fixture_node" ||
                        event.type == "fixture_node" || event.type == "collector_persist" ||
                        event.type == "persist_collector_batch" || event.type == "send_ack" ||
                        event.type == "collector_ack" || event.type == "drop_ack" ||
                        event.type == "collector_drop_ack" || event.type == "ble_batch" ||
                        event.type == "request_batch" || event.type == "ble_ack" ||
                        event.type == "ble_compact" || event.type == "ble_time" ||
                        event.type == "node_proof" || event.type == "claim_mode_enter" ||
                        event.type == "claim_mode_exit");

    if (is_mutating) {
        reset_fault_counters();
        check_crash_expected(event.type);
    }

    try {
        if (event.type == "boot") return execute_boot();
        if (event.type == "reboot") return execute_reboot();
        if (event.type == "factory_reset") return execute_factory_reset();
        if (event.type == "advance_ms") return execute_advance_ms(event.args[0]);
        if (event.type == "set_ms") return execute_set_ms(event.args[0]);
        if (event.type == "rtc") return execute_rtc(event.args);
        if (event.type == "rtc_invalid") return execute_rtc_invalid();
        if (event.type == "rtc_correct_ms") return execute_rtc_correct_ms(event.args[0]);
        if (event.type == "read") return execute_read(event.args[0]);
        if (event.type == "read_absent") return execute_read_absent();
        if (event.type == "expect_read") return execute_expect_read(event.args);
        if (event.type == "ack") return execute_ack(event.args[0]);
        if (event.type == "expect_ack") return execute_expect_ack(event.args);
        if (event.type == "storage_available") return execute_storage_available();
        if (event.type == "storage_unavailable") return execute_storage_unavailable();
        if (event.type == "fail_load_next") return execute_fail_load_next();
        if (event.type == "fail_identity_store") return execute_fail_identity_store(event.args[0]);
        if (event.type == "crash_identity_store") return execute_crash_identity_store(event.args[0]);
        if (event.type == "fail_append") return execute_fail_append(event.args[0]);
        if (event.type == "crash_append") return execute_crash_append(event.args[0]);
        if (event.type == "fail_clear_next") return execute_fail_clear_next();
        if (event.type == "crash_clear_next") return execute_crash_clear_next();
        if (event.type == "fail_watermark_next") return execute_fail_watermark_next();
        if (event.type == "fail_prune_next") return execute_fail_prune_next();
        if (event.type == "crash_prune_next") return execute_crash_prune_next();
        if (event.type == "sync_batch") return execute_sync_batch(event.args);
        if (event.type == "sync_ack") return execute_sync_ack(event.args);
        if (event.type == "sync_compact") return execute_sync_compact();
        if (event.type == "sync_status") return execute_sync_status();
        if (event.type == "sync_claim") return execute_sync_claim();
        if (event.type == "phone_connect" || event.type == "ble_connect")
            return execute_phone_connect(event.args);
        if (event.type == "phone_disconnect" || event.type == "ble_disconnect" ||
            event.type == "disconnect")
            return execute_phone_disconnect();
        if (event.type == "reconnect" || event.type == "ble_reconnect")
            return execute_reconnect(event.args);
        if (event.type == "hello") return execute_hello(event.args);
        if (event.type == "owner") return execute_owner();
        if (event.type == "provide_credential" || event.type == "auth" ||
            event.type == "credential")
            return execute_provide_credential(event.args);
        if (event.type == "claim_mode_enter") return execute_claim_mode("enter");
        if (event.type == "claim_mode_exit") return execute_claim_mode("exit");
        if (event.type == "claim_as" || event.type == "claim")
            return execute_claim_as(event.args);
        if (event.type == "import_fixture_node" || event.type == "fixture_node")
            return execute_import_fixture_node(event.args);
        if (event.type == "collector_persist" || event.type == "persist_collector_batch")
            return execute_collector_persist(event.args);
        if (event.type == "send_ack" || event.type == "collector_ack")
            return execute_send_ack(event.args);
        if (event.type == "drop_ack" || event.type == "collector_drop_ack")
            return execute_drop_ack();
        if (event.type == "ble_status") return execute_ble_status();
        if (event.type == "ble_batch" || event.type == "request_batch")
            return execute_ble_batch(event.args);
        if (event.type == "ble_ack") return execute_ble_ack(event.args);
        if (event.type == "ble_compact") return execute_ble_compact();
        if (event.type == "ble_time") return execute_ble_time(event.args);
        if (event.type == "node_proof") return execute_node_proof(event.args);
        if (event.type == "ble_version") return execute_ble_version(event.args);
        if (event.type == "ble_malformed") return execute_ble_malformed();
        if (event.type == "now") return execute_now(event.args[0]);
        if (event.type == "sync_start") return execute_sync_start();
        if (event.type == "sync_complete") return execute_sync_complete();
        if (event.type == "assert") return execute_assert(event.args);
        return false; // unknown event type (should not reach here, parser validates)
    } catch (const PowerLoss&) {
        if (crash_expected_) {
            destroy_core(); // volatile state lost
            crash_expected_ = false;
            disarm_all_faults();
            return true;
        }
        // Unexpected power loss
        fail(event.type, "no crash expected", "PowerLoss", "unexpected power loss");
        return false;
    }
}

bool ScenarioRunner::execute_boot() {
    return boot_core();
}

bool ScenarioRunner::execute_reboot() {
    return reboot_core();
}

bool ScenarioRunner::execute_factory_reset() {
    return factory_reset_core();
}

bool ScenarioRunner::execute_advance_ms(const std::string& arg) {
    std::uint64_t delta;
    if (!miezmerker::sim::parse_uint64(arg, delta)) {
        fail("advance_ms", "valid uint64", arg, "parse delta");
        return false;
    }
    clock_.advance_monotonic_ms(delta);
    return true;
}

bool ScenarioRunner::execute_set_ms(const std::string& arg) {
    std::uint64_t value;
    if (!miezmerker::sim::parse_uint64(arg, value)) {
        fail("set_ms", "valid uint64", arg, "parse value");
        return false;
    }
    clock_.set_monotonic_ms(value);
    return true;
}

bool ScenarioRunner::execute_rtc(const std::vector<std::string>& args) {
    std::string status_str = args[0];
    ClockStatus status;
    if (status_str == "SYNCED") status = ClockStatus::SYNCED;
    else if (status_str == "RTC_ONLY") status = ClockStatus::RTC_ONLY;
    else if (status_str == "UNKNOWN") status = ClockStatus::UNKNOWN;
    else {
        fail("rtc", "SYNCED|RTC_ONLY|UNKNOWN", status_str, "parse status");
        return false;
    }
    if (status == ClockStatus::UNKNOWN) {
        if (args.size() > 1) {
            fail("rtc UNKNOWN", "no epoch argument", args[1], "extra argument");
            return false;
        }
        rtc_.invalidate();
        return true;
    }
    if (args.size() != 2) {
        fail("rtc SYNCED|RTC_ONLY", "1 epoch argument", std::to_string(args.size() - 1), "argument count");
        return false;
    }
    std::uint64_t epoch;
    if (!miezmerker::sim::parse_uint64(args[1], epoch)) {
        fail("rtc epoch", "valid uint64", args[1], "parse epoch");
        return false;
    }
    if (status == ClockStatus::SYNCED) rtc_.set_synced(epoch);
    else rtc_.set_rtc_only(epoch);
    return true;
}

bool ScenarioRunner::execute_rtc_invalid() {
    rtc_.invalidate();
    return true;
}

bool ScenarioRunner::execute_rtc_correct_ms(const std::string& arg) {
    std::uint64_t epoch;
    if (!miezmerker::sim::parse_uint64(arg, epoch)) {
        fail("rtc_correct_ms", "valid uint64", arg, "parse epoch");
        return false;
    }
    rtc_.correct(epoch);
    return true;
}

bool ScenarioRunner::execute_read(const std::string& chip_id) {
    if (!core_) {
        fail("read", "booted core", "null", "core not initialized");
        return false;
    }
    reader_.present(chip_id);
    auto result = core_->poll_reader(reader_);
    if (result) last_result_ = *result;
    return true;
}

bool ScenarioRunner::execute_read_absent() {
    if (!core_) {
        fail("read_absent", "booted core", "null", "core not initialized");
        return false;
    }
    reader_.absent();
    auto result = core_->poll_reader(reader_);
    if (result) last_result_ = *result;
    return true;
}

bool ScenarioRunner::execute_expect_read(const std::vector<std::string>& args) {
    if (!execute_read(args[0])) return false;
    std::string expected = args[1];
    std::string actual = last_result_ ? format_record_result(*last_result_) : "nullopt";
    if (actual != expected) {
        fail("expect_read", expected, actual, "record result");
        return false;
    }
    return true;
}

bool ScenarioRunner::execute_ack(const std::string& arg) {
    if (!core_) {
        fail("ack", "booted core", "null", "core not initialized");
        return false;
    }
    std::uint64_t sequence;
    if (!miezmerker::sim::parse_uint64(arg, sequence)) {
        fail("ack", "valid uint64", arg, "parse sequence");
        return false;
    }
    last_ack_result_ = core_->set_ack_watermark(sequence);
    return true;
}

bool ScenarioRunner::execute_expect_ack(const std::vector<std::string>& args) {
    if (!execute_ack(args[0])) return false;
    const std::string& expected = args[1];
    bool expected_bool;
    if (expected == "true" || expected == "ok") expected_bool = true;
    else if (expected == "false" || expected == "fail") expected_bool = false;
    else {
        fail("expect_ack", "true|false|ok|fail", expected, "parse expected");
        return false;
    }
    bool actual = last_ack_result_.value_or(false);
    if (actual != expected_bool) {
        fail("expect_ack", expected_bool ? "true" : "false", actual ? "true" : "false",
             "ack result");
        return false;
    }
    return true;
}

bool ScenarioRunner::execute_storage_available() {
    storage_.set_available(true);
    return true;
}

bool ScenarioRunner::execute_storage_unavailable() {
    storage_.set_available(false);
    return true;
}

bool ScenarioRunner::execute_fail_load_next() {
    identity_.fail_next_load();
    return true;
}

bool ScenarioRunner::execute_fail_identity_store(const std::string& arg) {
    std::size_t n;
    if (!miezmerker::sim::parse_size_t(arg, n) || n == 0) {
        fail("fail_identity_store", "positive integer", arg, "parse count");
        return false;
    }
    identity_.fail_store_at(n);
    return true;
}

bool ScenarioRunner::execute_crash_identity_store(const std::string& arg) {
    std::size_t n;
    if (!miezmerker::sim::parse_size_t(arg, n) || n == 0) {
        fail("crash_identity_store", "positive integer", arg, "parse count");
        return false;
    }
    identity_.crash_store_at(n);
    crash_armed_ = true;
    return true;
}

bool ScenarioRunner::execute_fail_append(const std::string& arg) {
    std::size_t n;
    if (!miezmerker::sim::parse_size_t(arg, n) || n == 0) {
        fail("fail_append", "positive integer", arg, "parse count");
        return false;
    }
    observations_.fail_append_at(n);
    return true;
}

bool ScenarioRunner::execute_crash_append(const std::string& arg) {
    std::size_t n;
    if (!miezmerker::sim::parse_size_t(arg, n) || n == 0) {
        fail("crash_append", "positive integer", arg, "parse count");
        return false;
    }
    observations_.crash_append_at(n);
    crash_armed_ = true;
    return true;
}

bool ScenarioRunner::execute_fail_clear_next() {
    observations_.fail_next_clear();
    return true;
}

bool ScenarioRunner::execute_crash_clear_next() {
    observations_.crash_next_clear();
    crash_armed_ = true;
    return true;
}

bool ScenarioRunner::execute_fail_watermark_next() {
    observations_.fail_next_watermark();
    return true;
}

bool ScenarioRunner::execute_fail_prune_next() {
    observations_.fail_next_prune();
    return true;
}

bool ScenarioRunner::execute_crash_prune_next() {
    observations_.crash_next_prune();
    crash_armed_ = true;
    return true;
}

bool ScenarioRunner::execute_sync_batch(const std::vector<std::string>& args) {
    if (!core_) {
        fail("sync_batch", "booted core", "null", "core not initialized");
        return false;
    }
    if (args.size() < 1) { fail("sync_batch", "from_sequence", "none", "arg count"); return false; }
    std::uint64_t from_seq;
    if (!miezmerker::sim::parse_uint64(args[0], from_seq)) {
        fail("sync_batch", "valid from_sequence", args[0], "parse");
        return false;
    }
    std::uint16_t max_records = 16;
    if (args.size() >= 2) {
        std::uint64_t max_records_64;
        if (!miezmerker::sim::parse_uint64(args[1], max_records_64)) {
            fail("sync_batch", "valid max_records", args[1], "parse");
            return false;
        }
        if (max_records_64 > 65535) {
            fail("sync_batch", "max_records <= 65535", args[1], "parse");
            return false;
        }
        max_records = static_cast<std::uint16_t>(max_records_64);
    }
    if (!sync_) { fail("sync_batch", "sync_claim", "unclaimed", "no sync fixture"); return false; }
    last_batch_request_from_ = from_seq;
    last_batch_request_max_ = max_records;
    last_batch_ = sync_->server.batch(from_seq, max_records, SyncFixture::now);
    if (last_batch_->error == ble::SyncError::Ok) {
        // Exercise exactly the wire codec used by the collector as well.
        auto decoded = ble::decode_batch_response(ble::encode_batch_response(last_batch_->batch));
        if (!decoded) { fail("sync_batch", "decodable batch", "invalid", "codec"); return false; }
        last_batch_->batch = *decoded;
    }
    return true;
}

bool ScenarioRunner::execute_sync_ack(const std::vector<std::string>& args) {
    if (!core_) {
        fail("sync_ack", "booted core", "null", "core not initialized");
        return false;
    }
    if (args.size() != 1) { fail("sync_ack", "watermark", "none", "arg count"); return false; }
    std::uint64_t watermark;
    if (!miezmerker::sim::parse_uint64(args[0], watermark)) {
        fail("sync_ack", "valid watermark", args[0], "parse");
        return false;
    }
    if (!sync_) { fail("sync_ack", "sync_claim", "unclaimed", "no sync fixture"); return false; }
    last_ack_result_ = sync_->server.ack(watermark, SyncFixture::now).error == ble::SyncError::Ok;
    return true;
}

bool ScenarioRunner::execute_sync_compact() {
    if (!core_) {
        fail("sync_compact", "booted core", "null", "core not initialized");
        return false;
    }
    if (!sync_) { fail("sync_compact", "sync_claim", "unclaimed", "no sync fixture"); return false; }
    last_compact_result_ = sync_->server.compact(SyncFixture::now).error == ble::SyncError::Ok;
    return true;
}

bool ScenarioRunner::execute_sync_status() {
    if (!core_) {
        fail("sync_status", "booted core", "null", "core not initialized");
        return false;
    }
    if (!sync_) { fail("sync_status", "sync_claim", "unclaimed", "no sync fixture"); return false; }
    last_status_ = sync_->server.status(SyncFixture::now);
    if (!last_status_) { fail("sync_status", "authorized status", "none", "sync"); return false; }
    return true;
}

bool ScenarioRunner::execute_sync_claim() {
    if (!core_ || !core_->ready()) return false;
    IdentityRecord id;
    if (identity_.load(id) != IdentityLoadResult::OK) return false;
    id.claim_state = ClaimState::CLAIMED;
    if (!identity_.store(id)) return false;
    return reboot_core();
}

// --- BLE/security events (issue #7, real production path) ---

namespace {
std::array<unsigned char, 65> to_issuer_key(const SimOwnerStore& owner,
                                            const SimFixtures& fx,
                                            const std::string& node_id) {
    if (owner.has_issuer) return owner.issuer_key;
    // Pre-import default: sim issuer for sim node, vector issuer for vector.
    if (node_id == fx.vector_node_id) return fx.vector_issuer_key;
    return fx.issuer_key;
}
}  // namespace

bool ScenarioRunner::execute_phone_connect(const std::vector<std::string>& args) {
    const auto& fx = sim_fixtures();
    if (!fx.loaded) {
        fail("phone_connect", "fixtures loaded", fx.load_error, "fixtures");
        return false;
    }
    if (!core_ || !core_->ready()) {
        fail("phone_connect", "booted core", "null", "core not initialized");
        return false;
    }
    if (!ble_) rebuild_ble_session();
    if (!ble_) {
        fail("phone_connect", "ble session", "null", "no ble session");
        return false;
    }
    std::string which = args.empty() ? "fixed" : args[0];
    if (which != "fixed" && which != "alt") {
        fail("phone_connect", "fixed|alt", which, "parse challenge");
        return false;
    }
    const auto& ch = (which == "alt") ? fx.alt_challenge : fx.fixed_challenge;
    for (std::size_t i = 0; i < 32; ++i) g_next_challenge[i] = ch[i];
    g_challenge_set = true;
    // (Re)create the production session with the current owner org + issuer.
    std::array<unsigned char, 65> issuer = to_issuer_key(owner_, fx,
        core_->node_id().is_set() ? core_->node_id().to_string() : "");
    // If owner has no pinned issuer yet (pre-import), use fixture default so
    // public hello/owner + challenge still work; auth will fail closed.
    ble_->auth.session =
        std::make_unique<OfflineAuthSession>(owner_.organization_id, issuer);
    ble_->server->disconnect();
    ble_->router->disconnect();
    ble_->router->set_claim_mode(claim_mode_.enabled);
    ble_->owner_meta.organization_id = owner_.organization_id;
    ble_->owner_meta.organization_slug = owner_.organization_slug;
    ble_->owner_meta.organization_name = owner_.organization_name;
    ble_->owner_meta.public_contact = owner_.public_contact;
    auto request = ble::encode_frame(
        ble::Frame{ble::kProtocolVersion, ble::Opcode::ChallengeRequest, {}});
    auto response = ble::decode_frame(ble_->transport->exchange(request, trusted_now_s_));
    auto challenge = response && response->opcode == ble::Opcode::ChallengeResponse
        ? ble::decode_challenge_response(response->payload) : std::nullopt;
    if (!challenge) {
        fail("phone_connect", "ChallengeResponse", "invalid", "ble");
        return false;
    }
    last_challenge_ = *challenge;
    has_challenge_ = true;
    ble_connected_ = true;
    last_auth_.reset();
    return true;
}

bool ScenarioRunner::execute_phone_disconnect() {
    if (ble_) {
        ble_->server->disconnect();
        ble_->router->disconnect();
        ble_->auth.disconnect();
    }
    ble_connected_ = false;
    has_challenge_ = false;
    return true;
}

bool ScenarioRunner::execute_reconnect(const std::vector<std::string>& args) {
    if (!execute_phone_disconnect()) return false;
    return execute_phone_connect(args);
}

bool ScenarioRunner::execute_hello(const std::vector<std::string>& args) {
    if (!ble_ || !ble_connected_) {
        fail("hello", "connected", "disconnected", "ble");
        return false;
    }
    std::uint8_t client_ver = 1;
    if (!args.empty()) {
        std::uint64_t v;
        if (!parse_uint64(args[0], v) || v > 255) {
            fail("hello", "client version 0-255", args[0], "parse");
            return false;
        }
        client_ver = static_cast<std::uint8_t>(v);
    }
    auto payload = ble::encode_hello_request(client_ver, ble::kCapAllV1);
    auto req = ble::encode_frame(ble::Frame{ble::kProtocolVersion, ble::Opcode::HelloRequest, payload});
    auto rsp = ble_->transport->exchange(req, trusted_now_s_);
    auto frame = ble::decode_frame(rsp);
    if (!frame) {
        fail("hello", "decodable frame", "invalid", "codec");
        return false;
    }
    if (frame->opcode == ble::Opcode::Error) {
        auto err = ble::decode_error_payload(frame->payload);
        last_hello_error_ = err ? static_cast<std::uint64_t>(err->code)
                                : static_cast<std::uint64_t>(ble::SyncError::Internal);
        return true;
    }
    if (frame->opcode != ble::Opcode::HelloPublic) {
        fail("hello", "HelloPublic or Error", std::to_string(static_cast<int>(frame->opcode)),
             "opcode");
        return false;
    }
    last_hello_error_ = static_cast<std::uint64_t>(ble::SyncError::Ok);
    return true;
}

bool ScenarioRunner::execute_owner() {
    if (!ble_ || !ble_connected_) {
        fail("owner", "connected", "disconnected", "ble");
        return false;
    }
    auto req = ble::encode_frame(ble::Frame{ble::kProtocolVersion, ble::Opcode::OwnerRequest, {}});
    auto rsp = ble_->transport->exchange(req, trusted_now_s_);
    auto frame = ble::decode_frame(rsp);
    if (!frame || frame->opcode != ble::Opcode::OwnerResponse) {
        last_owner_error_ = static_cast<std::uint64_t>(ble::SyncError::InvalidFrame);
        if (!frame) {
            fail("owner", "decodable frame", "invalid", "codec");
            return false;
        }
        auto err = ble::decode_error_payload(frame->payload);
        last_owner_error_ = err ? static_cast<std::uint64_t>(err->code)
                                : static_cast<std::uint64_t>(ble::SyncError::Internal);
        return true;
    }
    auto info = ble::decode_owner_response(frame->payload);
    if (!info) {
        fail("owner", "decodable owner", "invalid", "codec");
        return false;
    }
    last_owner_error_ = static_cast<std::uint64_t>(ble::SyncError::Ok);
    last_owner_org_ = info->organization_id;
    last_owner_claim_ = (info->claim_state == ClaimState::CLAIMED) ? "CLAIMED" : "UNCLAIMED";
    return true;
}

bool ScenarioRunner::execute_provide_credential(const std::vector<std::string>& args) {
    const auto& fx = sim_fixtures();
    if (!fx.loaded) {
        fail("provide_credential", "fixtures loaded", fx.load_error, "fixtures");
        return false;
    }
    if (!ble_ || !ble_connected_ || !has_challenge_) {
        fail("provide_credential", "connected with challenge", "not connected", "ble");
        return false;
    }
    if (args.size() != 1) {
        fail("provide_credential", "1 argument", std::to_string(args.size()), "arg count");
        return false;
    }
    const std::string& kind = args[0];
    std::string credential;
    std::array<unsigned char, 64> proof{};
    if (kind == "member") {
        credential = fx.member_jwt;
        proof = fx.member_proof;
    } else if (kind == "admin") {
        credential = fx.admin_jwt;
        proof = fx.admin_proof;
    } else if (kind == "foreign") {
        credential = fx.foreign_jwt;
        proof = fx.foreign_proof;
    } else if (kind == "expired") {
        credential = fx.expired_jwt;
        proof = fx.member_proof;
    } else if (kind == "vector") {
        credential = fx.vector_credential_jwt;
        proof = fx.vector_proof;
    } else if (kind == "manipulated") {
        credential = fx.member_jwt;
        if (credential.size() > 12) credential[12] = (credential[12] == 'A' ? 'B' : 'A');
        proof = fx.member_proof;
    } else if (kind == "wrongkey") {
        credential = fx.member_jwt;
        proof = fx.admin_proof;
    } else if (kind == "replay") {
        credential = fx.member_jwt;
        proof = fx.member_proof;  // binds fixed challenge; fails on alt
    } else {
        fail("provide_credential", "member|admin|foreign|expired|vector|manipulated|wrongkey|replay",
             kind, "parse kind");
        return false;
    }
    std::array<std::uint8_t, 64> raw_proof{};
    for (std::size_t i = 0; i < 64; ++i) raw_proof[i] = proof[i];
    auto payload = ble::encode_auth_request(ble::AuthRequest{credential, raw_proof});
    auto req = ble::encode_frame(ble::Frame{ble::kProtocolVersion, ble::Opcode::AuthRequest, payload});
    auto rsp = ble_->transport->exchange(req, trusted_now_s_);
    auto frame = ble::decode_frame(rsp);
    if (!frame || frame->opcode != ble::Opcode::AuthResponse) {
        fail("provide_credential", "AuthResponse", "invalid", "codec");
        return false;
    }
    auto auth = ble::decode_auth_response(frame->payload);
    if (!auth) {
        fail("provide_credential", "decodable auth", "invalid", "codec");
        return false;
    }
    last_auth_ = auth->ok && auth->error == ble::SyncError::Ok;
    return true;
}

bool ScenarioRunner::execute_claim_mode(const std::string& mode) {
    claim_mode_.enabled = (mode == "enter");
    if (ble_ && ble_->router) ble_->router->set_claim_mode(claim_mode_.enabled);
    if (ble_) ble_->claim_mode = claim_mode_.enabled;
    return true;
}

bool ScenarioRunner::execute_claim_as(const std::vector<std::string>& args) {
    const auto& fx = sim_fixtures();
    if (!fx.loaded) {
        fail("claim_as", "fixtures loaded", fx.load_error, "fixtures");
        return false;
    }
    if (!core_ || !core_->ready()) {
        fail("claim_as", "booted core", "null", "core not initialized");
        return false;
    }
    if (args.size() != 1) {
        fail("claim_as", "1 argument", std::to_string(args.size()), "arg count");
        return false;
    }
    const std::string& role = args[0];
    if (role != "admin" && role != "member" && role != "foreign") {
        fail("claim_as", "admin|member|foreign", role, "parse role");
        return false;
    }
    // Backend issuance gate (mirrors backend NodeClaimTest: only ACTIVE ADMIN
    // obtains a receipt). MEMBER never receives a receipt, so the node is
    // untouched -- the failure is at issuance, not at node verification.
    if (role == "member") {
        last_claim_ = false;
        return true;
    }
    // Select the precomputed receipt for the current fixture node.
    std::string node_id = core_->node_id().is_set() ? core_->node_id().to_string() : "";
    std::string receipt;
    if (role == "admin") {
        if (node_id == fx.node_main_id) receipt = fx.receipt_a;
        else if (node_id == fx.vector_node_id) receipt = fx.vector_receipt_jwt;
        else {
            fail("claim_as", "fixture node imported", node_id, "node binding");
            last_claim_ = false;
            return false;
        }
    } else {  // foreign: same node, other org (takeover attempt)
        if (node_id == fx.node_main_id) receipt = fx.receipt_b;
        else {
            fail("claim_as", "fixture node imported", node_id, "node binding");
            last_claim_ = false;
            return false;
        }
    }
    if (receipt.empty()) {
        last_claim_ = false;
        return true;
    }
    if (!node_manager_) {
        fail("claim_as", "production NodeIdentityManager", "missing", "identity");
        return false;
    }
    claim_crypto_.set_now(trusted_now_s_);
    // The production manager becomes unready after an ambiguous failed save.
    // A retry must reload durable state before applying the receipt again.
    if (!node_manager_->initialize()) {
        last_claim_ = false;
        return true;
    }
    last_claim_ = node_manager_->apply_claim(receipt);
    if (*last_claim_) {
        // Reload capture metadata and public ownership from the same atomic
        // record. No scenario-specific claiming rules or separate owner commit.
        return reboot_core();
    }

    return true;
}

bool ScenarioRunner::execute_import_fixture_node(const std::vector<std::string>& args) {
    const auto& fx = sim_fixtures();
    if (!fx.loaded) {
        fail("import_fixture_node", "fixtures loaded", fx.load_error, "fixtures");
        return false;
    }
    if (args.size() != 1) {
        fail("import_fixture_node", "1 argument", std::to_string(args.size()), "arg count");
        return false;
    }
    const std::string& which = args[0];
    std::string node_id_str;
    std::array<unsigned char, 65> pub{};
    std::array<unsigned char, 65> issuer{};
    std::string x, y;
    if (which == "sim-main" || which == "sim") {
        node_id_str = fx.node_main_id;
        pub = fx.node_main_key;
        issuer = fx.issuer_key;
        x = fx.node_main_x;
        y = fx.node_main_y;
    } else if (which == "vector") {
        node_id_str = fx.vector_node_id;
        pub = fx.vector_node_key;
        issuer = fx.vector_issuer_key;
        x = fx.vector_node_x;
        y = fx.vector_node_y;
    } else {
        fail("import_fixture_node", "sim-main|vector", which, "parse node");
        return false;
    }
    auto parsed = NodeId::parse(node_id_str);
    if (!parsed) {
        fail("import_fixture_node", "valid UUID", node_id_str, "parse node_id");
        return false;
    }
    // Fresh incarnation via deterministic scenario entropy (like provisioning).
    std::array<std::uint8_t, 16> inc_bytes{};
    random_.fill_random(std::span<std::uint8_t>(inc_bytes.data(), inc_bytes.size()));
    bool all_zero = true;
    for (auto b : inc_bytes)
        if (b != 0) all_zero = false;
    if (all_zero) inc_bytes[0] = 0x01;
    apply_uuidv4_variant(inc_bytes);
    IdentityRecord rec;
    rec.node_id = *parsed;
    for (std::size_t i = 0; i < 16; ++i) rec.incarnation.bytes[i] = inc_bytes[i];
    rec.next_sequence = kFirstSequence;
    rec.boot_counter = 1;
    rec.claim_state = ClaimState::UNCLAIMED;
    rec.reset_pending = false;
    if (!rec.valid()) {
        fail("import_fixture_node", "valid record", node_id_str, "record");
        return false;
    }
    NodeIdentity node;
    for (std::size_t i = 0; i < 16; ++i)
        node.node_id[i] = static_cast<std::byte>(parsed->bytes[i]);
    for (std::size_t i = 0; i < 65; ++i)
        node.public_key[i] = static_cast<std::byte>(pub[i]);
    // Opaque fixture key handle: signing replays canonical fixture signatures.
    // It is not the scalar for the fixture public key.
    node.private_key[0] = std::byte{1};
    node.observations = rec;
    if (!identity_.save(node)) {
        fail("import_fixture_node", "identity stored", "store failed", "store");
        return false;
    }
    node_keys_.provisioned = true;
    node_keys_.node_id = node_id_str;
    node_keys_.public_key = pub;
    node_keys_.key_x_b64u = x;
    node_keys_.key_y_b64u = y;
    owner_.clear();
    owner_.issuer_key = issuer;
    owner_.has_issuer = true;
    claim_mode_.enabled = false;
    if (!reboot_core()) {
        fail("import_fixture_node", "rebooted", "reboot failed", "reboot");
        return false;
    }
    // Point the claim verifier at the imported trust anchor.
    claim_crypto_.set_issuer(issuer);
    return true;
}

bool ScenarioRunner::execute_collector_persist(const std::vector<std::string>& args) {
    if (!last_batch_) {
        fail("collector_persist", "prior ble_batch", "none", "no batch");
        return false;
    }
    if (args.empty()) {
        collector_.persist(last_batch_->batch.records);
        return true;
    }
    for (const auto& a : args) {
        std::uint64_t seq;
        if (!parse_uint64(a, seq)) {
            fail("collector_persist", "valid sequence", a, "parse");
            return false;
        }
        for (const auto& r : last_batch_->batch.records) {
            if (r.sequence == seq) collector_.persist_one(r);
        }
    }
    return true;
}

bool ScenarioRunner::execute_send_ack(const std::vector<std::string>& args) {
    if (!ble_ || !ble_connected_) {
        fail("send_ack", "connected", "disconnected", "ble");
        return false;
    }
    if (!core_) {
        fail("send_ack", "booted core", "null", "core");
        return false;
    }
    std::uint64_t watermark;
    if (args.empty()) {
        watermark = collector_.watermark(core_->node_id(), core_->ack_watermark());
    } else if (args.size() == 1) {
        if (!parse_uint64(args[0], watermark)) {
            fail("send_ack", "valid watermark", args[0], "parse");
            return false;
        }
    } else {
        fail("send_ack", "0 or 1 arguments", std::to_string(args.size()), "arg count");
        return false;
    }
    auto payload = ble::encode_ack_request(watermark);
    auto req = ble::encode_frame(ble::Frame{ble::kProtocolVersion, ble::Opcode::AckRequest, payload});
    auto rsp = ble_->transport->exchange(req, trusted_now_s_);
    auto frame = ble::decode_frame(rsp);
    if (!frame) {
        fail("send_ack", "decodable frame", "invalid", "codec");
        return false;
    }
    if (frame->opcode == ble::Opcode::Error) {
        auto err = ble::decode_error_payload(frame->payload);
        last_ack_error_ = err ? static_cast<std::uint64_t>(err->code)
                              : static_cast<std::uint64_t>(ble::SyncError::Internal);
        last_ack_result_ = false;
        ack_dropped_ = false;
        return true;
    }
    auto ack = ble::decode_ack_response(frame->payload);
    if (!ack) {
        fail("send_ack", "decodable ack", "invalid", "codec");
        return false;
    }
    last_ack_error_ = static_cast<std::uint64_t>(ack->error);
    last_ack_result_ = (ack->error == ble::SyncError::Ok);
    ack_dropped_ = false;
    return true;
}

bool ScenarioRunner::execute_drop_ack() {
    // Collector persisted but the ACK frame never reached the node (lost ACK
    // is a normal case; the node retransmits idempotently on retry).
    ack_dropped_ = true;
    last_ack_result_.reset();
    return true;
}

bool ScenarioRunner::execute_ble_status() {
    if (!ble_ || !ble_connected_) {
        fail("ble_status", "connected", "disconnected", "ble");
        return false;
    }
    auto req = ble::encode_frame(ble::Frame{ble::kProtocolVersion, ble::Opcode::StatusRequest, {}});
    auto rsp = ble_->transport->exchange(req, trusted_now_s_);
    auto frame = ble::decode_frame(rsp);
    if (!frame) {
        fail("ble_status", "decodable frame", "invalid", "codec");
        return false;
    }
    if (frame->opcode == ble::Opcode::Error) {
        auto err = ble::decode_error_payload(frame->payload);
        last_status_error_ = err ? static_cast<std::uint64_t>(err->code)
                                 : static_cast<std::uint64_t>(ble::SyncError::Internal);
        last_status_.reset();
        return true;
    }
    auto st = ble::decode_status_response(frame->payload);
    if (!st) {
        fail("ble_status", "decodable status", "invalid", "codec");
        return false;
    }
    last_status_ = *st;
    last_status_error_ = static_cast<std::uint64_t>(ble::SyncError::Ok);
    return true;
}

bool ScenarioRunner::execute_ble_batch(const std::vector<std::string>& args) {
    if (!ble_ || !ble_connected_) {
        fail("ble_batch", "connected", "disconnected", "ble");
        return false;
    }
    if (args.empty()) {
        fail("ble_batch", "from_sequence", "none", "arg count");
        return false;
    }
    std::uint64_t from_seq;
    if (!parse_uint64(args[0], from_seq)) {
        fail("ble_batch", "valid from_sequence", args[0], "parse");
        return false;
    }
    std::uint16_t max_records = 16;
    if (args.size() >= 2) {
        std::uint64_t m;
        if (!parse_uint64(args[1], m) || m > 65535) {
            fail("ble_batch", "max_records 0..65535", args[1], "parse");
            return false;
        }
        // 0 and >64 pass through to the real router so version/cursor
        // validation is exercised (INVALID_FRAME), not simulator-checked.
        max_records = static_cast<std::uint16_t>(m);
    }
    last_batch_request_from_ = from_seq;
    last_batch_request_max_ = max_records;
    std::optional<std::size_t> disconnect_after;
    if (args.size() == 3) {
        std::size_t offset;
        if (!parse_size_t(args[2], offset)) {
            fail("ble_batch", "disconnect byte offset", args[2], "parse");
            return false;
        }
        disconnect_after = offset;
    }
    auto payload = ble::encode_batch_request(ble::BatchRequest{from_seq, max_records});
    // encode_batch_request returns empty for from==0/max==0 (codec rejects);
    // send the raw empty payload so the router returns a real INVALID_FRAME.
    auto req = ble::encode_frame(ble::Frame{ble::kProtocolVersion, ble::Opcode::BatchRequest, payload});
    auto rsp = ble_->transport->exchange(req, trusted_now_s_, disconnect_after);
    if (ble_->transport->interrupted()) {
        last_batch_.reset();
        last_batch_error_.reset();
        return execute_phone_disconnect();
    }
    auto frame = ble::decode_frame(rsp);
    if (!frame) {
        fail("ble_batch", "decodable frame", "invalid", "codec");
        return false;
    }
    if (frame->opcode == ble::Opcode::Error) {
        auto err = ble::decode_error_payload(frame->payload);
        last_batch_error_ = err ? static_cast<std::uint64_t>(err->code)
                                : static_cast<std::uint64_t>(ble::SyncError::Internal);
        last_batch_.reset();
        return true;
    }
    auto batch = ble::decode_batch_response(frame->payload);
    if (!batch) {
        fail("ble_batch", "decodable batch", "invalid", "codec");
        return false;
    }
    ble::SyncServer::BatchResult res;
    res.batch = *batch;
    res.error = ble::SyncError::Ok;
    last_batch_ = res;
    last_batch_error_ = static_cast<std::uint64_t>(ble::SyncError::Ok);
    return true;
}

bool ScenarioRunner::execute_ble_ack(const std::vector<std::string>& args) {
    if (args.size() != 1) {
        fail("ble_ack", "1 argument", std::to_string(args.size()), "arg count");
        return false;
    }
    // Low-level explicit watermark (may be invalid by design); the
    // collector-correct path is send_ack (auto high-watermark).
    return execute_send_ack(args);
}

bool ScenarioRunner::execute_ble_compact() {
    if (!ble_ || !ble_connected_) {
        fail("ble_compact", "connected", "disconnected", "ble");
        return false;
    }
    auto req = ble::encode_frame(ble::Frame{ble::kProtocolVersion, ble::Opcode::CompactRequest, {}});
    auto rsp = ble_->transport->exchange(req, trusted_now_s_);
    auto frame = ble::decode_frame(rsp);
    if (!frame) {
        fail("ble_compact", "decodable frame", "invalid", "codec");
        return false;
    }
    if (frame->opcode == ble::Opcode::Error) {
        auto err = ble::decode_error_payload(frame->payload);
        last_compact_error_ = err ? static_cast<std::uint64_t>(err->code)
                                  : static_cast<std::uint64_t>(ble::SyncError::Internal);
        last_compact_result_ = false;
        return true;
    }
    auto c = ble::decode_compact_response(frame->payload);
    if (!c) {
        fail("ble_compact", "decodable compact", "invalid", "codec");
        return false;
    }
    last_compact_error_ = static_cast<std::uint64_t>(c->error);
    last_compact_result_ = (c->error == ble::SyncError::Ok);
    return true;
}

bool ScenarioRunner::execute_ble_time(const std::vector<std::string>& args) {
    if (!ble_ || !ble_connected_) {
        fail("ble_time", "connected", "disconnected", "ble");
        return false;
    }
    std::uint64_t epoch;
    if (!parse_uint64(args[0], epoch)) {
        fail("ble_time", "valid epoch_ms", args[0], "parse");
        return false;
    }
    auto payload = ble::encode_time_request(epoch);
    auto req = ble::encode_frame(ble::Frame{ble::kProtocolVersion, ble::Opcode::TimeRequest, payload});
    auto rsp = ble_->transport->exchange(req, trusted_now_s_);
    auto frame = ble::decode_frame(rsp);
    if (!frame) {
        fail("ble_time", "decodable frame", "invalid", "codec");
        return false;
    }
    if (frame->opcode == ble::Opcode::Error) {
        last_time_ = false;
        return true;
    }
    auto t = ble::decode_time_response(frame->payload);
    if (!t) {
        fail("ble_time", "decodable time", "invalid", "codec");
        return false;
    }
    last_time_ = t->ok;
    return true;
}

bool ScenarioRunner::execute_node_proof(const std::vector<std::string>& args) {
    const auto& fx = sim_fixtures();
    if (!ble_ || !ble_connected_) {
        fail("node_proof", "connected", "disconnected", "ble");
        return false;
    }
    std::string which = args.empty() ? "fixed" : args[0];
    if (which != "fixed" && which != "fake") {
        fail("node_proof", "fixed|fake", which, "parse");
        return false;
    }
    if (which == "fake") ble_->signer.force_fail = true;
    auto payload = ble::encode_node_proof_request(fx.session_nonce);
    auto req = ble::encode_frame(
        ble::Frame{ble::kProtocolVersion, ble::Opcode::NodeProofRequest, payload});
    auto rsp = ble_->transport->exchange(req, trusted_now_s_);
    ble_->signer.force_fail = false;
    auto frame = ble::decode_frame(rsp);
    if (!frame) {
        fail("node_proof", "decodable frame", "invalid", "codec");
        return false;
    }
    if (frame->opcode == ble::Opcode::Error) {
        last_node_proof_ = false;
        return true;
    }
    auto sig = ble::decode_node_proof_response(frame->payload);
    if (!sig) {
        fail("node_proof", "decodable proof", "invalid", "codec");
        return false;
    }
    // Real collector-side verification with the pinned node key (mirrors PWA
    // WebCrypto in #8; same MM-NODE-SESSION-v1 message as firmware).
    std::string node_id = core_ && core_->node_id().is_set() ? core_->node_id().to_string() : "";
    std::array<unsigned char, 64> raw_sig{};
    for (std::size_t i = 0; i < 64; ++i) raw_sig[i] = (*sig)[i];
    bool verified = false;
    if (node_keys_.provisioned && node_id == fx.node_main_id) {
        verified = SimCollector::verify_node_proof(fx.node_main_key, node_id, fx.session_nonce,
                                                   raw_sig);
    }
    last_node_proof_ = verified;
    return true;
}

bool ScenarioRunner::execute_ble_version(const std::vector<std::string>& args) {
    if (!ble_ || !ble_connected_) {
        fail("ble_version", "connected", "disconnected", "ble");
        return false;
    }
    std::uint64_t v;
    if (!parse_uint64(args[0], v) || v > 255) {
        fail("ble_version", "version 0-255", args[0], "parse");
        return false;
    }
    auto payload = ble::encode_hello_request(1, ble::kCapAllV1);
    auto req = ble::encode_frame(
        ble::Frame{static_cast<std::uint8_t>(v), ble::Opcode::HelloRequest, payload});
    auto rsp = ble_->transport->exchange(req, trusted_now_s_);
    auto frame = ble::decode_frame(rsp);
    if (!frame || frame->opcode != ble::Opcode::Error) {
        fail("ble_version", "Error frame", "unexpected", "version gating");
        return false;
    }
    auto err = ble::decode_error_payload(frame->payload);
    last_frame_error_ = err ? static_cast<std::uint64_t>(err->code)
                            : static_cast<std::uint64_t>(ble::SyncError::Internal);
    return true;
}

bool ScenarioRunner::execute_ble_malformed() {
    if (!ble_ || !ble_connected_) {
        fail("ble_malformed", "connected", "disconnected", "ble");
        return false;
    }
    std::vector<std::uint8_t> truncated{0x01, 0x0D};
    auto rsp = ble_->transport->exchange(truncated, trusted_now_s_);
    auto frame = ble::decode_frame(rsp);
    if (!frame || frame->opcode != ble::Opcode::Error) {
        fail("ble_malformed", "Error frame", "unexpected", "framing");
        return false;
    }
    auto err = ble::decode_error_payload(frame->payload);
    last_frame_error_ = err ? static_cast<std::uint64_t>(err->code)
                            : static_cast<std::uint64_t>(ble::SyncError::Internal);
    return true;
}

bool ScenarioRunner::execute_now(const std::string& arg) {
    std::uint64_t v;
    if (!parse_uint64(arg, v) ||
        v > static_cast<std::uint64_t>(std::numeric_limits<ble::TrustedEpochSeconds>::max())) {
        fail("now", "epoch seconds in signed 64-bit range", arg, "parse");
        return false;
    }
    trusted_now_s_ = static_cast<ble::TrustedEpochSeconds>(v);
    return true;
}

bool ScenarioRunner::execute_sync_start() {
    if (!ble_ || !ble_connected_) {
        fail("sync_start", "connected", "disconnected", "ble");
        return false;
    }
    if (!ble_->auth.can_sync(trusted_now_s_)) {
        fail("sync_start", "authorized session", "unauthorized", "auth");
        return false;
    }
    return true;
}

bool ScenarioRunner::execute_sync_complete() {
    if (!ble_ || !ble_connected_) {
        fail("sync_complete", "connected", "disconnected", "ble");
        return false;
    }
    if (!ble_->auth.can_sync(trusted_now_s_)) {
        fail("sync_complete", "authorized session", "unauthorized", "auth");
        return false;
    }
    auto st = ble_->server->status(trusted_now_s_);
    if (!st) {
        fail("sync_complete", "authorized status", "none", "sync");
        return false;
    }
    if (st->pending != 0) {
        fail("sync_complete", "pending 0", std::to_string(st->pending), "pending");
        return false;
    }
    return true;
}

bool ScenarioRunner::execute_assert(const std::vector<std::string>& args) {
    if (!core_) {
        fail("assert", "booted core", "null", "core not initialized");
        return false;
    }

    std::string what = args[0];

    if (what == "batch_count" || what == "batch_cursor" || what == "batch_error" ||
        what == "sync_pending") {
        if (args.size() != 2) return false;
        std::uint64_t expected;
        if (!parse_uint64(args[1], expected)) return false;
        if (what == "sync_pending") {
            if (!last_status_) return false;
            return expect_eq(last_status_->pending, expected, what);
        }
        if (!last_batch_) return false;
        if (what == "batch_count") return expect_eq(last_batch_->batch.records.size(), expected, what);
        if (what == "batch_cursor") return expect_eq(last_batch_->batch.next_cursor, expected, what);
        return expect_eq(static_cast<std::uint64_t>(last_batch_->error), expected, what);
    }

    if (what == "observations") {
        if (args.size() != 2) { fail("assert observations", "1 argument", std::to_string(args.size() - 1), "arg count"); return false; }
        std::size_t expected; if (!miezmerker::sim::parse_size_t(args[1], expected)) { fail("assert observations", "uint", args[1], "parse"); return false; }
        return expect_eq(core_->observation_count(), expected, "observations");
    }
    if (what == "next_sequence") {
        if (args.size() != 2) { fail("assert next_sequence", "1 argument", std::to_string(args.size() - 1), "arg count"); return false; }
        std::uint64_t expected; if (!miezmerker::sim::parse_uint64(args[1], expected)) { fail("assert next_sequence", "uint64", args[1], "parse"); return false; }
        return expect_eq(core_->next_sequence(), expected, "next_sequence");
    }
    if (what == "boot_counter") {
        if (args.size() != 2) { fail("assert boot_counter", "1 argument", std::to_string(args.size() - 1), "arg count"); return false; }
        std::uint64_t expected; if (!miezmerker::sim::parse_uint64(args[1], expected)) { fail("assert boot_counter", "uint32", args[1], "parse"); return false; }
        return expect_eq(core_->boot_counter(), expected, "boot_counter");
    }
    if (what == "store_status") {
        if (args.size() != 2) { fail("assert store_status", "1 argument", std::to_string(args.size() - 1), "arg count"); return false; }
        return expect_eq(format_store_status(core_->store_status()), args[1], "store_status");
    }
    if (what == "last_result") {
        if (args.size() != 2) { fail("assert last_result", "1 argument", std::to_string(args.size() - 1), "arg count"); return false; }
        std::string actual = last_result_ ? format_record_result(*last_result_) : "nullopt";
        return expect_eq(actual, args[1], "last_result");
    }
    if (what == "last_ack") {
        if (args.size() != 2) { fail("assert last_ack", "1 argument", std::to_string(args.size() - 1), "arg count"); return false; }
        if (!last_ack_result_) { fail("assert last_ack", args[1], "nullopt", "no prior ack"); return false; }
        bool expected;
        if (args[1] == "true" || args[1] == "ok") expected = true;
        else if (args[1] == "false" || args[1] == "fail") expected = false;
        else { fail("assert last_ack", "true|false|ok|fail", args[1], "parse expected"); return false; }
        return expect_eq(*last_ack_result_, expected, "last_ack");
    }
    if (what == "ack_watermark") {
        if (args.size() != 2) { fail("assert ack_watermark", "1 argument", std::to_string(args.size() - 1), "arg count"); return false; }
        std::uint64_t expected; if (!miezmerker::sim::parse_uint64(args[1], expected)) { fail("assert ack_watermark", "uint64", args[1], "parse"); return false; }
        return expect_eq(core_->ack_watermark(), expected, "ack_watermark");
    }
    if (what == "ready") {
        if (args.size() != 1) { fail("assert ready", "0 arguments", std::to_string(args.size() - 1), "arg count"); return false; }
        return expect_eq(core_->ready(), true, "ready");
    }
    if (what == "not_ready") {
        if (args.size() != 1) { fail("assert not_ready", "0 arguments", std::to_string(args.size() - 1), "arg count"); return false; }
        return expect_eq(core_->ready(), false, "not_ready");
    }
    if (what == "node_id") {
        if (args.size() != 2) { fail("assert node_id", "1 argument", std::to_string(args.size() - 1), "arg count"); return false; }
        if (args[1] == "unchanged") {
            if (!last_node_id_) { fail("assert node_id unchanged", "previous node_id", "none", "no prior boot"); return false; }
            return expect_eq(core_->node_id(), *last_node_id_, "node_id unchanged");
        }
        if (args[1] == "changed") {
            if (!previous_node_id_) { fail("assert node_id changed", "previous node_id", "none", "no prior factory_reset"); return false; }
            return expect_eq(core_->node_id() != *previous_node_id_, true, "node_id changed");
        }
        // explicit UUID
        auto expected = NodeId::parse(args[1]);
        if (!expected) { fail("assert node_id", "valid UUID", args[1], "parse UUID"); return false; }
        return expect_eq(core_->node_id(), *expected, "node_id");
    }
    if (what == "incarnation") {
        if (args.size() != 2) { fail("assert incarnation", "1 argument", std::to_string(args.size() - 1), "arg count"); return false; }
        if (args[1] == "unchanged") {
            if (!last_incarnation_) { fail("assert incarnation unchanged", "previous incarnation", "none", "no prior boot"); return false; }
            return expect_eq(core_->incarnation(), *last_incarnation_, "incarnation unchanged");
        }
        if (args[1] == "changed") {
            if (!previous_incarnation_) { fail("assert incarnation changed", "previous incarnation", "none", "no prior factory_reset"); return false; }
            return expect_eq(core_->incarnation() != *previous_incarnation_, true, "incarnation changed");
        }
        auto expected = IncarnationId::parse(args[1]);
        if (!expected) { fail("assert incarnation", "valid UUID", args[1], "parse UUID"); return false; }
        return expect_eq(core_->incarnation(), *expected, "incarnation");
    }
    if (what == "observation") {
        // assert observation <idx> <field> <expected>
        if (args.size() != 4) { fail("assert observation", "3 arguments", std::to_string(args.size() - 1), "arg count"); return false; }
        std::size_t idx; if (!miezmerker::sim::parse_size_t(args[1], idx)) { fail("assert observation idx", "uint", args[1], "parse"); return false; }
        return expect_seq("observation[" + std::to_string(idx) + "]", idx, args[2], args[3]);
    }
    if (what == "sequences_unique") {
        if (args.size() != 1) { fail("assert sequences_unique", "0 arguments", std::to_string(args.size() - 1), "arg count"); return false; }
        auto obs = core_->load_observations();
        std::vector<std::pair<NodeId, Sequence>> pairs;
        pairs.reserve(obs.size());
        for (const auto& o : obs) pairs.emplace_back(o.node_id, o.sequence);
        std::sort(pairs.begin(), pairs.end());
        for (std::size_t i = 1; i < pairs.size(); ++i) {
            if (pairs[i] == pairs[i - 1]) {
                fail("assert sequences_unique", "no duplicates", "duplicate (node_id, sequence)", "duplicate check");
                return false;
            }
        }
        return true;
    }
    if (what == "sequences_monotonic") {
        if (args.size() != 1) { fail("assert sequences_monotonic", "0 arguments", std::to_string(args.size() - 1), "arg count"); return false; }
        auto obs = core_->load_observations();
        std::uint64_t last_seq = 0;
        NodeId last_node;
        bool has_last = false;
        for (const auto& o : obs) {
            if (has_last && o.node_id == last_node) {
                if (o.sequence <= last_seq) {
                    fail("assert sequences_monotonic", "strictly increasing",
                         "sequence " + std::to_string(o.sequence) + " <= " + std::to_string(last_seq),
                         "monotonic check");
                    return false;
                }
            }
            last_seq = o.sequence;
            last_node = o.node_id;
            has_last = true;
        }
        return true;
    }
    if (what == "last_batch_request_from") {
        if (args.size() != 2) { fail("assert last_batch_request_from", "1 argument", std::to_string(args.size() - 1), "arg count"); return false; }
        std::uint64_t expected; if (!miezmerker::sim::parse_uint64(args[1], expected)) { fail("assert last_batch_request_from", "uint64", args[1], "parse"); return false; }
        return expect_eq(last_batch_request_from_, expected, "last_batch_request_from");
    }
    if (what == "last_batch_request_max") {
        if (args.size() != 2) { fail("assert last_batch_request_max", "1 argument", std::to_string(args.size() - 1), "arg count"); return false; }
        std::uint64_t expected64; if (!miezmerker::sim::parse_uint64(args[1], expected64)) { fail("assert last_batch_request_max", "uint16", args[1], "parse"); return false; }
        if (expected64 > 65535) { fail("assert last_batch_request_max", "uint16 <= 65535", args[1], "parse"); return false; }
        return expect_eq(static_cast<std::uint64_t>(last_batch_request_max_), expected64, "last_batch_request_max");
    }
    if (what == "last_compact") {
        if (args.size() != 2) { fail("assert last_compact", "1 argument", std::to_string(args.size() - 1), "arg count"); return false; }
        if (!last_compact_result_) { fail("assert last_compact", args[1], "nullopt", "no prior compact"); return false; }
        bool expected;
        if (args[1] == "true" || args[1] == "ok") expected = true;
        else if (args[1] == "false" || args[1] == "fail") expected = false;
        else { fail("assert last_compact", "true|false|ok|fail", args[1], "parse expected"); return false; }
        return expect_eq(*last_compact_result_, expected, "last_compact");
    }
    // --- BLE/security assertions (issue #7) ---
    if (what == "ble_connected") {
        if (args.size() != 1) { fail("assert ble_connected", "0 args", std::to_string(args.size()-1), "arg count"); return false; }
        return expect_eq(ble_connected_, true, "ble_connected");
    }
    if (what == "ble_disconnected") {
        if (args.size() != 1) { fail("assert ble_disconnected", "0 args", std::to_string(args.size()-1), "arg count"); return false; }
        return expect_eq(ble_connected_, false, "ble_disconnected");
    }
    if (what == "ble_authorized" || what == "ble_unauthorized") {
        if (args.size() != 1) { fail("assert ble_auth", "0 args", std::to_string(args.size()-1), "arg count"); return false; }
        bool expected = (what == "ble_authorized");
        bool actual = ble_ && ble_->auth.can_sync(trusted_now_s_);
        return expect_eq(actual, expected, what);
    }
    if (what == "last_auth" || what == "last_claim" || what == "last_node_proof" ||
        what == "last_time") {
        if (args.size() != 2) { fail("assert last_*", "1 argument", std::to_string(args.size()-1), "arg count"); return false; }
        const std::optional<bool>* slot = nullptr;
        if (what == "last_auth") slot = &last_auth_;
        else if (what == "last_claim") slot = &last_claim_;
        else if (what == "last_node_proof") slot = &last_node_proof_;
        else slot = &last_time_;
        if (!*slot) { fail("assert " + what, args[1], "nullopt", "no prior result"); return false; }
        bool expected;
        if (args[1] == "true" || args[1] == "ok") expected = true;
        else if (args[1] == "false" || args[1] == "fail") expected = false;
        else { fail("assert " + what, "true|false|ok|fail", args[1], "parse"); return false; }
        return expect_eq(**slot, expected, what);
    }
    if (what == "claim_state") {
        if (args.size() != 2) { fail("assert claim_state", "1 argument", std::to_string(args.size()-1), "arg count"); return false; }
        std::string actual = (core_->claim_state() == ClaimState::CLAIMED) ? "CLAIMED" : "UNCLAIMED";
        return expect_eq(actual, args[1], "claim_state");
    }
    if (what == "owner_org") {
        if (args.size() != 2) { fail("assert owner_org", "1 argument", std::to_string(args.size()-1), "arg count"); return false; }
        std::string actual = owner_.claimed ? owner_.organization_id : "empty";
        if (args[1] == "empty") return expect_eq(actual, std::string("empty"), "owner_org");
        return expect_eq(actual, args[1], "owner_org");
    }
    if (what == "last_owner_org") {
        if (args.size() != 2) { fail("assert last_owner_org", "1 argument", std::to_string(args.size()-1), "arg count"); return false; }
        if (!last_owner_org_) { fail("assert last_owner_org", args[1], "nullopt", "no prior owner"); return false; }
        return expect_eq(*last_owner_org_, args[1], "last_owner_org");
    }
    if (what == "collector_persisted" || what == "collector_count") {
        if (args.size() != 2) { fail("assert collector", "1 argument", std::to_string(args.size()-1), "arg count"); return false; }
        std::uint64_t expected; if (!parse_uint64(args[1], expected)) { fail("assert collector", "uint", args[1], "parse"); return false; }
        return expect_eq(static_cast<std::uint64_t>(collector_.size()), expected, what);
    }
    if (what == "collector_watermark") {
        if (args.size() != 2) { fail("assert collector_watermark", "1 argument", std::to_string(args.size()-1), "arg count"); return false; }
        std::uint64_t expected; if (!parse_uint64(args[1], expected)) { fail("assert collector_watermark", "uint", args[1], "parse"); return false; }
        std::uint64_t base = core_ ? core_->ack_watermark() : 0;
        return expect_eq(collector_.watermark(core_->node_id(), base), expected, "collector_watermark");
    }
    if (what == "collector_has" || what == "collector_missing") {
        if (args.size() != 2) { fail("assert collector_has", "1 argument", std::to_string(args.size()-1), "arg count"); return false; }
        std::uint64_t seq; if (!parse_uint64(args[1], seq)) { fail("assert collector_has", "uint", args[1], "parse"); return false; }
        bool has = collector_.has(core_->node_id(), seq);
        bool expected = (what == "collector_has");
        return expect_eq(has, expected, what + " " + args[1]);
    }
    if (what == "claim_mode") {
        if (args.size() != 2) { fail("assert claim_mode", "1 argument", std::to_string(args.size()-1), "arg count"); return false; }
        std::string actual = claim_mode_.enabled ? "on" : "off";
        return expect_eq(actual, args[1], "claim_mode");
    }
    if (what == "ble_error" || what == "last_hello_error" || what == "last_batch_error" ||
        what == "last_ack_error" || what == "last_status_error" || what == "last_compact_error" ||
        what == "last_frame_error" || what == "last_owner_error") {
        if (args.size() != 2) { fail("assert ble_error", "1 argument", std::to_string(args.size()-1), "arg count"); return false; }
        std::uint64_t expected; if (!parse_uint64(args[1], expected)) { fail("assert ble_error", "uint", args[1], "parse"); return false; }
        std::optional<std::uint64_t> slot;
        if (what == "ble_error" || what == "last_hello_error") slot = last_hello_error_;
        else if (what == "last_batch_error") slot = last_batch_error_;
        else if (what == "last_ack_error") slot = last_ack_error_;
        else if (what == "last_status_error") slot = last_status_error_;
        else if (what == "last_compact_error") slot = last_compact_error_;
        else if (what == "last_owner_error") slot = last_owner_error_;
        else slot = last_frame_error_;
        if (!slot) { fail("assert " + what, args[1], "nullopt", "no prior ble op"); return false; }
        return expect_eq(*slot, expected, what);
    }

    fail("assert", "known assertion type", what, "unknown assertion");
    return false;
}

bool ScenarioRunner::expect_eq(std::uint64_t actual, std::uint64_t expected, const std::string& what) {
    if (actual == expected) return true;
    fail(what, std::to_string(expected), std::to_string(actual), what);
    return false;
}

bool ScenarioRunner::expect_eq(const std::string& actual, const std::string& expected, const std::string& what) {
    if (actual == expected) return true;
    fail(what, expected, actual, what);
    return false;
}

bool ScenarioRunner::expect_eq(bool actual, bool expected, const std::string& what) {
    if (actual == expected) return true;
    fail(what, expected ? "true" : "false", actual ? "true" : "false", what);
    return false;
}

bool ScenarioRunner::expect_eq(const NodeId& actual, const NodeId& expected, const std::string& what) {
    if (actual == expected) return true;
    fail(what, expected.to_string(), actual.to_string(), what);
    return false;
}

bool ScenarioRunner::expect_eq(const IncarnationId& actual, const IncarnationId& expected, const std::string& what) {
    if (actual == expected) return true;
    fail(what, expected.to_string(), actual.to_string(), what);
    return false;
}

bool ScenarioRunner::expect_seq(const std::string& what, std::size_t idx, const std::string& field, const std::string& expected) {
    auto obs = core_->load_observations();
    if (idx >= obs.size()) {
        fail(what, "index < " + std::to_string(obs.size()), std::to_string(idx), "index out of bounds");
        return false;
    }
    const auto& o = obs[idx];
    std::string actual;
    if (field == "sequence") actual = std::to_string(o.sequence);
    else if (field == "chip_id") actual = o.chip_id.value;
    else if (field == "clock_status") actual = format_clock_status(o.clock_status);
    else if (field == "epoch_ms") actual = o.observed_at_epoch_ms ? std::to_string(*o.observed_at_epoch_ms) : "null";
    else if (field == "monotonic_ms") actual = std::to_string(o.monotonic_ms);
    else if (field == "boot_counter") actual = std::to_string(o.boot_counter);
    else if (field == "node_id") actual = o.node_id.to_string();
    else {
        fail(what, "known field", field, "unknown field");
        return false;
    }
    if (actual == expected) return true;
    fail(what, expected, actual, field);
    return false;
}

void ScenarioRunner::fail(const std::string& event_desc, const std::string& expected, const std::string& actual, const std::string& what) {
    std::cerr << "FAIL scenario '" << scenario_.name << "' event " << events_executed_
              << " (" << event_desc << "): " << what << "\n"
              << "  expected: " << expected << "\n"
              << "  actual:   " << actual << "\n"
              << "  state:    " << context_state() << "\n";
}

std::string ScenarioRunner::format_clock_status(ClockStatus s) const {
    switch (s) {
        case ClockStatus::SYNCED: return "SYNCED";
        case ClockStatus::RTC_ONLY: return "RTC_ONLY";
        case ClockStatus::UNKNOWN: return "UNKNOWN";
    }
    return "UNKNOWN";
}

std::string ScenarioRunner::format_store_status(StoreStatus s) const {
    switch (s) {
        case StoreStatus::OK: return "OK";
        case StoreStatus::NEARLY_FULL: return "NEARLY_FULL";
        case StoreStatus::FULL: return "FULL";
    }
    return "OK";
}

std::string ScenarioRunner::context_state() const {
    std::ostringstream ss;
    if (core_) {
        ss << "node_id=" << (core_->node_id().is_set() ? core_->node_id().to_string() : "<unset>")
           << " next_sequence=" << core_->next_sequence()
           << " observations=" << core_->observation_count()
           << " ack=" << core_->ack_watermark()
           << " claim=" << (core_->claim_state() == ClaimState::CLAIMED ? "CLAIMED" : "UNCLAIMED")
           << " owner=" << (owner_.claimed ? owner_.organization_id : "empty")
           << " store_status=" << format_store_status(core_->store_status())
           << " boot_counter=" << core_->boot_counter()
           << " monotonic_ms=" << clock_.monotonic_ms()
           << " rtc=" << format_clock_status(rtc_.status());
        if (auto e = rtc_.epoch_ms(); e) ss << "(" << *e << ")";
        else ss << "(null)";
        ss << " ble=" << (ble_connected_ ? "connected" : "disconnected");
        bool authed = ble_ && ble_->auth.can_sync(trusted_now_s_);
        ss << "/" << (authed ? "authorized" : "unauthorized");
        ss << " claim_mode=" << (claim_mode_.enabled ? "on" : "off");
        ss << " collector=" << collector_.size()
           << " coll_wm=" << collector_.watermark(core_->node_id(), core_->ack_watermark())
           << " trusted_now=" << trusted_now_s_;
        if (ack_dropped_) ss << " ack_dropped=1";
    } else {
        ss << "core=null";
    }
    return ss.str();
}

int ScenarioRunner::run() {
    std::cout << "Running scenario: " << scenario_.name << " (format version " << scenario_.format_version << ")\n";
    for (const auto& event : scenario_.events) {
        if (!execute_event(event)) {
            return 1;
        }
        // No auto-disarm here: faults persist until the consuming operation
        // triggers them (Sim stores clear one-shot flags on trigger) or a
        // PowerLoss resume disarms. This prevents silently dropping e.g.
        // fail_append armed before an unrelated reboot.
        // Only clear a stale crash expectation when the op did not crash;
        // a still-armed crash will re-arm on the next mutating op.
        if (event.type == "boot" || event.type == "reboot" ||
            event.type == "factory_reset" || event.type == "read" ||
            event.type == "expect_read" || event.type == "ack" ||
            event.type == "expect_ack" || event.type == "sync_batch" ||
            event.type == "sync_ack" || event.type == "sync_compact" || event.type == "sync_claim" ||
            event.type == "phone_connect" || event.type == "ble_connect" ||
            event.type == "phone_disconnect" || event.type == "ble_disconnect" ||
            event.type == "disconnect" || event.type == "reconnect" ||
            event.type == "claim_as" || event.type == "claim" ||
            event.type == "import_fixture_node" || event.type == "ble_batch" ||
            event.type == "ble_ack" || event.type == "ble_compact" ||
            event.type == "send_ack" || event.type == "collector_persist") {
            crash_expected_ = false;
        }
    }
    // Check for unconsumed faults (both crash and fail). An armed fault that
    // never reaches its consuming operation is a scenario bug.
    if (crash_armed_ || identity_.has_crash_armed() || observations_.has_crash_armed() ||
        identity_.has_fail_armed() || observations_.has_fail_armed()) {
        std::cerr << "FAIL scenario '" << scenario_.name << "': armed fault never triggered\n";
        return 1;
    }
    std::cout << "PASS scenario '" << scenario_.name << "' (" << events_executed_ << " events)\n";
    return 0;
}

}  // namespace miezmerker::sim
