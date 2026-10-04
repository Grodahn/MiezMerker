#include "miezmerker/ble_gatt.hpp"
#include "miezmerker/ble_codec.hpp"

namespace miezmerker::ble_gatt {

namespace {
using namespace miezmerker::ble;

std::vector<std::uint8_t> error_frame(SyncError code, const std::string& msg) {
    ErrorMsg e{code, msg};
    return encode_frame(Frame{kProtocolVersion, Opcode::Error, encode_error_payload(e)});
}
}  // namespace

GattRouter::GattRouter(ble::SyncServer& server, OwnerMetadata& owner)
    : server_(&server), owner_(&owner) {}

void GattRouter::disconnect() {
    server_->disconnect();
}

std::vector<std::uint8_t> GattRouter::advertisement_bytes() const {
    Advertisement adv = server_->advertisement(claim_mode_);
    return encode_advertisement(adv);
}

std::vector<std::uint8_t> GattRouter::handle_frame(const std::vector<std::uint8_t>& request,
                                                   ble::TrustedEpochSeconds now_s) {
    auto frame = decode_frame(request);
    if (!frame) return error_frame(SyncError::InvalidFrame, "malformed frame");
    if (frame->version != kProtocolVersion) {
        return error_frame(SyncError::UnknownVersion, "unsupported version");
    }
    switch (frame->opcode) {
        case Opcode::HelloRequest: {
            auto req = decode_hello_request(frame->payload);
            if (!req) return error_frame(SyncError::InvalidFrame, "bad hello");
            if (req->client_ver != kProtocolVersion) {
                return error_frame(SyncError::UnknownVersion, "unsupported version");
            }
            HelloPublic hello = server_->public_hello();
            return encode_frame(Frame{kProtocolVersion, Opcode::HelloPublic,
                                      encode_hello_public(hello)});
        }
        case Opcode::OwnerRequest: {
            if (!frame->payload.empty()) return error_frame(SyncError::InvalidFrame, "bad owner req");
            OwnerInfo info = server_->public_owner();
            if (info.claim_state == miezmerker::ClaimState::CLAIMED) {
                info.organization_id = owner_->organization_id;
                info.organization_slug = owner_->organization_slug;
                info.organization_name = owner_->organization_name;
                info.public_contact = owner_->public_contact;
            }
            return encode_frame(
                Frame{kProtocolVersion, Opcode::OwnerResponse, encode_owner_response(info)});
        }
        case Opcode::ChallengeRequest: {
            if (!frame->payload.empty()) return error_frame(SyncError::InvalidFrame, "bad challenge");
            std::array<std::uint8_t, 32> nonce{};
            if (!server_->begin_challenge(nonce)) {
                return error_frame(SyncError::Internal, "challenge unavailable");
            }
            return encode_frame(Frame{kProtocolVersion, Opcode::ChallengeResponse,
                                      encode_challenge_response(nonce)});
        }
        case Opcode::AuthRequest: {
            auto req = decode_auth_request(frame->payload);
            if (!req) return error_frame(SyncError::InvalidFrame, "bad auth");
            const bool ok = server_->authorize(req->credential, req->proof, now_s);
            AuthResponse resp;
            resp.ok = ok;
            // Expiry echo is informational; the session itself gates ops.
            // A failed auth carries 0 + the foreign/expired distinction via error.
            if (ok) {
                auto st = server_->status(now_s);
                resp.expires_s = 0;  // Authorizer owns expiry; status re-checks it.
                (void)st;
                resp.error = SyncError::Ok;
            } else {
                resp.expires_s = 0;
                resp.error = SyncError::InvalidCredential;
            }
            return encode_frame(
                Frame{kProtocolVersion, Opcode::AuthResponse, encode_auth_response(resp)});
        }
        case Opcode::NodeProofRequest: {
            auto nonce = decode_node_proof_request(frame->payload);
            if (!nonce) return error_frame(SyncError::InvalidFrame, "bad node proof");
            auto res = server_->node_proof(*nonce, now_s);
            if (res.error != SyncError::Ok) {
                return error_frame(res.error, "node proof denied");
            }
            return encode_frame(Frame{kProtocolVersion, Opcode::NodeProofResponse,
                                      encode_node_proof_response(res.signature)});
        }
        case Opcode::StatusRequest: {
            if (!frame->payload.empty()) return error_frame(SyncError::InvalidFrame, "bad status");
            auto st = server_->status(now_s);
            if (!st) return error_frame(SyncError::Unauthorized, "unauthorized");
            return encode_frame(
                Frame{kProtocolVersion, Opcode::StatusResponse, encode_status_response(*st)});
        }
        case Opcode::BatchRequest: {
            auto req = decode_batch_request(frame->payload);
            if (!req) return error_frame(SyncError::InvalidFrame, "bad batch");
            auto res = server_->batch(req->from_sequence, req->max_records, now_s);
            if (res.error != SyncError::Ok) {
                if (res.error == SyncError::InvalidRecord)
                    return error_frame(SyncError::InvalidRecord, "stored record invalid");
                if (res.error == SyncError::InvalidSequence)
                    return error_frame(SyncError::InvalidSequence, "bad cursor");
                if (res.error == SyncError::InvalidState)
                    return error_frame(SyncError::InvalidState, "unclaimed");
                return error_frame(res.error, "batch denied");
            }
            return encode_frame(Frame{kProtocolVersion, Opcode::BatchResponse,
                                      encode_batch_response(res.batch)});
        }
        case Opcode::AckRequest: {
            auto w = decode_ack_request(frame->payload);
            if (!w) return error_frame(SyncError::InvalidFrame, "bad ack");
            auto res = server_->ack(*w, now_s);
            if (res.error != SyncError::Ok) {
                if (res.error == SyncError::InvalidSequence)
                    return error_frame(SyncError::InvalidSequence, "bad watermark");
                if (res.error == SyncError::InvalidState)
                    return error_frame(SyncError::InvalidState, "unclaimed");
                return error_frame(res.error, "ack denied");
            }
            AckResponse ack{res.new_watermark, res.error};
            return encode_frame(
                Frame{kProtocolVersion, Opcode::AckResponse, encode_ack_response(ack)});
        }
        case Opcode::TimeRequest: {
            auto v = decode_time_request(frame->payload);
            if (!v) return error_frame(SyncError::InvalidFrame, "bad time");
            auto res = server_->correct_time(*v, now_s);
            TimeResponse t{res.ok, res.error, res.applied_epoch_ms};
            if (!res.ok && res.error == SyncError::Unauthorized)
                return error_frame(res.error, "time correction denied");
            return encode_frame(
                Frame{kProtocolVersion, Opcode::TimeResponse, encode_time_response(t)});
        }
        case Opcode::CompactRequest: {
            if (!frame->payload.empty()) return error_frame(SyncError::InvalidFrame, "bad compact");
            auto res = server_->compact(now_s);
            if (res.error != SyncError::Ok) {
                if (res.error == SyncError::InvalidState)
                    return error_frame(SyncError::InvalidState, "unclaimed");
                return error_frame(res.error, "compact denied");
            }
            CompactResponse c{res.freed, res.remaining, res.ack_watermark, res.error};
            return encode_frame(Frame{kProtocolVersion, Opcode::CompactResponse,
                                      encode_compact_response(c)});
        }
        default:
            return error_frame(SyncError::InvalidFrame, "unknown opcode");
    }
}

}  // namespace miezmerker::ble_gatt
