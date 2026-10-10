#include "nimble_peripheral.hpp"
#include "esp_log.h"
#include "esp_timer.h"
#include "nimble/nimble_port.h"
#include "nimble/nimble_port_freertos.h"
#include "host/ble_hs_mbuf.h"
#include "services/gap/ble_svc_gap.h"
#include "services/gatt/ble_svc_gatt.h"
#include <algorithm>
#include <exception>

namespace miezmerker::esp32 {
NimblePeripheral* NimblePeripheral::instance_ = nullptr;
namespace {
using ble_gatt::Characteristic;
class Lock {
public:
    explicit Lock(SemaphoreHandle_t mutex) : mutex_(mutex) { xSemaphoreTakeRecursive(mutex_, portMAX_DELAY); }
    ~Lock() { xSemaphoreGiveRecursive(mutex_); }
private:
    SemaphoreHandle_t mutex_;
};
void check(int rc, const char* operation) {
    if (rc) { ESP_LOGE("ble", "%s rc=%d", operation, rc); ESP_ERROR_CHECK(ESP_FAIL); }
}
int att(ble_gatt::AccessResult result) {
    switch (result) {
        case ble_gatt::AccessResult::ok: return 0;
        case ble_gatt::AccessResult::unauthorized: return BLE_ATT_ERR_INSUFFICIENT_AUTHOR;
        case ble_gatt::AccessResult::invalid: return BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN;
        default: return BLE_ATT_ERR_UNLIKELY;
    }
}
}
void NimblePeripheral::start() {
    instance_ = this;
    ESP_ERROR_CHECK(nimble_port_init());
    ble_hs_cfg.sync_cb = synced; ble_hs_cfg.reset_cb = reset;
    ble_hs_cfg.sm_bonding = 0; ble_hs_cfg.sm_mitm = 0;
    ble_svc_gap_init(); ble_svc_gatt_init();
    check(ble_svc_gap_device_name_set("MM-Node"), "device name");
    check(ble_att_set_preferred_mtu(517), "preferred MTU");
    check(ble_uuid_from_str(&service_uuid_, ble_gatt::kServiceUuid), "service UUID");
    const char* ids[] = {ble_gatt::kInfoUuid, ble_gatt::kOwnerUuid, ble_gatt::kChallengeUuid,
        ble_gatt::kAuthUuid, ble_gatt::kNodeProofUuid, ble_gatt::kStatusUuid, ble_gatt::kBatchUuid,
        ble_gatt::kAckUuid, ble_gatt::kTimeUuid, ble_gatt::kClaimAdvertisementUuid, ble_gatt::kClaimReceiptUuid};
    for (unsigned i = 0; i < 11; ++i) {
        check(ble_uuid_from_str(&uuids_[i], ids[i]), "characteristic UUID");
        auto& c = characteristics_[i];
        c.uuid = &uuids_[i].u; c.access_cb = access;
        c.arg = reinterpret_cast<void*>(static_cast<std::uintptr_t>(i));
        c.flags = BLE_GATT_CHR_F_READ;
        if (i == 3 || i == 4 || i == 6 || i == 7 || i == 8 || i == 10) c.flags |= BLE_GATT_CHR_F_WRITE;
        if (i == 6) c.flags |= BLE_GATT_CHR_F_NOTIFY;
        c.val_handle = &handles_[i];
    }
    services_[0].type = BLE_GATT_SVC_TYPE_PRIMARY;
    services_[0].uuid = &service_uuid_.u; services_[0].characteristics = characteristics_.data();
    check(ble_gatts_count_cfg(services_.data()), "GATT count");
    check(ble_gatts_add_svcs(services_.data()), "GATT registration");
    ESP_LOGI("ble", "NimBLE initialized, MiezMerker GATT registered (11 characteristics)");
    nimble_port_freertos_init(host_task);
}
void NimblePeripheral::host_task(void*) { nimble_port_run(); nimble_port_freertos_deinit(); }
void NimblePeripheral::synced() {
    auto& self = *instance_; Lock lock(self.mutex_);
    ble_addr_t random{};
    check(ble_hs_id_gen_rnd(0, &random), "random BLE address");
    check(ble_hs_id_set_rnd(random.val), "set random BLE address");
    self.synced_ = true; self.advertise();
}
void NimblePeripheral::reset(int reason) {
    auto& self = *instance_; Lock lock(self.mutex_);
    self.endpoint_.disconnect(); self.connection_ = BLE_HS_CONN_HANDLE_NONE;
    self.subscribed_ = false; self.synced_ = false;
    ESP_LOGE("ble", "host reset reason=%d; session cleared", reason);
}
void NimblePeripheral::advertise() {
    if (!synced_ || connection_ != BLE_HS_CONN_HANDLE_NONE) return;
    ble_hs_adv_fields fields{};
    fields.flags = BLE_HS_ADV_F_DISC_GEN | BLE_HS_ADV_F_BREDR_UNSUP;
    fields.uuids128 = &service_uuid_.u128; fields.num_uuids128 = 1; fields.uuids128_is_complete = 1;
    check(ble_gap_adv_set_fields(&fields), "advertisement UUID");
    // 128-bit Service Data is AD type 0x21. Technical version/flags only.
    auto data = router_.advertisement_bytes();
    std::vector<std::uint8_t> response{21, 0x21};
    response.insert(response.end(), service_uuid_.u128.value, service_uuid_.u128.value + 16);
    response.insert(response.end(), data.begin(), data.end());
    const std::uint8_t name[] = {8, 0x09, 'M','M','-','N','o','d','e'};
    response.insert(response.end(), std::begin(name), std::end(name));
    check(ble_gap_adv_rsp_set_data(response.data(), static_cast<int>(response.size())), "scan response");
    ble_gap_adv_params parameters{};
    parameters.conn_mode = BLE_GAP_CONN_MODE_UND; parameters.disc_mode = BLE_GAP_DISC_MODE_GEN;
    check(ble_gap_adv_start(address_type_, nullptr, BLE_HS_FOREVER, &parameters, gap, this), "advertise start");
    ESP_LOGI("ble", "advertising service=%s flags=%u", ble_gatt::kServiceUuid, data[1]);
}
void NimblePeripheral::refresh_advertisement() {
    Lock lock(mutex_);
    if (!synced_ || connection_ != BLE_HS_CONN_HANDLE_NONE) return;
    if (ble_gap_adv_active()) check(ble_gap_adv_stop(), "advertise stop");
    advertise();
}
void NimblePeripheral::close_session() {
    Lock lock(mutex_); endpoint_.disconnect();
    if (connection_ != BLE_HS_CONN_HANDLE_NONE) ble_gap_terminate(connection_, BLE_ERR_REM_USER_CONN_TERM);
}
int NimblePeripheral::gap(ble_gap_event* event, void* arg) {
    auto& self = *static_cast<NimblePeripheral*>(arg); Lock lock(self.mutex_);
    switch (event->type) {
        case BLE_GAP_EVENT_CONNECT:
            self.endpoint_.disconnect(); self.subscribed_ = false;
            if (!event->connect.status) {
                self.connection_ = event->connect.conn_handle; self.endpoint_.connect();
                ESP_LOGI("ble", "connected; fresh unauthenticated session");
            } else { self.connection_ = BLE_HS_CONN_HANDLE_NONE; self.advertise(); }
            break;
        case BLE_GAP_EVENT_DISCONNECT:
            self.endpoint_.disconnect(); self.connection_ = BLE_HS_CONN_HANDLE_NONE; self.subscribed_ = false;
            ESP_LOGI("ble", "disconnected reason=%d; session cleared", event->disconnect.reason);
            self.advertise(); break;
        case BLE_GAP_EVENT_SUBSCRIBE:
            if (event->subscribe.attr_handle == self.handles_[6]) self.subscribed_ = event->subscribe.cur_notify;
            break;
        case BLE_GAP_EVENT_MTU:
            ESP_LOGI("ble", "ATT MTU=%u", event->mtu.value); break;
        case BLE_GAP_EVENT_ADV_COMPLETE: self.advertise(); break;
        default: break;
    }
    return 0;
}
int NimblePeripheral::access(std::uint16_t conn, std::uint16_t, ble_gatt_access_ctxt* context, void* arg) {
    auto& self = *instance_; Lock lock(self.mutex_);
    if (conn != self.connection_) return BLE_ATT_ERR_UNLIKELY;
    const auto c = static_cast<Characteristic>(reinterpret_cast<std::uintptr_t>(arg));
    const auto mono = static_cast<std::uint64_t>(esp_timer_get_time()) / 1000;
    const auto now = static_cast<ble::TrustedEpochSeconds>(self.clock_.epoch_ms() / 1000);
    try {
        if (context->op == BLE_GATT_ACCESS_OP_WRITE_CHR) {
            const auto length = OS_MBUF_PKTLEN(context->om);
            if (!length || length > 512) return BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN;
            std::vector<std::uint8_t> request(length);
            if (os_mbuf_copydata(context->om, 0, length, request.data())) return BLE_ATT_ERR_UNLIKELY;
            const auto result = self.endpoint_.write(c, request, ble_att_mtu(conn), mono, now);
            const auto& value = self.endpoint_.value(c);
            if (result == ble_gatt::AccessResult::ok && c == Characteristic::Batch && self.subscribed_
                && !value.empty() && value.size() <= static_cast<unsigned>(ble_att_mtu(conn) - 3)) {
                auto* mbuf = ble_hs_mbuf_from_flat(value.data(), static_cast<std::uint16_t>(value.size()));
                if (mbuf) {
                    const int rc = ble_gatts_notify_custom(conn, self.handles_[6], mbuf);
                    if (rc) ESP_LOGW("ble", "notification rc=%d; response retained for read/retry", rc);
                }
            }
            return att(result);
        }
        if (context->op != BLE_GATT_ACCESS_OP_READ_CHR) return BLE_ATT_ERR_UNLIKELY;
        const auto result = self.endpoint_.read(c, context->offset != 0, mono, now);
        if (result != ble_gatt::AccessResult::ok) return att(result);
        const auto& value = self.endpoint_.value(c);
        // NimBLE applies context->offset itself; append the complete stable value.
        return os_mbuf_append(context->om, value.data(), static_cast<std::uint16_t>(value.size())) == 0
            ? 0 : BLE_ATT_ERR_INSUFFICIENT_RES;
    } catch (const std::exception&) {
        self.endpoint_.disconnect();
        ESP_LOGE("ble", "GATT resource failure; session cleared");
        return BLE_ATT_ERR_INSUFFICIENT_RES;
    }
}
} // namespace miezmerker::esp32
