#include "miezmerker/gatt_endpoint.hpp"
#include <algorithm>

namespace miezmerker::ble_gatt {
namespace {
using ble::Opcode;
bool protected_characteristic(Characteristic c) {
    return c == Characteristic::NodeProof || c == Characteristic::Status || c == Characteristic::Batch
        || c == Characteristic::Ack || c == Characteristic::Time;
}
bool matches(Characteristic c, Opcode op) {
    switch (c) {
        case Characteristic::Auth: return op == Opcode::AuthRequest;
        case Characteristic::NodeProof: return op == Opcode::NodeProofRequest;
        case Characteristic::Batch: return op == Opcode::BatchRequest;
        case Characteristic::Ack: return op == Opcode::AckRequest || op == Opcode::CompactRequest;
        case Characteristic::Time: return op == Opcode::TimeRequest;
        case Characteristic::ClaimReceipt: return op == Opcode::ClaimReceiptRequest;
        default: return false;
    }
}
}
void GattEndpoint::disconnect() {
    router_.disconnect(); for (auto& v : values_) v.clear(); connected_ = false; last_ms_ = 0;
}
void GattEndpoint::connect() { disconnect(); connected_ = true; }
void GattEndpoint::expire(std::uint64_t mono) {
    if (last_ms_ && mono - last_ms_ > 10000) {
        router_.disconnect(); for (auto& v : values_) v.clear();
    }
    last_ms_ = mono;
}
AccessResult GattEndpoint::write(Characteristic c, const std::vector<std::uint8_t>& bytes,
        unsigned mtu, std::uint64_t mono, ble::TrustedEpochSeconds now) {
    if (!connected_ || c >= Characteristic::Count) return AccessResult::unavailable;
    expire(mono);
    if (protected_characteristic(c) && !server_.authorized(now)) return AccessResult::unauthorized;
    auto& value = values_[static_cast<unsigned>(c)]; value.clear();
    if (bytes.empty() || bytes.size() > 512) return AccessResult::invalid;
    const bool envelope = bytes[0] == 0x4d || bytes[0] == 0x4e;
    auto frame = ble::decode_frame(bytes);
    if (envelope) {
        if ((bytes[0] == 0x4d && c != Characteristic::Auth)
            || (bytes[0] == 0x4e && c != Characteristic::ClaimReceipt)) return AccessResult::invalid;
    } else if (!frame || !matches(c, frame->opcode)) return AccessResult::invalid;
    auto request = bytes;
    if (!envelope && frame->version == 1 && c == Characteristic::Batch) {
        auto batch = ble::decode_batch_request(frame->payload);
        if (batch && batch->max_records != 0) {
            // 23 B frame/batch overhead, 127 B worst-case record, ATT notification overhead 3.
            // MTU 23 uses one-record long reads; no truncated notification is sent.
            const unsigned bounded = std::clamp(mtu, 23U, 517U);
            const unsigned fit = std::clamp(bounded > 26 ? (bounded - 26U) / 127U : 0U, 1U, 3U);
            batch->max_records = static_cast<std::uint16_t>(std::min<unsigned>(batch->max_records, fit));
            request = ble::encode_frame({1, Opcode::BatchRequest, ble::encode_batch_request(*batch)});
        }
    }
    value = router_.handle_frame(request, now);
    return AccessResult::ok;
}
AccessResult GattEndpoint::read(Characteristic c, bool continuation, std::uint64_t mono, ble::TrustedEpochSeconds now) {
    if (!connected_ || c >= Characteristic::Count) return AccessResult::unavailable;
    expire(mono);
    if (protected_characteristic(c) && !server_.authorized(now)) return AccessResult::unauthorized;
    auto& value = values_[static_cast<unsigned>(c)];
    if (!continuation) {
        std::optional<Opcode> opcode;
        switch (c) {
            case Characteristic::Info: opcode = Opcode::HelloRequest; break;
            case Characteristic::Owner: opcode = Opcode::OwnerRequest; break;
            case Characteristic::Challenge:
                // A new challenge invalidates previously cached protected values as well as authorization.
                for (unsigned i = 0; i < values_.size(); ++i)
                    if (protected_characteristic(static_cast<Characteristic>(i))) values_[i].clear();
                opcode = Opcode::ChallengeRequest; break;
            case Characteristic::Status: opcode = Opcode::StatusRequest; break;
            case Characteristic::ClaimAdvertisement: opcode = Opcode::ClaimAdvertisementRequest; break;
            default: break;
        }
        if (opcode) value = router_.handle_frame(ble::encode_frame({1, *opcode,
            *opcode == Opcode::HelloRequest ? ble::encode_hello_request(1, ble::kCapAllV1) : std::vector<std::uint8_t>{}}), now);
    }
    return value.empty() || value.size() > 512 ? AccessResult::unavailable : AccessResult::ok;
}
} // namespace miezmerker::ble_gatt
