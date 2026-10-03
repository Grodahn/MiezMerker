#include "miezmerker/sim/scenario_runner.hpp"
#include "miezmerker/sim/parse_utils.hpp"

#include <algorithm>
#include <iostream>
#include <limits>
#include <sstream>
#include <stdexcept>

namespace miezmerker::sim {

namespace {

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

ScenarioRunner::ScenarioRunner(const Scenario& scenario)
    : scenario_(scenario),
      clock_(rtc_),
      random_(scenario.seed),
      capacity_(scenario.capacity),
      debounce_ms_(scenario.debounce_ms),
      seed_(scenario.seed) {
    observations_ = SimObservationStore(capacity_);
    identity_ = SimDeviceIdentity();
}

ScenarioRunner::~ScenarioRunner() = default;

void ScenarioRunner::destroy_core() {
    core_.reset();
}

bool ScenarioRunner::boot_core() {
    destroy_core();
    core_ = std::make_unique<Core>(clock_, storage_, observations_, identity_, random_,
                                   CoreConfig{debounce_ms_});
    if (!core_->initialize()) {
        // initialize() can fail (e.g., storage unavailable, identity load error)
        // This is not a scenario failure; assertions can check ready() state.
        return true;
    }
    last_node_id_ = core_->node_id();
    last_incarnation_ = core_->incarnation();
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
    core_ = std::make_unique<Core>(clock_, storage_, observations_, identity_, random_,
                                   CoreConfig{debounce_ms_});
    if (!core_->factory_reset()) {
        // factory_reset can fail; assertions can check ready() state.
        return true;
    }
    last_node_id_ = core_->node_id();
    last_incarnation_ = core_->incarnation();
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
                        event.type == "expect_ack");

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

bool ScenarioRunner::execute_assert(const std::vector<std::string>& args) {
    if (!core_) {
        fail("assert", "booted core", "null", "core not initialized");
        return false;
    }

    std::string what = args[0];

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
           << " store_status=" << format_store_status(core_->store_status())
           << " boot_counter=" << core_->boot_counter()
           << " monotonic_ms=" << clock_.monotonic_ms()
           << " rtc=" << format_clock_status(rtc_.status());
        if (auto e = rtc_.epoch_ms(); e) ss << "(" << *e << ")";
        else ss << "(null)";
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
            event.type == "expect_ack") {
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