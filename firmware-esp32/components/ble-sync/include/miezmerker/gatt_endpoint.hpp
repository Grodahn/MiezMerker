#pragma once
#include "miezmerker/ble_gatt.hpp"

namespace miezmerker::ble_gatt {
enum class Characteristic : unsigned { Info, Owner, Challenge, Auth, NodeProof, Status, Batch, Ack, Time, ClaimAdvertisement, ClaimReceipt, Count };
enum class AccessResult { ok, unauthorized, invalid, unavailable };
// One endpoint per connection. NimBLE supplies MTU/time and handles ATT Read Blob
// slicing. The complete cached value must stay unchanged throughout a long read.
class GattEndpoint {
public:
    GattEndpoint(GattRouter& router, ble::SyncServer& server) : router_(router), server_(server) {}
    void connect();
    void disconnect();
    AccessResult write(Characteristic characteristic, const std::vector<std::uint8_t>& bytes,
                       unsigned mtu, std::uint64_t monotonic_ms, ble::TrustedEpochSeconds now);
    AccessResult read(Characteristic characteristic, bool continuation, std::uint64_t monotonic_ms,
                      ble::TrustedEpochSeconds now);
    const std::vector<std::uint8_t>& value(Characteristic characteristic) const { return values_[static_cast<unsigned>(characteristic)]; }
private:
    void expire(std::uint64_t monotonic_ms);
    GattRouter& router_;
    ble::SyncServer& server_;
    std::array<std::vector<std::uint8_t>, static_cast<unsigned>(Characteristic::Count)> values_;
    std::uint64_t last_ms_{0};
    bool connected_{false};
};
} // namespace miezmerker::ble_gatt
