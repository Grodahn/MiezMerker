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
// JSON is only a transport envelope. Signed bytes and uint64 decimal values
// are copied exactly; the backend independently verifies both signatures.
std::string quoted(const std::string& value) {
    std::string out = "\"";
    constexpr char hex[] = "0123456789abcdef";
    for (unsigned char c : value) {
        if (c == '"' || c == '\\') { out += '\\'; out += static_cast<char>(c); }
        else if (c < 32) { out += "\\u00"; out += hex[c >> 4]; out += hex[c & 15]; }
        else out += static_cast<char>(c);
    }
    return out + "\"";
}
std::string boot_json(const BootEpochV1& b) {
    return "{\"formatVersion\":1,\"nodeId\":" + quoted(b.node_id.to_string()) +
        ",\"incarnation\":" + quoted(b.incarnation.to_string()) +
        ",\"bootCounter\":" + std::to_string(b.boot_counter) +
        ",\"resetReason\":" + std::to_string(b.reset_reason) +
        ",\"startedUptimeMs\":" + std::to_string(b.started_uptime_ms) +
        ",\"firstPossibleSequence\":" + std::to_string(b.first_possible_sequence) + "}";
}
std::string anchor_json(const BootEpochV1& b, const TimeAnchorV1& a) {
    return "{\"boot\":" + boot_json(b) + ",\"proof\":" + quoted(a.proof) +
        ",\"challenge\":" + (a.challenge.empty() ? "null" : a.challenge) +
        ",\"acceptance\":" + a.acceptance +
        ",\"challengeBytes\":" + quoted(a.challenge_bytes) +
        ",\"acceptanceBytes\":" + quoted(a.acceptance_bytes) +
        ",\"metadata\":{\"anchorId\":" + quoted(a.anchor_id.to_string()) +
        ",\"formatVersion\":1,\"verifiedSecurityTime\":" + std::string(a.verified_security_time ? "true" : "false") +
        ",\"proofIdOrDigest\":" + quoted(a.proof_id_or_digest) +
        ",\"uptimeMs\":" + std::to_string(a.uptime_ms) +
        ",\"utcMs\":" + std::to_string(a.utc_ms) +
        ",\"source\":" + std::to_string(static_cast<unsigned>(a.source)) +
        ",\"uncertaintyMinusMs\":" + std::to_string(a.uncertainty_minus_ms) +
        ",\"uncertaintyPlusMs\":" + std::to_string(a.uncertainty_plus_ms) +
        ",\"driftPpmBound\":" + std::to_string(a.drift_ppm_bound) + "}}";
}
}  // namespace

GattRouter::GattRouter(ble::SyncServer& server, OwnerMetadata& owner)
    : server_(&server), owner_(&owner) {}

void GattRouter::disconnect() {
    if (alpha_clock_) alpha_clock_->disconnect();
    auth_fragments_.clear();
    auth_total_ = 0;
    receipt_fragments_.clear();
    receipt_total_ = 0;
    evidence_fragments_.clear(); evidence_total_ = 0;
    metadata_fragments_.clear(); metadata_total_ = 0;
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
    if (!request.empty() && (request[0] == 0x4d || request[0] == 0x4e || request[0] == 0x4f || request[0] == 0x50)) {
        const bool metadata = request[0] == 0x50;
        if (metadata && !server_->authorized(now_s)) {
            metadata_fragments_.clear(); metadata_total_ = 0;
            return error_frame(SyncError::Unauthorized, "metadata requires authorization");
        }
        const bool evidence = request[0] == 0x4f;
        const bool receipt = request[0] == 0x4e;
        auto& fragments = metadata ? metadata_fragments_ : evidence ? evidence_fragments_ : receipt ? receipt_fragments_ : auth_fragments_;
        auto& expected_total = metadata ? metadata_total_ : evidence ? evidence_total_ : receipt ? receipt_total_ : auth_total_;
        auto reject = [&]() {
            fragments.clear();
            expected_total = 0;
            return error_frame(SyncError::InvalidFrame, "bad transport fragment");
        };
        if (request.size() < 9 || request.size() > 20 || request[1] != 0x4d ||
            request[2] != 1 || request[3] > 1) return reject();
        const std::size_t total = request[4] | (static_cast<std::size_t>(request[5]) << 8);
        const std::size_t offset = request[6] | (static_cast<std::size_t>(request[7]) << 8);
        if (total < (metadata ? 40U : evidence ? 57U : receipt ? 7U : 70U) || total > (metadata ? 440U : evidence ? 57U : receipt ? 4102U : 2118U)) return reject();
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
        if (!decoded || (metadata ? decoded->opcode != Opcode::MetadataRequest : evidence ? decoded->opcode != Opcode::TimeEvidenceReceiptRequest : receipt
            ? decoded->opcode != Opcode::ClaimReceiptRequest && decoded->opcode != Opcode::RegistrationTimeProofRequest
            : decoded->opcode != Opcode::AuthRequest)) return reject();
        return handle_frame(assembled, now_s);
    }
    auto frame = decode_frame(request);
    if (!frame) return error_frame(SyncError::InvalidFrame, "malformed frame");
    if (frame->version != kProtocolVersion) {
        return error_frame(SyncError::UnknownVersion, "unsupported version");
    }
    switch (frame->opcode) {
        case Opcode::MetadataRequest: {
            if (!server_->authorized(now_s)) return error_frame(SyncError::Unauthorized, "metadata requires authorization");
            const auto& p = frame->payload;
            if (!identity_ || p.size() < 36 || p.size() > 436)
                return error_frame(SyncError::InvalidFrame, "bad metadata");
            std::array<std::byte, 16> node{};
            NodeId org;
            for (unsigned i = 0; i < 16; ++i) { node[i] = static_cast<std::byte>(p[i]); org.bytes[i] = p[16+i]; }
            std::uint32_t number = 0;
            for (unsigned i = 0; i < 4; ++i) number |= std::uint32_t(p[32+i]) << (8*i);
            const std::string name(p.begin()+36, p.end());
            if (!identity_->apply_metadata(node, org.to_string(), number, name))
                return error_frame(SyncError::InvalidState, "metadata rejected");
            return encode_frame({1, Opcode::MetadataResponse, {0}});
        }
        case Opcode::AlphaPhoneClockRequest: {
            const auto& p = frame->payload;
            if (!alpha_clock_) return error_frame(SyncError::ClockUnavailable, "FIELD_ALPHA_PHONE_CLOCK disabled");
            if (p.size() != 9 || p[0] != 1) return error_frame(SyncError::InvalidFrame, "bad alpha clock sample");
            std::uint64_t utc = 0;
            for (unsigned i = 0; i < 8; ++i) utc |= std::uint64_t(p[1+i]) << (8*i);
            if (!alpha_clock_->sample(utc)) return error_frame(SyncError::InvalidState, "alpha clock already sampled or invalid; reconnect");
            return encode_frame({1, Opcode::AlphaPhoneClockResponse, {0}});
        }
        case Opcode::ClockSnapshotRequest: {
            if (!frame->payload.empty()) return error_frame(SyncError::InvalidFrame, "bad clock snapshot request");
            const auto snapshot = server_->clock_snapshot(now_s);
            if (!snapshot) return error_frame(SyncError::Unauthorized, "clock snapshot requires authorization");
            return encode_frame({1, Opcode::ClockSnapshotResponse, *snapshot});
        }
        case Opcode::SecurityTimeStatusRequest: {
            if (!frame->payload.empty() || !time_bootstrap_)
                return error_frame(SyncError::ClockUnavailable, "security time unavailable");
            std::int64_t lower = 0, upper = 0;
            const bool usable = time_bootstrap_->current_interval(lower, upper);
            return encode_frame({1, Opcode::SecurityTimeStatusResponse, {static_cast<std::uint8_t>(usable)}});
        }
        case Opcode::TimeEvidenceReceiptRequest: {
            if (!server_->authorized(now_s)) return error_frame(SyncError::Unauthorized, "evidence receipt requires authorization");
            const auto& p = frame->payload;
            if (!evidence_ || p.size() != 53 || p[0] > 1) return error_frame(SyncError::InvalidFrame, "bad evidence receipt");
            NodeId node, anchor; IncarnationId incarnation;
            std::copy_n(p.begin()+1,16,node.bytes.begin()); std::copy_n(p.begin()+17,16,incarnation.bytes.begin());
            const std::uint32_t boot = p[33] | (std::uint32_t(p[34]) << 8) | (std::uint32_t(p[35]) << 16) | (std::uint32_t(p[36]) << 24);
            std::copy_n(p.begin()+37,16,anchor.bytes.begin());
            const auto hello = server_->public_hello();
            if (node != hello.node_id || incarnation != hello.incarnation || !boot || (p[0] == 0 && anchor.is_set()) || (p[0] == 1 && !anchor.is_set()))
                return error_frame(SyncError::InvalidFrame, "wrong evidence receipt lifetime");
            const auto result = evidence_->acknowledge(p[0] == 1,node,incarnation,boot,anchor);
            if (result == EvidenceReceiptResult::Rejected) return error_frame(SyncError::Internal, "evidence receipt persistence failed");
            auto response = p;
            response.push_back(result == EvidenceReceiptResult::Retained ? 1 : 0);
            return encode_frame({1,Opcode::TimeEvidenceReceiptResponse,response});
        }
        case Opcode::TimeEvidenceRequest: {
            // Separate append-only boot/anchor indexes, never the raw ACK watermark.
            // Retain on the Node even after transfer; no pre-auth evidence disclosure.
            if (!server_->authorized(now_s)) return error_frame(SyncError::Unauthorized, "evidence requires authorization");
            if (!evidence_ || frame->payload.size() != 7 || frame->payload[0] > 1)
                return error_frame(SyncError::InvalidFrame, "bad evidence request");
            const auto& req = frame->payload;
            const unsigned kind = req[0];
            const std::uint32_t index = req[1] | (std::uint32_t(req[2]) << 8) | (std::uint32_t(req[3]) << 16) | (std::uint32_t(req[4]) << 24);
            const unsigned offset = req[5] | (unsigned(req[6]) << 8);
            if (index > (kind == 0 ? 128U : 16U)) return error_frame(SyncError::InvalidFrame, "evidence index exceeds bounds");
            const auto boots = evidence_->boots();
            std::string bytes;
            if (kind == 0) {
                if (index < boots.size()) bytes = "{\"boot\":" + boot_json(boots[index]) + "}";
            } else {
                const auto anchors = evidence_->anchors();
                if (index < anchors.size()) {
                    const auto& a = anchors[index];
                    auto boot = std::find_if(boots.begin(), boots.end(), [&](const auto& b) {
                        return b.node_id == a.node_id && b.incarnation == a.incarnation && b.boot_counter == a.boot_counter;
                    });
                    if (boot == boots.end()) return error_frame(SyncError::Internal, "missing boot evidence");
                    bytes = anchor_json(*boot, a);
                }
            }
            if (bytes.size() > 8192 || offset > bytes.size() || (offset && offset == bytes.size()))
                return error_frame(SyncError::InvalidFrame, "bad evidence offset or size");
            std::vector<std::uint8_t> payload(req.begin(), req.end());
            payload.push_back(static_cast<std::uint8_t>(bytes.size()));
            payload.push_back(static_cast<std::uint8_t>(bytes.size() >> 8));
            const auto size = std::min<std::size_t>(480, bytes.size() - offset);
            payload.insert(payload.end(), bytes.begin() + offset, bytes.begin() + offset + size);
            return encode_frame({1, Opcode::TimeEvidenceResponse, payload});
        }
        case Opcode::RegistrationTimeChallengeRequest: {
            if (!frame->payload.empty()) return error_frame(SyncError::InvalidFrame, "bad time challenge request");
            if (!identity_ || !identity_->ready() || !time_bootstrap_)
                return error_frame(SyncError::ClockUnavailable, "signed time bootstrap unavailable");
            node_time::NodeTimeChallenge challenge;
            const auto& node = identity_->identity();
            if (!time_bootstrap_->create_challenge(node.public_key, node.private_key, challenge))
                return error_frame(SyncError::ClockUnavailable, "time challenge unavailable");
            // 239 bytes, bounded below ATT's 512-byte attribute limit. Explicit
            // little-endian encoding, never memcpy of a padded native struct.
            std::vector<std::uint8_t> payload;
            auto append = [&](const auto& bytes) { payload.insert(payload.end(), bytes.begin(), bytes.end()); };
            auto integer = [&](std::uint64_t value, unsigned size) {
                for (unsigned i = 0; i < size; ++i) payload.push_back(static_cast<std::uint8_t>(value >> (8 * i)));
            };
            integer(challenge.version, 2);
            append(challenge.node_id.bytes); append(challenge.incarnation.bytes);
            integer(challenge.boot_counter, 4); append(challenge.nonce);
            integer(challenge.challenge_uptime_ms, 8); append(challenge.device_public_key);
            append(challenge.fingerprint); append(challenge.signature);
            return encode_frame({1, Opcode::RegistrationTimeChallengeResponse, payload});
        }
        case Opcode::RegistrationTimeProofRequest: {
            auto proof = decode_claim_receipt(frame->payload); // same bounded UTF-8/JWS envelope
            if (!proof) return error_frame(SyncError::InvalidFrame, "bad time proof");
            if (!identity_ || !identity_->ready() || !time_bootstrap_)
                return error_frame(SyncError::ClockUnavailable, "signed time bootstrap unavailable");
            const auto& node = identity_->identity();
            std::string acceptance;
            if (!time_bootstrap_->submit_proof(*proof, node.public_key, node.private_key, acceptance))
                return error_frame(SyncError::InvalidProof, "time proof rejected");
            // The manager durably records the anchor and signed acceptance before
            // success. This exchange grants time trust only, never ownership/auth.
            return encode_frame({1, Opcode::RegistrationTimeProofResponse, {0}});
        }
        case Opcode::ClaimAdvertisementRequest: {
            ClaimAdvertisement advertisement;
            if (!frame->payload.empty()) return error_frame(SyncError::InvalidFrame, "bad claim request");
            if (!identity_ || !identity_->sign_claim(advertisement)) return error_frame(SyncError::InvalidState, "UNCLAIMED ready identity and trusted UTC required");
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
            hello.caps |= kCapClockSnapshot;
            if (identity_) hello.caps |= kCapMetadata;
            if (alpha_clock_) hello.caps |= kCapFieldAlphaPhoneClock;
            if (time_bootstrap_ && evidence_) hello.caps |= kCapTimeEvidence;
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
            metadata_fragments_.clear(); metadata_total_ = 0;
            if (!frame->payload.empty()) return error_frame(SyncError::InvalidFrame, "bad challenge");
            std::array<std::uint8_t, 32> nonce{};
            if (!server_->begin_challenge(nonce)) {
                return error_frame(SyncError::Internal, "challenge unavailable");
            }
            return encode_frame(Frame{kProtocolVersion, Opcode::ChallengeResponse,
                                      encode_challenge_response(nonce)});
        }
        case Opcode::AuthRequest: {
            metadata_fragments_.clear(); metadata_total_ = 0;
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
