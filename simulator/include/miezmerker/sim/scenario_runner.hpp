#pragma once

// Scenario runner: executes a parsed scenario against the simulator fakes
// and reports deterministic pass/fail with detailed diagnostics (issue #22).

#include <cstddef>
#include <cstdint>
#include <memory>
#include <optional>
#include <string>
#include <vector>

#include "miezmerker/core.hpp"
#include "miezmerker/sim/scenario.hpp"
#include "miezmerker/sim/sim_clock.hpp"
#include "miezmerker/sim/sim_rfid.hpp"
#include "miezmerker/sim/sim_stores.hpp"

namespace miezmerker::sim {

class ScenarioRunner {
public:
    explicit ScenarioRunner(const Scenario& scenario);
    ~ScenarioRunner();
    ScenarioRunner(const ScenarioRunner&) = delete;
    ScenarioRunner& operator=(const ScenarioRunner&) = delete;

    int run();  // 0 = pass, 1 = fail

private:
    const Scenario& scenario_;

    // Simulator fixture (survives reboots)
    SimRtc rtc_;
    SimClock clock_;
    SimStorage storage_;
    SimObservationStore observations_;
    SimDeviceIdentity identity_;
    SimRandomSource random_;
    SimRfidReader reader_;

    // Core instance (volatile, recreated on boot/reboot/factory_reset)
    std::unique_ptr<Core> core_;

    // Per-scenario configuration
    std::size_t capacity_;
    std::uint64_t debounce_ms_;
    std::uint64_t seed_;

    // Runtime state
    std::optional<NodeId> last_node_id_;      // node_id after last boot/reboot/factory_reset
    std::optional<NodeId> previous_node_id_;  // node_id before last factory_reset
    std::optional<IncarnationId> last_incarnation_;
    std::optional<IncarnationId> previous_incarnation_; // incarnation before last factory_reset
    std::optional<RecordResult> last_result_;
    std::optional<bool> last_ack_result_;
    bool crash_armed_{false};                 // whether a crash fault is armed for next operation
    bool crash_expected_{false};              // whether the current operation expects a crash
    std::size_t events_executed_{0};

    // Core lifecycle
    bool boot_core();
    bool reboot_core();
    bool factory_reset_core();
    void destroy_core();

    // Fault injection helpers
    void reset_fault_counters();
    void disarm_all_faults();
    void check_crash_expected(const std::string& op_name);

    // Event execution
    bool execute_event(const ScenarioEvent& event);
    bool execute_boot();
    bool execute_reboot();
    bool execute_factory_reset();
    bool execute_advance_ms(const std::string& arg);
    bool execute_set_ms(const std::string& arg);
    bool execute_rtc(const std::vector<std::string>& args);
    bool execute_rtc_invalid();
    bool execute_rtc_correct_ms(const std::string& arg);
    bool execute_read(const std::string& chip_id);
    bool execute_read_absent();
    bool execute_expect_read(const std::vector<std::string>& args);
    bool execute_ack(const std::string& arg);
    bool execute_expect_ack(const std::vector<std::string>& args);
    bool execute_storage_available();
    bool execute_storage_unavailable();
    bool execute_fail_load_next();
    bool execute_fail_identity_store(const std::string& arg);
    bool execute_crash_identity_store(const std::string& arg);
    bool execute_fail_append(const std::string& arg);
    bool execute_crash_append(const std::string& arg);
    bool execute_fail_clear_next();
    bool execute_crash_clear_next();
    bool execute_fail_watermark_next();
    bool execute_assert(const std::vector<std::string>& args);

    // Assertion helpers
    bool expect_eq(std::uint64_t actual, std::uint64_t expected, const std::string& what);
    bool expect_eq(const std::string& actual, const std::string& expected, const std::string& what);
    bool expect_eq(bool actual, bool expected, const std::string& what);
    bool expect_eq(const NodeId& actual, const NodeId& expected, const std::string& what);
    bool expect_eq(const IncarnationId& actual, const IncarnationId& expected, const std::string& what);
    bool expect_seq(const std::string& what, std::size_t idx, const std::string& field, const std::string& expected);
    void fail(const std::string& event_desc, const std::string& expected, const std::string& actual, const std::string& what);
    std::string context_state() const;
    std::string format_clock_status(ClockStatus status) const;
    std::string format_store_status(StoreStatus status) const;
};

}  // namespace miezmerker::sim