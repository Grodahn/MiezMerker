#pragma once

// MiezMerker BLE sync v1 codec (issue #6).
//
// Transport-independent binary encoding/decoding for the GATT sync protocol.
// No BLE/NimBLE/Web-Bluetooth/ESP-IDF includes: pure standard C++ over byte
// vectors so firmware-core, ESP32 adapters, simulator and PWA share the exact
// layout documented in protocol/ble/messages.md and pinned by
// protocol/fixtures/ble-sync-v1.json.
//
// Frame: [ver u8][opcode u8][len u16 LE][payload].
// All multi-byte integers little-endian. Strings: [len u16 LE][UTF-8].
// Firmware version uses [len u8][UTF-8] inside HelloPublic (max 64).

#include <array>
#include <cstddef>
#include <cstdint>
#include <optional>
#include <string>
#include <vector>

#include "miezmerker/ports.hpp"
#include "miezmerker/types.hpp"

namespace miezmerker::ble {

inline constexpr std::uint8_t kProtocolVersion = 1;

inline constexpr std::uint16_t kCapBatch = 0x0001;
inline constexpr std::uint16_t kCapAck = 0x0002;
inline constexpr std::uint16_t kCapTimeCorrect = 0x0004;
inline constexpr std::uint16_t kCapNodeProof = 0x0008;
inline constexpr std::uint16_t kCapCompact = 0x0010;
inline constexpr std::uint16_t kCapAllV1 =
    kCapBatch | kCapAck | kCapTimeCorrect | kCapNodeProof | kCapCompact;

enum class Opcode : std::uint8_t {
    HelloRequest = 0x01,
    HelloPublic = 0x02,
    OwnerRequest = 0x03,
    OwnerResponse = 0x04,
    ChallengeRequest = 0x05,
    ChallengeResponse = 0x06,
    AuthRequest = 0x07,
    AuthResponse = 0x08,
    NodeProofRequest = 0x09,
    NodeProofResponse = 0x0A,
    BatchRequest = 0x0B,
    BatchResponse = 0x0C,
    AckRequest = 0x0D,
    AckResponse = 0x0E,
    TimeRequest = 0x0F,
    TimeResponse = 0x10,
    StatusRequest = 0x11,
    StatusResponse = 0x12,
    CompactRequest = 0x13,
    CompactResponse = 0x14,
    Error = 0xFF,
};

enum class SyncError : std::uint8_t {
    Ok = 0,
    UnknownVersion = 1,
    Unauthorized = 2,
    ForbiddenForeign = 3,
    InvalidFrame = 4,
    InvalidSequence = 5,
    InvalidRecord = 6,
    NotFound = 7,
    StoreFull = 8,
    ClockUnavailable = 9,
    AuthExpired = 10,
    Replay = 11,
    InvalidCredential = 12,
    InvalidProof = 13,
    InvalidState = 14,
    Internal = 15,
};

inline const char* to_string(SyncError code) {
    switch (code) {
        case SyncError::Ok: return "OK";
        case SyncError::UnknownVersion: return "UNKNOWN_VERSION";
        case SyncError::Unauthorized: return "UNAUTHORIZED";
        case SyncError::ForbiddenForeign: return "FORBIDDEN_FOREIGN";
        case SyncError::InvalidFrame: return "INVALID_FRAME";
        case SyncError::InvalidSequence: return "INVALID_SEQUENCE";
        case SyncError::InvalidRecord: return "INVALID_RECORD";
        case SyncError::NotFound: return "NOT_FOUND";
        case SyncError::StoreFull: return "STORE_FULL";
        case SyncError::ClockUnavailable: return "CLOCK_UNAVAILABLE";
        case SyncError::AuthExpired: return "AUTH_EXPIRED";
        case SyncError::Replay: return "REPLAY";
        case SyncError::InvalidCredential: return "INVALID_CREDENTIAL";
        case SyncError::InvalidProof: return "INVALID_PROOF";
        case SyncError::InvalidState: return "INVALID_STATE";
        case SyncError::Internal: return "INTERNAL";
    }
    return "INTERNAL";
}

struct Frame {
    std::uint8_t version{kProtocolVersion};
    Opcode opcode{Opcode::Error};
    std::vector<std::uint8_t> payload;
};

/// Encodes a frame ([ver][opcode][len LE][payload]). Always versioned.
std::vector<std::uint8_t> encode_frame(const Frame& frame);
/// Decodes a frame. Fails on short buffer, length mismatch or trailing bytes.
std::optional<Frame> decode_frame(const std::vector<std::uint8_t>& bytes);

// --- Message payloads (encode_* produce payload only; wrap with encode_frame)
// Hello
std::vector<std::uint8_t> encode_hello_request(std::uint8_t client_ver, std::uint16_t caps);
struct HelloRequest {
    std::uint8_t client_ver{0};
    std::uint16_t caps{0};
};
std::optional<HelloRequest> decode_hello_request(const std::vector<std::uint8_t>& payload);

struct HelloPublic {
    std::uint8_t server_ver{kProtocolVersion};
    std::uint16_t caps{kCapAllV1};
    NodeId node_id{};
    IncarnationId incarnation{};
    std::string firmware_version;
    ClaimState claim_state{ClaimState::UNCLAIMED};
    ClockStatus clock_status{ClockStatus::UNKNOWN};
};
std::vector<std::uint8_t> encode_hello_public(const HelloPublic& msg);
std::optional<HelloPublic> decode_hello_public(const std::vector<std::uint8_t>& payload);

// Owner (public)
struct OwnerInfo {
    NodeId node_id{};
    ClaimState claim_state{ClaimState::UNCLAIMED};
    std::string organization_id;
    std::string organization_slug;
    std::string organization_name;
    std::string public_contact;
};
std::vector<std::uint8_t> encode_owner_response(const OwnerInfo& msg);
std::optional<OwnerInfo> decode_owner_response(const std::vector<std::uint8_t>& payload);

// Challenge (32 B raw)
std::vector<std::uint8_t> encode_challenge_response(const std::array<std::uint8_t, 32>& nonce);
std::optional<std::array<std::uint8_t, 32>> decode_challenge_response(
    const std::vector<std::uint8_t>& payload);

// Auth
struct AuthRequest {
    std::string credential;  // ASCII JWT, max 2048
    std::array<std::uint8_t, 64> proof{};
};
std::vector<std::uint8_t> encode_auth_request(const AuthRequest& msg);
std::optional<AuthRequest> decode_auth_request(const std::vector<std::uint8_t>& payload);

struct AuthResponse {
    bool ok{false};
    std::uint64_t expires_s{0};
    SyncError error{SyncError::Unauthorized};
};
std::vector<std::uint8_t> encode_auth_response(const AuthResponse& msg);
std::optional<AuthResponse> decode_auth_response(const std::vector<std::uint8_t>& payload);

// Node proof (PWA nonce 32 B -> node sig 64 B)
std::vector<std::uint8_t> encode_node_proof_request(const std::array<std::uint8_t, 32>& nonce);
std::optional<std::array<std::uint8_t, 32>> decode_node_proof_request(
    const std::vector<std::uint8_t>& payload);
std::vector<std::uint8_t> encode_node_proof_response(const std::array<std::uint8_t, 64>& sig);
std::optional<std::array<std::uint8_t, 64>> decode_node_proof_response(
    const std::vector<std::uint8_t>& payload);

// Records (binary RawObservation, see protocol/ble/messages.md)
std::vector<std::uint8_t> encode_record(const RawObservation& obs);
std::optional<RawObservation> decode_record(const std::vector<std::uint8_t>& bytes, std::size_t& consumed);

// Batch
struct BatchRequest {
    std::uint64_t from_sequence{kInvalidSequence};
    std::uint16_t max_records{0};
};
std::vector<std::uint8_t> encode_batch_request(const BatchRequest& msg);
std::optional<BatchRequest> decode_batch_request(const std::vector<std::uint8_t>& payload);

struct BatchResponse {
    std::uint64_t from_sequence{0};
    bool more{false};
    std::uint64_t next_cursor{0};
    std::vector<RawObservation> records;
};
std::vector<std::uint8_t> encode_batch_response(const BatchResponse& msg);
std::optional<BatchResponse> decode_batch_response(const std::vector<std::uint8_t>& payload);

// Ack
std::vector<std::uint8_t> encode_ack_request(std::uint64_t watermark);
std::optional<std::uint64_t> decode_ack_request(const std::vector<std::uint8_t>& payload);
struct AckResponse {
    std::uint64_t new_watermark{0};
    SyncError error{SyncError::Ok};
};
std::vector<std::uint8_t> encode_ack_response(const AckResponse& msg);
std::optional<AckResponse> decode_ack_response(const std::vector<std::uint8_t>& payload);

// Time correction (protected, future reads only)
std::vector<std::uint8_t> encode_time_request(std::uint64_t epoch_ms);
std::optional<std::uint64_t> decode_time_request(const std::vector<std::uint8_t>& payload);
struct TimeResponse {
    bool ok{false};
    SyncError error{SyncError::Ok};
    std::uint64_t applied_epoch_ms{0};
};
std::vector<std::uint8_t> encode_time_response(const TimeResponse& msg);
std::optional<TimeResponse> decode_time_response(const std::vector<std::uint8_t>& payload);

// Status (protected)
struct StatusResponse {
    std::uint32_t pending{0};
    std::uint64_t ack_watermark{0};
    StoreStatus store_status{StoreStatus::OK};
    ClockStatus clock_status{ClockStatus::UNKNOWN};
    std::uint64_t epoch_ms{0};  // 0 when UNKNOWN
    std::uint64_t next_sequence{kFirstSequence};
};
std::vector<std::uint8_t> encode_status_response(const StatusResponse& msg);
std::optional<StatusResponse> decode_status_response(const std::vector<std::uint8_t>& payload);

// Compact (protected, explicit free of acked prefix)
struct CompactResponse {
    std::uint32_t freed{0};
    std::uint32_t remaining{0};
    std::uint64_t ack_watermark{0};
    SyncError error{SyncError::Ok};
};
std::vector<std::uint8_t> encode_compact_response(const CompactResponse& msg);
std::optional<CompactResponse> decode_compact_response(const std::vector<std::uint8_t>& payload);

// Error
struct ErrorMsg {
    SyncError code{SyncError::Internal};
    std::string message;
};
std::vector<std::uint8_t> encode_error_payload(const ErrorMsg& msg);
std::optional<ErrorMsg> decode_error_payload(const std::vector<std::uint8_t>& payload);

// Advertisement service data: [ver u8][flags u8][reserved u16].
// Flags bit0 CLAIMED, bit1 CLAIM_MODE. No identity/chip/pending/org.
struct Advertisement {
    std::uint8_t protocol_version{kProtocolVersion};
    bool claimed{false};
    bool claim_mode{false};
};
std::vector<std::uint8_t> encode_advertisement(const Advertisement& adv);
std::optional<Advertisement> decode_advertisement(const std::vector<std::uint8_t>& bytes);

}  // namespace miezmerker::ble
