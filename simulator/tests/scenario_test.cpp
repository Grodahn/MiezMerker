#include <iostream>
#include <string>

#include "miezmerker/sim/scenario.hpp"
#include "miezmerker/sim/scenario_runner.hpp"

namespace {

int checks = 0;
#define CHECK(cond) do { ++checks; if (!(cond)) { \
    std::cerr << "FAIL " << __LINE__ << ": " << #cond << "\n"; std::exit(1); \
} } while (0)

void test_parse_valid() {
    const char* text = R"(
# comment
name: single-read
version: 1
capacity: 100
debounce_ms: 2000
seed: 42
description: test scenario

boot
rtc SYNCED 1700000000000
read CHIP-001
assert observations 1
assert next_sequence 2
)";
    miezmerker::sim::Scenario sc;
    auto err = miezmerker::sim::parse_scenario(text, sc);
    CHECK(err.ok());
    CHECK(sc.name == "single-read");
    CHECK(sc.format_version == "1");
    CHECK(sc.capacity == 100);
    CHECK(sc.debounce_ms == 2000);
    CHECK(sc.seed == 42);
    CHECK(sc.events.size() == 5);
    CHECK(sc.events[0].type == "boot");
    CHECK(sc.events[1].type == "rtc");
    CHECK(sc.events[1].args[0] == "SYNCED");
    CHECK(sc.events[1].args[1] == "1700000000000");
    CHECK(sc.events[2].type == "read");
    CHECK(sc.events[2].args[0] == "CHIP-001");
    CHECK(sc.events[3].type == "assert");
    CHECK(sc.events[3].args[0] == "observations");
    CHECK(sc.events[3].args[1] == "1");
    std::cout << "PASS test_parse_valid\n";
}

void test_parse_repeat() {
    const char* text = R"(
name: repeat-test
version: 1

boot
repeat 3
  read CHIP-001
  advance_ms 3000
end
assert observations 3
)";
    miezmerker::sim::Scenario sc;
    auto err = miezmerker::sim::parse_scenario(text, sc);
    CHECK(err.ok());
    // boot + 3*(read+advance) + assert = 1 + 6 + 1 = 8 events
    CHECK(sc.events.size() == 8);
    CHECK(sc.events[0].type == "boot");
    CHECK(sc.events[1].type == "read");
    CHECK(sc.events[2].type == "advance_ms");
    CHECK(sc.events[3].type == "read");
    CHECK(sc.events[4].type == "advance_ms");
    CHECK(sc.events[5].type == "read");
    CHECK(sc.events[6].type == "advance_ms");
    CHECK(sc.events[7].type == "assert");
    std::cout << "PASS test_parse_repeat\n";
}

void test_parse_errors() {
    miezmerker::sim::Scenario sc;

    // missing name
    auto err = miezmerker::sim::parse_scenario("version: 1\nboot\n", sc);
    CHECK(!err.ok());
    CHECK(err.message.find("missing required 'name'") != std::string::npos);

    // unknown header key
    err = miezmerker::sim::parse_scenario("name: x\nversion: 1\nunknown: 1\nboot\n", sc);
    CHECK(!err.ok());
    CHECK(err.message.find("unknown header key") != std::string::npos);

    // unknown event
    err = miezmerker::sim::parse_scenario("name: x\nversion: 1\nunknown_event\n", sc);
    CHECK(!err.ok());
    CHECK(err.message.find("unknown event type") != std::string::npos);

    // bad arity
    err = miezmerker::sim::parse_scenario("name: x\nversion: 1\nboot extra\n", sc);
    CHECK(!err.ok());
    CHECK(err.message.find("takes 0 arguments") != std::string::npos);

    // nested repeat
    err = miezmerker::sim::parse_scenario("name: x\nversion: 1\nrepeat 2\n  repeat 2\n  end\nend\n", sc);
    CHECK(!err.ok());
    CHECK(err.message.find("nested repeat") != std::string::npos);

    // unclosed repeat
    err = miezmerker::sim::parse_scenario("name: x\nversion: 1\nrepeat 2\n  read C\n", sc);
    CHECK(!err.ok());
    CHECK(err.message.find("unclosed repeat") != std::string::npos);

    std::cout << "PASS test_parse_errors\n";
}

void test_runner_simple() {
    const char* text = R"(
name: simple
version: 1

boot
rtc SYNCED 1700000000000
read CHIP-001
assert observations 1
assert next_sequence 2
assert last_result RECORDED
assert node_id unchanged
assert boot_counter 1
)";
    miezmerker::sim::Scenario sc;
    auto err = miezmerker::sim::parse_scenario(text, sc);
    CHECK(err.ok());

    miezmerker::sim::ScenarioRunner runner(sc);
    int result = runner.run();
    CHECK(result == 0);
    std::cout << "PASS test_runner_simple\n";
}

void test_runner_reboot() {
    const char* text = R"(
name: reboot-test
version: 1

boot
rtc SYNCED 1700000000000
read CHIP-001
reboot
assert observations 1
assert next_sequence 2
assert boot_counter 2
assert node_id unchanged
read CHIP-002
assert observations 2
assert next_sequence 3
)";
    miezmerker::sim::Scenario sc;
    auto err = miezmerker::sim::parse_scenario(text, sc);
    CHECK(err.ok());

    miezmerker::sim::ScenarioRunner runner(sc);
    int result = runner.run();
    CHECK(result == 0);
    std::cout << "PASS test_runner_reboot\n";
}

void test_runner_factory_reset() {
    const char* text = R"(
name: factory-reset-test
version: 1

boot
read CHIP-001
read CHIP-002
assert observations 2
factory_reset
assert observations 0
assert next_sequence 1
assert node_id changed
assert incarnation changed
read CHIP-003
assert observations 1
assert next_sequence 2
)";
    miezmerker::sim::Scenario sc;
    auto err = miezmerker::sim::parse_scenario(text, sc);
    CHECK(err.ok());

    miezmerker::sim::ScenarioRunner runner(sc);
    int result = runner.run();
    CHECK(result == 0);
    std::cout << "PASS test_runner_factory_reset\n";
}

void test_runner_dedup() {
    const char* text = R"(
name: dedup-test
version: 1
debounce_ms: 2000

boot
rtc SYNCED 1700000000000
read CHIP-001
assert last_result RECORDED
read CHIP-001
assert last_result DEBOUNCED
advance_ms 1000
read CHIP-001
assert last_result DEBOUNCED
advance_ms 1000
read CHIP-001
assert last_result RECORDED
assert observations 2
)";
    miezmerker::sim::Scenario sc;
    auto err = miezmerker::sim::parse_scenario(text, sc);
    CHECK(err.ok());

    miezmerker::sim::ScenarioRunner runner(sc);
    int result = runner.run();
    CHECK(result == 0);
    std::cout << "PASS test_runner_dedup\n";
}

void test_runner_invalid_rtc() {
    const char* text = R"(
name: invalid-rtc
version: 1

boot
rtc UNKNOWN
read CHIP-001
assert last_result RECORDED
assert observation 0 clock_status UNKNOWN
assert observation 0 epoch_ms null
rtc SYNCED 1700000000000
read CHIP-002
assert observation 1 clock_status SYNCED
assert observation 1 epoch_ms 1700000000000
assert observation 0 clock_status UNKNOWN
assert observation 0 epoch_ms null
)";
    miezmerker::sim::Scenario sc;
    auto err = miezmerker::sim::parse_scenario(text, sc);
    CHECK(err.ok());

    miezmerker::sim::ScenarioRunner runner(sc);
    int result = runner.run();
    CHECK(result == 0);
    std::cout << "PASS test_runner_invalid_rtc\n";
}

void test_runner_rtc_correction() {
    const char* text = R"(
name: rtc-correction
version: 1

boot
rtc SYNCED 1700000000000
read CHIP-001
assert observation 0 epoch_ms 1700000000000
rtc_correct_ms 1700000500000
read CHIP-002
assert observation 1 epoch_ms 1700000500000
assert observation 0 epoch_ms 1700000000000
assert observation 0 clock_status SYNCED
assert observation 1 clock_status SYNCED
)";
    miezmerker::sim::Scenario sc;
    auto err = miezmerker::sim::parse_scenario(text, sc);
    CHECK(err.ok());

    miezmerker::sim::ScenarioRunner runner(sc);
    int result = runner.run();
    CHECK(result == 0);
    std::cout << "PASS test_runner_rtc_correction\n";
}

void test_runner_power_loss_obs() {
    const char* text = R"(
name: power-loss-obs
version: 1

boot
read CHIP-001
crash_append 1
read CHIP-002
reboot
assert observations 2
assert next_sequence 3
assert observation 1 sequence 2
assert sequences_unique
assert sequences_monotonic
read CHIP-003
assert observations 3
)";
    miezmerker::sim::Scenario sc;
    auto err = miezmerker::sim::parse_scenario(text, sc);
    CHECK(err.ok());

    miezmerker::sim::ScenarioRunner runner(sc);
    int result = runner.run();
    CHECK(result == 0);
    std::cout << "PASS test_runner_power_loss_obs\n";
}

void test_runner_power_loss_seq() {
    const char* text = R"(
name: power-loss-seq
version: 1

boot
read CHIP-001
crash_identity_store 1
read CHIP-002
reboot
assert observations 1
assert next_sequence 3
assert observation 0 sequence 1
read CHIP-002
assert observations 2
assert next_sequence 4
assert sequences_unique
assert sequences_monotonic
)";
    miezmerker::sim::Scenario sc;
    auto err = miezmerker::sim::parse_scenario(text, sc);
    CHECK(err.ok());

    miezmerker::sim::ScenarioRunner runner(sc);
    int result = runner.run();
    CHECK(result == 0);
    std::cout << "PASS test_runner_power_loss_seq\n";
}

void test_runner_storage_full() {
    const char* text = R"(
name: storage-full
version: 1
capacity: 4

boot
rtc SYNCED 1700000000000
read C1
read C2
read C3
read C4
assert store_status FULL
assert observations 4
assert next_sequence 5
read C5
assert last_result REJECTED_FULL
assert observations 4
reboot
assert observations 4
assert store_status FULL
read C6
assert last_result REJECTED_FULL
assert observations 4
)";
    miezmerker::sim::Scenario sc;
    auto err = miezmerker::sim::parse_scenario(text, sc);
    CHECK(err.ok());

    miezmerker::sim::ScenarioRunner runner(sc);
    int result = runner.run();
    CHECK(result == 0);
    std::cout << "PASS test_runner_storage_full\n";
}

void test_runner_storage_nearly_full() {
    const char* text = R"(
name: storage-nearly-full
version: 1
capacity: 10

boot
rtc SYNCED 1700000000000
repeat 9
  read CHIP
  advance_ms 3000
end
assert store_status NEARLY_FULL
read CHIP
assert store_status FULL
assert observations 10
assert next_sequence 11
)";
    miezmerker::sim::Scenario sc;
    auto err = miezmerker::sim::parse_scenario(text, sc);
    CHECK(err.ok());

    miezmerker::sim::ScenarioRunner runner(sc);
    int result = runner.run();
    CHECK(result == 0);
    std::cout << "PASS test_runner_storage_nearly_full\n";
}

void test_runner_no_silent_deletion() {
    const char* text = R"(
name: no-silent-deletion
version: 1
capacity: 10

boot
rtc SYNCED 1700000000000
repeat 5
  read CHIP
  advance_ms 3000
end
assert observations 5
assert ack_watermark 0
reboot
assert observations 5
assert ack_watermark 0
# fill to full
repeat 5
  read CHIP
  advance_ms 3000
end
assert store_status FULL
assert observations 10
# no silent deletion
reboot
assert observations 10
assert ack_watermark 0
)";
    miezmerker::sim::Scenario sc;
    auto err = miezmerker::sim::parse_scenario(text, sc);
    CHECK(err.ok());

    miezmerker::sim::ScenarioRunner runner(sc);
    int result = runner.run();
    CHECK(result == 0);
    std::cout << "PASS test_runner_no_silent_deletion\n";
}

void test_runner_many_records() {
    const char* text = R"(
name: many-records
version: 1
capacity: 10000

boot
rtc SYNCED 1700000000000
repeat 5000
  advance_ms 3000
  read CHIP
end
assert observations 5000
assert next_sequence 5001
assert sequences_unique
assert sequences_monotonic
assert ack_watermark 0
assert store_status OK
)";
    miezmerker::sim::Scenario sc;
    auto err = miezmerker::sim::parse_scenario(text, sc);
    CHECK(err.ok());

    miezmerker::sim::ScenarioRunner runner(sc);
    int result = runner.run();
    CHECK(result == 0);
    std::cout << "PASS test_runner_many_records\n";
}

void test_runner_multiple_chips() {
    const char* text = R"(
name: multiple-chips
version: 1

boot
read CHIP-A
read CHIP-B
read CHIP-C
assert observations 3
assert observation 0 chip_id CHIP-A
assert observation 1 chip_id CHIP-B
assert observation 2 chip_id CHIP-C
assert next_sequence 4
)";
    miezmerker::sim::Scenario sc;
    auto err = miezmerker::sim::parse_scenario(text, sc);
    CHECK(err.ok());

    miezmerker::sim::ScenarioRunner runner(sc);
    int result = runner.run();
    CHECK(result == 0);
    std::cout << "PASS test_runner_multiple_chips\n";
}

void test_runner_fail_load() {
    const char* text = R"(
name: fail-load
version: 1

boot
read CHIP-001
reboot
fail_load_next
reboot
assert not_ready
)";
    miezmerker::sim::Scenario sc;
    auto err = miezmerker::sim::parse_scenario(text, sc);
    CHECK(err.ok());

    miezmerker::sim::ScenarioRunner runner(sc);
    int result = runner.run();
    CHECK(result == 0);
    std::cout << "PASS test_runner_fail_load\n";
}

void test_runner_fail_identity_store() {
    const char* text = R"(
name: fail-identity-store
version: 1

boot
read CHIP-001
fail_identity_store 1
read CHIP-002
assert last_result IO_ERROR
reboot
assert observations 1
assert next_sequence 2
)";
    miezmerker::sim::Scenario sc;
    auto err = miezmerker::sim::parse_scenario(text, sc);
    CHECK(err.ok());

    miezmerker::sim::ScenarioRunner runner(sc);
    int result = runner.run();
    CHECK(result == 0);
    std::cout << "PASS test_runner_fail_identity_store\n";
}

void test_runner_fail_append() {
    const char* text = R"(
name: fail-append
version: 1

boot
read CHIP-001
fail_append 1
read CHIP-002
assert last_result IO_ERROR
reboot
assert observations 1
assert next_sequence 3
)";
    miezmerker::sim::Scenario sc;
    auto err = miezmerker::sim::parse_scenario(text, sc);
    CHECK(err.ok());

    miezmerker::sim::ScenarioRunner runner(sc);
    int result = runner.run();
    CHECK(result == 0);
    std::cout << "PASS test_runner_fail_append\n";
}

void test_runner_invalid_chip() {
    const char* text = R"(
name: invalid-chip
version: 1

boot
rtc SYNCED 1700000000000
read ""
assert last_result REJECTED_INVALID
read CHIP-001
assert last_result RECORDED
assert observations 1
)";
    miezmerker::sim::Scenario sc;
    auto err = miezmerker::sim::parse_scenario(text, sc);
    CHECK(err.ok());

    miezmerker::sim::ScenarioRunner runner(sc);
    int result = runner.run();
    CHECK(result == 0);
    std::cout << "PASS test_runner_invalid_chip\n";
}

void test_runner_fault_never_triggered() {
    const char* text = R"(
name: fault-never-triggered
version: 1

boot
crash_identity_store 1
# no read/factory_reset to consume it
)";
    miezmerker::sim::Scenario sc;
    auto err = miezmerker::sim::parse_scenario(text, sc);
    CHECK(err.ok());

    miezmerker::sim::ScenarioRunner runner(sc);
    int result = runner.run();
    CHECK(result == 1); // should fail due to armed but unconsumed crash fault
    std::cout << "PASS test_runner_fault_never_triggered\n";
}

void test_parse_hex_seed() {
    const char* text = R"(
name: hex-seed
version: 1
seed: 0x2A
capacity: 0x64

boot
)";
    miezmerker::sim::Scenario sc;
    auto err = miezmerker::sim::parse_scenario(text, sc);
    CHECK(err.ok());
    CHECK(sc.seed == 42);
    CHECK(sc.capacity == 100);

    miezmerker::sim::ScenarioRunner runner(sc);
    CHECK(runner.run() == 0);
    std::cout << "PASS test_parse_hex_seed\n";
}

void test_parse_rtc_invalid_arity() {
    miezmerker::sim::Scenario sc;
    auto err = miezmerker::sim::parse_scenario("name: x\nversion: 1\nrtc_invalid\n", sc);
    CHECK(err.ok());
    err = miezmerker::sim::parse_scenario("name: x\nversion: 1\nrtc_invalid extra\n", sc);
    CHECK(!err.ok());
    CHECK(err.message.find("takes 0 arguments") != std::string::npos);
    std::cout << "PASS test_parse_rtc_invalid_arity\n";
}

void test_runner_rtc_invalid_event() {
    const char* text = R"(
name: rtc-invalid-event
version: 1

boot
rtc_invalid
read CHIP-001
assert observation 0 clock_status UNKNOWN
assert observation 0 epoch_ms null
)";
    miezmerker::sim::Scenario sc;
    CHECK(miezmerker::sim::parse_scenario(text, sc).ok());
    miezmerker::sim::ScenarioRunner runner(sc);
    CHECK(runner.run() == 0);
    std::cout << "PASS test_runner_rtc_invalid_event\n";
}

void test_parse_ble_events() {
    miezmerker::sim::Scenario sc;
    const char* text = R"(
name: ble-parse
version: 1

boot
import_fixture_node sim-main
claim_mode_enter
claim_as admin
phone_connect
phone_connect alt
hello
hello 1
owner
provide_credential member
claim_mode_exit
reconnect
ble_status
ble_batch 1 16
ble_ack 1
ble_compact
ble_time 1790900000000
node_proof fixed
ble_version 2
ble_malformed
now 1790899300
collector_persist
collector_persist 1 2
send_ack
send_ack 1
drop_ack
phone_disconnect
sync_start
sync_complete
assert ble_connected
assert ble_authorized
assert last_auth ok
assert last_claim ok
assert claim_state CLAIMED
assert owner_org empty
assert collector_persisted 0
assert collector_watermark 0
assert collector_has 1
)";
    auto err = miezmerker::sim::parse_scenario(text, sc);
    CHECK(err.ok());
    CHECK(sc.events.size() == 38);
    CHECK(sc.events[1].type == "import_fixture_node");
    CHECK(sc.events[4].type == "phone_connect");
    std::cout << "PASS test_parse_ble_events\n";
}

void test_runner_ack_watermark() {
    const char* text = R"(
name: ack-watermark-unit
version: 1

boot
read A
read B
read C
assert ack_watermark 0
expect_ack 2 true
assert ack_watermark 2
fail_watermark_next
expect_ack 3 false
assert ack_watermark 2
expect_ack 3 true
assert ack_watermark 3
reboot
assert ack_watermark 3
expect_ack 99 false
assert ack_watermark 3
)";
    miezmerker::sim::Scenario sc;
    CHECK(miezmerker::sim::parse_scenario(text, sc).ok());
    miezmerker::sim::ScenarioRunner runner(sc);
    CHECK(runner.run() == 0);
    std::cout << "PASS test_runner_ack_watermark\n";
}

void test_runner_fail_never_triggered() {
    const char* text = R"(
name: fail-never-triggered
version: 1

boot
fail_append 1
# no read to consume it
)";
    miezmerker::sim::Scenario sc;
    CHECK(miezmerker::sim::parse_scenario(text, sc).ok());
    miezmerker::sim::ScenarioRunner runner(sc);
    CHECK(runner.run() == 1); // fail faults must also be reported
    std::cout << "PASS test_runner_fail_never_triggered\n";
}

void test_runner_clear_faults() {
    const char* text = R"(
name: clear-faults-unit
version: 1

boot
read A
read B
fail_clear_next
factory_reset
assert not_ready
assert observations 2
factory_reset
assert ready
assert observations 0
read C
crash_clear_next
factory_reset
reboot
assert observations 0
assert next_sequence 1
)";
    miezmerker::sim::Scenario sc;
    CHECK(miezmerker::sim::parse_scenario(text, sc).ok());
    miezmerker::sim::ScenarioRunner runner(sc);
    CHECK(runner.run() == 0);
    std::cout << "PASS test_runner_clear_faults\n";
}

void test_runner_misc_events() {
    const char* text = R"(
name: misc-events-unit
version: 1

boot
rtc_invalid
set_ms 5000
read A
assert observation 0 monotonic_ms 5000
read_absent
assert observations 1
expect_read B RECORDED
assert observations 2
storage_unavailable
reboot
assert not_ready
storage_available
reboot
assert ready
assert observations 2
)";
    miezmerker::sim::Scenario sc;
    CHECK(miezmerker::sim::parse_scenario(text, sc).ok());
    miezmerker::sim::ScenarioRunner runner(sc);
    CHECK(runner.run() == 0);
    std::cout << "PASS test_runner_misc_events\n";
}

void test_runner_rejects_invalid_ble_setup() {
    // A printed setup failure must also make the scenario fail in CI.
    for (const auto& setup : {std::string("boot\nclaim_as admin\n"),
                             std::string("boot\nclaim_as foreign\n"),
                             std::string("boot\nimport_fixture_node vector\nclaim_as foreign\n")}) {
        miezmerker::sim::Scenario sc;
        CHECK(miezmerker::sim::parse_scenario(
            "name: invalid-claim-setup\nversion: 1\n\n" + setup, sc).ok());
        miezmerker::sim::ScenarioRunner runner(sc);
        CHECK(runner.run() == 1);
    }
    std::cout << "PASS test_runner_rejects_invalid_ble_setup\n";
}

void test_runner_rejects_wrapped_time() {
    for (const auto& epoch : {std::string("9223372036854775808"),
                             std::string("18446744073709551615")}) {
        miezmerker::sim::Scenario sc;
        CHECK(miezmerker::sim::parse_scenario(
            "name: wrapped-time\nversion: 1\n\nboot\nnow " + epoch + "\n", sc).ok());
        miezmerker::sim::ScenarioRunner runner(sc);
        CHECK(runner.run() == 1);
    }
    miezmerker::sim::Scenario boundary;
    CHECK(miezmerker::sim::parse_scenario(
        "name: maximum-time\nversion: 1\n\nboot\nnow 9223372036854775807\n", boundary).ok());
    miezmerker::sim::ScenarioRunner runner(boundary);
    CHECK(runner.run() == 0);
    std::cout << "PASS test_runner_rejects_wrapped_time\n";
}

} // namespace

int main() {
    test_parse_valid();
    test_parse_repeat();
    test_parse_errors();
    test_parse_hex_seed();
    test_parse_rtc_invalid_arity();
    test_runner_simple();
    test_runner_reboot();
    test_runner_factory_reset();
    test_runner_dedup();
    test_runner_invalid_rtc();
    test_runner_rtc_invalid_event();
    test_runner_rtc_correction();
    test_runner_power_loss_obs();
    test_runner_power_loss_seq();
    test_runner_storage_full();
    test_runner_storage_nearly_full();
    test_runner_no_silent_deletion();
    test_runner_many_records();
    test_runner_multiple_chips();
    test_runner_fail_load();
    test_runner_fail_identity_store();
    test_runner_fail_append();
    test_runner_invalid_chip();
    test_runner_ack_watermark();
    test_runner_clear_faults();
    test_runner_misc_events();
    test_runner_rejects_invalid_ble_setup();
    test_runner_rejects_wrapped_time();
    test_parse_ble_events();
    test_runner_fault_never_triggered();
    test_runner_fail_never_triggered();

    std::cout << "All scenario tests passed (" << checks << " checks)\n";
    return 0;
}
