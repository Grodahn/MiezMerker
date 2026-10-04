#pragma once

// MiezMerker BLE GATT transport adapter (issue #6, target ESP32-C3/NimBLE).
//
// Host-testable request router between raw GATT frames and the portable
// SyncServer. No NimBLE/ESP-IDF includes here: the board shim (see README)
// maps characteristic reads/writes/notifications onto handle_frame() and
// advertisement_bytes(). Authorization crypto stays in OfflineAuthSession
// (injected as SyncAuthorizer); this router only enforces gating and version
// negotiation with identical fail-closed semantics.
//
// Service 6f4a2c1e-8b3d-4e5f-9a0c-1d2e3f4a5b6c, characteristics ...5b01..09
// (see protocol/ble/messages.md). Advertisement carries ver/flags only.

#include <array>
#include <cstdint>
#include <string>
#include <vector>

#include "miezmerker/ble_sync.hpp"

namespace miezmerker::ble_gatt {

inline constexpr char kServiceUuid[] = "6f4a2c1e-8b3d-4e5f-9a0c-1d2e3f4a5b6c";
inline constexpr char kInfoUuid[] = "6f4a2c1e-8b3d-4e5f-9a0c-1d2e3f4a5b01";
inline constexpr char kOwnerUuid[] = "6f4a2c1e-8b3d-4e5f-9a0c-1d2e3f4a5b02";
inline constexpr char kChallengeUuid[] = "6f4a2c1e-8b3d-4e5f-9a0c-1d2e3f4a5b03";
inline constexpr char kAuthUuid[] = "6f4a2c1e-8b3d-4e5f-9a0c-1d2e3f4a5b04";
inline constexpr char kNodeProofUuid[] = "6f4a2c1e-8b3d-4e5f-9a0c-1d2e3f4a5b05";
inline constexpr char kStatusUuid[] = "6f4a2c1e-8b3d-4e5f-9a0c-1d2e3f4a5b06";
inline constexpr char kBatchUuid[] = "6f4a2c1e-8b3d-4e5f-9a0c-1d2e3f4a5b07";
inline constexpr char kAckUuid[] = "6f4a2c1e-8b3d-4e5f-9a0c-1d2e3f4a5b08";
inline constexpr char kTimeUuid[] = "6f4a2c1e-8b3d-4e5f-9a0c-1d2e3f4a5b09";

/// Backend-provisioned public owner hint for CLAIMED nodes. Persisted with
/// the claim receipt (#18); empty until provisioned. Never contains
/// observations/chips/pending data.
struct OwnerMetadata {
    std::string organization_id;
    std::string organization_slug;
    std::string organization_name;
    std::string public_contact;
};

/// Routes one decoded request frame to exactly one response frame.
/// Version negotiation: any request with ver != 1 returns Error/UNKNOWN_VERSION
/// without touching protected state. Protected opcodes without a valid
/// session return Error/UNAUTHORIZED (or FORBIDDEN_FOREIGN via AuthResponse).
class GattRouter {
public:
    GattRouter(ble::SyncServer& server, OwnerMetadata& owner);

    void disconnect();
    void set_claim_mode(bool active) { claim_mode_ = active; }

    /// Handles a raw request frame, returns a raw response frame.
    /// trusted_now_s is trusted UTC seconds (0 = unknown, fails closed).
    std::vector<std::uint8_t> handle_frame(const std::vector<std::uint8_t>& request,
                                           ble::TrustedEpochSeconds now_s);

    /// Advertisement service data (4 B). No identity/chip/pending/org.
    std::vector<std::uint8_t> advertisement_bytes() const;

private:
    ble::SyncServer* server_;
    OwnerMetadata* owner_;
    bool claim_mode_{false};
};

}  // namespace miezmerker::ble_gatt
