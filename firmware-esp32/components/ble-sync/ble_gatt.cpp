#include "miezmerker/ble_gatt.hpp"
#include "miezmerker/ble_codec.hpp"
#include <algorithm>

namespace miezmerker::ble_gatt {

namespace {
using namespace miezmerker::ble;

std::vector<std::uint8_t> error_frame(SyncError code, const std::string& msg) {
    ErrorMsg e{code, msg};
    return encode_frame(Frame{kProtocolVersion, Opcode::Error, encode_error_payload(e)});
}
// Owner text is a public display hint. Preserve complete signed metadata in the
// identity store, but project hints into v1 codec bounds without splitting UTF-8.
std::string hint(const std::string& value, std::size_t limit) {
    if (value.size() <= limit) return value;
    while (limit && (static_cast<unsigned char>(value[limit]) & 0xc0) == 0x80) --limit;
    return value.substr(0, limit);
}
}  // namespace

GattRouter::GattRouter(ble::SyncServer& server, OwnerMetadata& owner)
    : server_(&server), owner_(&owner) {}

void GattRouter::disconnect() {
    auth_fragments_.clear();
    auth_total_ = 0;
    receipt_fragments_.clear();
    receipt_total_ = 0;
    server_->disconnect();
}

std::vector<std::uint8_t> GattRouter::advertisement_bytes() const {
    Advertisement adv = server_->advertisement(identity_ ? identity_->in_claim_mode() : claim_mode_);
    return encode_advertisement(adv);
}

std::vector<std::uint8_t> GattRouter::handle_frame(const std::vector<std::uint8_t>& request,
                                                   ble::TrustedEpochSeconds now_s) {
    // Auth/receipt transport envelopes. An empty response accepts a partial
    // write; the board retains the final response for the following read.
    if (!request.empty() && (request[0] == 0x4d || request[0] == 0x4e)) {
        const bool receipt = request[0] == 0x4e;
        auto& fragments = receipt ? receipt_fragments_ : auth_fragments_;
        auto& expected_total = receipt ? receipt_total_ : auth_total_;
        auto reject = [&]() {
            fragments.clear();
            expected_total = 0;
            return error_frame(SyncError::InvalidFrame, "bad transport fragment");
        };
        if (request.size() < 9 || request.size() > 20 || request[1] != 0x4d ||
            request[2] != 1 || request[3] > 1) return reject();
        const std::size_t total = request[4] | (static_cast<std::size_t>(request[5]) << 8);
        const std::size_t offset = request[6] | (static_cast<std::size_t>(request[7]) << 8);
        if (total < (receipt ? 7U : 70U) || total > (receipt ? 4102U : 2118U)) return reject();
        if (request[3] == 1) {
            fragments.clear();
            expected_total = total;
            if (offset != 0) return reject();
        }
        if (expected_total != total || offset != fragments.size() ||
            offset + request.size() - 8 > total) return reject();
        fragments.insert(fragments.end(), request.begin() + 8, request.end());
        if (fragments.size() != total) return {};
        auto assembled = std::move(fragments);
        fragments.clear();
        expected_total = 0;
        auto decoded = decode_frame(assembled);
        if (!decoded || decoded->opcode != (receipt ? Opcode::ClaimReceiptRequest : Opcode::AuthRequest)) return reject();
        return handle_frame(assembled, now_s);
    }
    auto frame = decode_frame(request);
    if (!frame) return error_frame(SyncError::InvalidFrame, "malformed frame");
    if (frame->version != kProtocolVersion) {
        return error_frame(SyncError::UnknownVersion, "unsupported version");
    }
    switch (frame->opcode) {
        case Opcode::ClaimAdvertisementRequest: {
            ClaimAdvertisement advertisement;
            if (!frame->payload.empty()) return error_frame(SyncError::InvalidFrame, "bad claim request");
            if (!identity_ || !identity_->sign_claim(advertisement)) return error_frame(SyncError::InvalidState, "physical claim mode required");
            ClaimAdvertisementPayload payload;
            const auto& node = identity_->identity();
            for (std::size_t i = 0; i < 16; ++i) payload.node_id.bytes[i] = std::to_integer<std::uint8_t>(node.node_id[i]);
            for (std::size_t i = 0; i < 65; ++i) payload.public_key[i] = std::to_integer<std::uint8_t>(node.public_key[i]);
            for (std::size_t i = 0; i < 64; ++i) payload.signature[i] = std::to_integer<std::uint8_t>(advertisement.signature[i]);
            payload.timestamp_ms = advertisement.timestamp_ms;
            return encode_frame(Frame{1, Opcode::ClaimAdvertisementResponse, encode_claim_advertisement(payload)});
        }
        case Opcode::ClaimReceiptRequest: {
            auto receipt = decode_claim_receipt(frame->payload);
            if (!receipt) return error_frame(SyncError::InvalidFrame, "bad receipt");
            if (!identity_ || !identity_->apply_claim(*receipt)) return error_frame(SyncError::InvalidState, "claim rejected");
            const auto& node = identity_->identity();
            owner_->organization_id = node.organization_id;
            owner_->organization_name = node.organization_name;
            owner_->public_contact = node.public_contact;
            owner_->organization_slug.clear();
            // Reload capture state so subsequent public Hello/Owner reflect the
            // same atomic ownership record before a collector can start sync.
            if (!server_->refresh_identity()) return error_frame(SyncError::Internal, "claim refresh failed");
            return encode_frame(Frame{1, Opcode::ClaimReceiptResponse, {0}});
        }
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
                // Never shorten the ownership identifier itself.
                if (owner_->organization_id.size() > 128)
                    return error_frame(SyncError::Internal, "invalid owner identifier");
                info.organization_id = owner_->organization_id;
                info.organization_slug = hint(owner_->organization_slug, 128);
                info.organization_name = hint(owner_->organization_name, 128);
                // Frame header + UUID/claim marker + four string length fields.
                constexpr std::size_t overhead = 4 + 16 + 1 + 4 * 2;
                const auto remaining = 512 - overhead - info.organization_id.size()
                    - info.organization_slug.size() - info.organization_name.size();
                info.public_contact = hint(owner_->public_contact, std::min<std::size_t>(256, remaining));
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
