#pragma once
#include "miezmerker/gatt_endpoint.hpp"
#include "freertos/FreeRTOS.h"
#include "freertos/semphr.h"
#include "host/ble_hs.h"

namespace miezmerker::esp32 {
class NimblePeripheral {
public:
    NimblePeripheral(ble_gatt::GattRouter& router, ble_gatt::GattEndpoint& endpoint, ClaimClock& clock, SemaphoreHandle_t mutex)
        : router_(router), endpoint_(endpoint), clock_(clock), mutex_(mutex) {}
    void start();
    void refresh_advertisement();
    void close_session();
private:
    static int access(std::uint16_t conn, std::uint16_t attr, ble_gatt_access_ctxt* context, void* arg);
    static int gap(ble_gap_event* event, void* arg);
    static void synced();
    static void reset(int reason);
    static void host_task(void*);
    void advertise();
    static NimblePeripheral* instance_;
    ble_gatt::GattRouter& router_;
    ble_gatt::GattEndpoint& endpoint_;
    ClaimClock& clock_;
    SemaphoreHandle_t mutex_;
    ble_uuid_any_t service_uuid_{};
    std::array<ble_uuid_any_t, 11> uuids_{};
    std::array<ble_gatt_chr_def, 12> characteristics_{};
    std::array<ble_gatt_svc_def, 2> services_{};
    std::array<std::uint16_t, 11> handles_{};
    std::uint16_t connection_{BLE_HS_CONN_HANDLE_NONE};
    std::uint8_t address_type_{BLE_OWN_ADDR_RANDOM};
    bool subscribed_{false}, synced_{false};
};
} // namespace miezmerker::esp32
