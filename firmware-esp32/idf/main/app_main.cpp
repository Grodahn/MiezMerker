#include "board.hpp"
#include "nimble_peripheral.hpp"
#include "miezmerker/esp32_composition.hpp"
#include "sdkconfig.h"
#include "esp_app_desc.h"
#include "esp_log.h"
#include "esp_system.h"
#include "bootloader_random.h"
#include "driver/gpio.h"
#include "driver/usb_serial_jtag.h"
#include "psa/crypto.h"
#include "nvs_flash.h"
#include "freertos/task.h"
#include <charconv>
#include <cstdio>

using namespace miezmerker;
using namespace miezmerker::esp32;
namespace {
constexpr auto tag = "miezmerker";
void require(bool ok, const char* operation) {
    if (!ok) { ESP_LOGE(tag, "%s failed; no erase/reprovisioning", operation); ESP_ERROR_CHECK(ESP_FAIL); }
}
void status(Core& core, const NodeIdentity& identity) {
    std::array<unsigned char, 32> fingerprint{}; std::size_t length = 0;
    require(psa_hash_compute(PSA_ALG_SHA_256, reinterpret_cast<const unsigned char*>(identity.public_key.data()),
        identity.public_key.size(), fingerprint.data(), fingerprint.size(), &length) == PSA_SUCCESS, "fingerprint");
    char key_id[17];
    for (unsigned i = 0; i < 8; ++i) std::snprintf(key_id + 2*i, 3, "%02x", fingerprint[i]);
    ESP_LOGI(tag, "node=%s key_fingerprint=%s boot=%lu next=%llu stored=%u ack=%llu clock=UNKNOWN",
        core.node_id().to_string().c_str(), key_id, static_cast<unsigned long>(core.boot_counter()),
        static_cast<unsigned long long>(core.next_sequence()), static_cast<unsigned>(core.observation_count()),
        static_cast<unsigned long long>(core.ack_watermark()));
#if CONFIG_MM_DEV_SYNTHETIC_INPUT
    std::vector<std::uint8_t> records;
    for (const auto& observation : core.load_observations()) {
        const auto encoded = ble::encode_record(observation);
        records.insert(records.end(), encoded.begin(), encoded.end());
    }
    require(psa_hash_compute(PSA_ALG_SHA_256, records.data(), records.size(), fingerprint.data(), fingerprint.size(), &length)
        == PSA_SUCCESS, "observation digest");
    for (unsigned i = 0; i < 8; ++i) std::snprintf(key_id + 2*i, 3, "%02x", fingerprint[i]);
    ESP_LOGI(tag, "development immutable_log_digest=%s", key_id);
#endif
}
#if CONFIG_MM_DEV_SYNTHETIC_INPUT
void command(std::string_view line, Core& core, NodeIdentityManager& identity, BoardCrypto& crypto) {
    if (line == "status") { status(core, identity.identity()); return; }
    if (line == "crypto-test") {
        ESP_LOGI(tag, "isolated PSA/P-256/credential/claim vectors: %s (does not authorize GATT)",
            crypto_selftest(crypto, identity.identity()) ? "PASS" : "FAIL"); return;
    }
    if (line == "reboot") { ESP_LOGI(tag, "development normal reboot"); esp_restart(); }
    if (!line.starts_with("synthetic ")) { ESP_LOGW(tag, "commands: status | synthetic 1..16 | crypto-test | reboot"); return; }
    unsigned count = 0;
    const auto number = line.substr(10);
    const auto parsed = std::from_chars(number.data(), number.data() + number.size(), count);
    if (parsed.ec != std::errc{} || parsed.ptr != number.data() + number.size() || count < 1 || count > 16) {
        ESP_LOGW(tag, "synthetic count must be 1..16"); return;
    }
    for (unsigned i = 0; i < count; ++i) {
        const auto result = core.record_chip_read("SYNTHETIC-MM-" + std::to_string(core.next_sequence()));
        ESP_LOGI(tag, "synthetic capture result=%u", static_cast<unsigned>(result));
        if (result != RecordResult::RECORDED) break;
    }
    status(core, identity.identity());
}
#endif
}
extern "C" void app_main() {
    ESP_LOGI(tag, "app_main ESP32-C3 IDF=%s firmware=%s", esp_get_idf_version(), esp_app_get_description()->version);
    BoardClock clock;
    BoardRandom random;
    PhysicalClaimMode physical(clock);
    // Bootloader entropy is off before app_main. Enable it only while radio/ADC
    // are unused; the BLE controller supplies entropy after NimBLE starts.
    bootloader_random_enable();
    require(psa_crypto_init() == PSA_SUCCESS, "PSA crypto initialization");
    BoardCrypto crypto(clock);
    NvsBlob identity_blob("node_state"), observation_blob("observations");
    require(identity_blob.open() && observation_blob.open(), "persistent partitions");
    PersistentIdentity identity_store(identity_blob);
    PersistentObservations observations(observation_blob);
    NodeIdentityManager identity(identity_store, physical, random, crypto, clock);
    Core core(clock, observations, observations, identity, random);
    require(miezmerker::esp32::initialize(core) && identity.ready(), "firmware-core initialization");
    require(crypto.keypair_valid(identity.identity()), "persisted keypair consistency");
    status(core, identity.identity());
    ESP_LOGW(tag, "trusted UTC unavailable (#4); claim proof and offline authorization fail closed; issuer_pin=%s",
        crypto.pinned() ? "configured" : "missing");
    bootloader_random_disable();
    // Never copy the NimBLE example's automatic NVS erase on errors.
    ESP_ERROR_CHECK(nvs_flash_init());
    BoardAuthorizer authorizer(identity, crypto);
    BoardSigner signer(identity);
    ble::SyncConfig config;
    config.firmware_version = esp_app_get_description()->version;
    config.max_batch_records = 3; // Always <=512 B for an ATT long read.
    ble::SyncServer sync(core, authorizer, clock, signer, config);
    ble_gatt::OwnerMetadata owner{identity.identity().organization_id, "", identity.identity().organization_name, identity.identity().public_contact};
    ble_gatt::GattRouter router(sync, owner); router.set_node_identity(&identity);
    ble_gatt::GattEndpoint endpoint(router, sync);
    auto mutex = xSemaphoreCreateRecursiveMutex(); require(mutex != nullptr, "state mutex");
    NimblePeripheral peripheral(router, endpoint, clock, mutex);
    peripheral.start();
    gpio_config_t button{};
    button.pin_bit_mask = 1ULL << GPIO_NUM_9; button.mode = GPIO_MODE_INPUT; button.pull_up_en = GPIO_PULLUP_ENABLE;
    ESP_ERROR_CHECK(gpio_config(&button));
#if CONFIG_MM_DEV_SYNTHETIC_INPUT
    usb_serial_jtag_driver_config_t console{.tx_buffer_size = 2048, .rx_buffer_size = 256};
    ESP_ERROR_CHECK(usb_serial_jtag_driver_install(&console));
    ESP_LOGW(tag, "DEVELOPMENT synthetic input enabled: status | synthetic 1..16 | crypto-test | reboot");
    std::string line; bool overflow = false;
#endif
    std::uint64_t pressed_at = 0, reset_armed_until = 0, heartbeat = 0;
    bool released_once = false, old_claim = false;
    for (;;) {
        xSemaphoreTakeRecursive(mutex, portMAX_DELAY);
        const auto now = clock.monotonic_ms();
        const bool pressed = gpio_get_level(GPIO_NUM_9) == 0;
        if (!pressed) released_once = true; // BOOT during flashing never resets identity.
        if (released_once && pressed && !pressed_at) pressed_at = now;
        if (!pressed && pressed_at) {
            const auto held = now - pressed_at; pressed_at = 0;
            if (held >= 12000) {
                if (now < reset_armed_until) {
                    peripheral.close_session(); physical.cancel();
                    require(core.factory_reset(), "intentional physical factory reset");
                    esp_restart();
                }
                reset_armed_until = now + 30000;
                ESP_LOGW(tag, "factory reset ARMED for 30s; hold BOOT 12s and release again to confirm data/key loss");
            } else if (held >= 2000 && held <= 5000 && !identity.claimed()) {
                physical.activate(); ESP_LOGI(tag, "physical claim window opened (120s); trusted UTC still required");
            } else reset_armed_until = 0;
        }
        const bool claim = identity.in_claim_mode();
        if (old_claim != claim) { peripheral.refresh_advertisement(); old_claim = claim; }
#if CONFIG_MM_DEV_SYNTHETIC_INPUT
        std::uint8_t input[64];
        const int received = usb_serial_jtag_read_bytes(input, sizeof(input), 0);
        for (int i = 0; i < received; ++i) {
            if (input[i] == '\n') {
                if (!overflow && !line.empty()) command(line, core, identity, crypto);
                line.clear(); overflow = false;
            } else if (input[i] != '\r') {
                if (line.size() >= 48) overflow = true;
                else line += static_cast<char>(input[i]);
            }
        }
#endif
        if (now >= heartbeat) {
            ESP_LOGI(tag, "alive heap=%lu storage=%s", static_cast<unsigned long>(esp_get_free_heap_size()),
                observations.healthy() ? "ready" : "error");
            heartbeat = now + 10000;
        }
        xSemaphoreGiveRecursive(mutex); vTaskDelay(pdMS_TO_TICKS(25));
    }
}
