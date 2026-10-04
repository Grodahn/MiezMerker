#include "miezmerker/ble_sync.hpp"

#include <algorithm>
#include <limits>

namespace miezmerker::ble {

SyncServer::SyncServer(Core& core, SyncAuthorizer& authorizer, RtcControl& rtc,
                       NodeSigner& signer, const SyncConfig& config)
    : core_(&core), authorizer_(&authorizer), rtc_(&rtc), signer_(&signer), config_(config) {}

void SyncServer::disconnect() {
    authorizer_->disconnect();
}

HelloPublic SyncServer::public_hello() const {
    HelloPublic msg;
    msg.server_ver = kProtocolVersion;
    msg.caps = kCapAllV1;
    msg.node_id = core_->node_id();
    msg.incarnation = core_->incarnation();
    msg.firmware_version = config_.firmware_version;
    msg.claim_state = core_->claim_state();
    // Public technical clock status, without exposing an observation's time.
    msg.clock_status = core_->wall_clock().status;
    return msg;
}

OwnerInfo SyncServer::public_owner() const {
    // Owner metadata must come from the durable claim record. Core only
    // persists ClaimState, not org strings; the ESP32 owner adapter injects
    // them via a separate port in the GATT layer. The portable server returns
    // the claim marker with empty org fields when unclaimed, and the caller
    // (GATT adapter) overlays backend-provisioned strings for CLAIMED nodes.
    // This keeps firmware-core free of string persistence while preserving
    // the "public owner hint, no leakage" contract: no chip/pending/epoch.
    OwnerInfo msg;
    msg.node_id = core_->node_id();
    msg.claim_state = core_->claim_state();
    return msg;
}

Advertisement SyncServer::advertisement(bool claim_mode_active) const {
    Advertisement adv;
    adv.protocol_version = kProtocolVersion;
    adv.claimed = claimed();
    adv.claim_mode = claim_mode_active;
    return adv;
}

bool SyncServer::begin_challenge(std::array<std::uint8_t, 32>& challenge) {
    return authorizer_->begin(challenge);
}

bool SyncServer::authorize(const std::string& credential,
                           const std::array<std::uint8_t, 64>& proof,
                           TrustedEpochSeconds now_s) {
    if (credential.size() > config_.max_credential_bytes) {
        authorizer_->disconnect();  // Every failed attempt consumes the session.
        return false;
    }
    return authorizer_->authorize(credential, proof, now_s);
}

bool SyncServer::claimed() const {
    return core_->claim_state() == ClaimState::CLAIMED;
}

bool SyncServer::authorized(TrustedEpochSeconds now_s) const {
    return core_->ready() && authorizer_->can_sync(now_s);
}

std::uint32_t SyncServer::pending_count() const {
    const std::uint64_t ack = core_->ack_watermark();
    std::uint32_t pending = 0;
    for (const auto& obs : const_cast<Core*>(core_)->load_observations()) {
        if (obs.node_id == core_->node_id() && obs.sequence > ack) ++pending;
    }
    return pending;
}

std::uint64_t SyncServer::ack_watermark() const {
    return core_->ack_watermark();
}

std::optional<StatusResponse> SyncServer::status(TrustedEpochSeconds now_s) const {
    if (!authorized(now_s)) return std::nullopt;
    if (!claimed()) return std::nullopt;
    StatusResponse msg;
    msg.pending = pending_count();
    msg.ack_watermark = core_->ack_watermark();
    msg.store_status = core_->store_status();
    const auto wall = core_->wall_clock();
    msg.clock_status = wall.status;
    msg.epoch_ms = wall.epoch_ms.value_or(0);
    msg.next_sequence = core_->next_sequence();
    return msg;
}

SyncServer::BatchResult SyncServer::batch(std::uint64_t from_sequence, std::uint16_t max_records,
                                          TrustedEpochSeconds now_s) const {
    BatchResult result;
    if (!authorized(now_s)) {
        result.error = SyncError::Unauthorized;
        return result;
    }
    if (!claimed()) {
        result.error = SyncError::InvalidState;
        return result;
    }
    if (from_sequence == kInvalidSequence || max_records == 0 || max_records > 64) {
        result.error = SyncError::InvalidFrame;
        return result;
    }
    auto all = const_cast<Core*>(core_)->load_observations();
    // Filter to this node lifetime, ordered by sequence (store keeps order).
    std::vector<RawObservation> owned;
    owned.reserve(all.size());
    for (const auto& obs : all) {
        if (obs.node_id == core_->node_id()) {
            if (!obs.valid()) {
                result.error = SyncError::InvalidRecord;
                return result;
            }
            owned.push_back(obs);
        }
    }
    std::sort(owned.begin(), owned.end(),
              [](const RawObservation& a, const RawObservation& b) { return a.sequence < b.sequence; });
    std::uint64_t last = kInvalidSequence;
    if (!owned.empty()) last = owned.back().sequence;
    if (last != kInvalidSequence && from_sequence > last + 1) {
        // Allow from == last+1 (empty, caught up); beyond is a client bug.
        result.error = SyncError::InvalidSequence;
        return result;
    }
    const std::uint16_t limit =
        std::min<std::uint16_t>(max_records, config_.max_batch_records);
    result.batch.from_sequence = from_sequence;
    for (const auto& obs : owned) {
        if (obs.sequence < from_sequence) continue;
        if (result.batch.records.size() >= limit) break;
        result.batch.records.push_back(obs);
    }
    if (!result.batch.records.empty()) {
        result.batch.next_cursor = result.batch.records.back().sequence + 1;
        if (result.batch.next_cursor == kInvalidSequence) {
            result.error = SyncError::InvalidSequence;
            result.batch.records.clear();
            return result;
        }
    } else {
        result.batch.next_cursor = from_sequence;
    }
    // more = whether unserved records remain at or after next_cursor.
    result.batch.more = false;
    for (const auto& obs : owned) {
        if (obs.sequence >= result.batch.next_cursor) {
            // next_cursor points past the last served record; any record at or
            // beyond it means more pages remain.
            if (obs.sequence >= result.batch.next_cursor &&
                (result.batch.records.empty() || obs.sequence > result.batch.records.back().sequence)) {
                result.batch.more = true;
                break;
            }
        }
    }
    result.error = SyncError::Ok;
    return result;
}

SyncServer::AckResult SyncServer::ack(std::uint64_t watermark, TrustedEpochSeconds now_s) {
    AckResult result;
    if (!authorized(now_s)) {
        result.error = SyncError::Unauthorized;
        return result;
    }
    if (!claimed()) {
        result.error = SyncError::InvalidState;
        return result;
    }
    result.new_watermark = core_->ack_watermark();
    if (watermark == kInvalidSequence && watermark != core_->ack_watermark()) {
        // ACK 0 is only legal as idempotent no-op when nothing acked yet.
        if (core_->ack_watermark() != 0) {
            result.error = SyncError::InvalidSequence;
            return result;
        }
        result.error = SyncError::Ok;
        return result;
    }
    if (watermark < core_->ack_watermark()) {
        result.error = SyncError::InvalidSequence;
        return result;
    }
    if (watermark == core_->ack_watermark()) {
        result.error = SyncError::Ok;  // idempotent replay (lost ACK path).
        return result;
    }
    // A numeric high-watermark must not jump an absent sequence. Reserved
    // sequences lost before append remain gaps; no range/tombstone protocol
    // exists in v1 that would let the collector prove such a gap durable.
    std::vector<std::uint64_t> sequences;
    for (const auto& obs : core_->load_observations()) {
        if (obs.node_id == core_->node_id() && obs.valid()) sequences.push_back(obs.sequence);
    }
    if (watermark > contiguous_watermark(core_->ack_watermark(), sequences)) {
        result.error = SyncError::InvalidSequence;
        return result;
    }
    if (!core_->set_ack_watermark(watermark)) {
        result.error = SyncError::Internal;  // Durable watermark write failed.
        return result;
    }
    result.new_watermark = core_->ack_watermark();
    result.error = SyncError::Ok;
    return result;
}

SyncServer::TimeResult SyncServer::correct_time(std::uint64_t epoch_ms,
                                                TrustedEpochSeconds now_s) {
    TimeResult result;
    if (!authorized(now_s)) {
        result.error = SyncError::Unauthorized;
        return result;
    }
    if (!claimed()) {
        result.error = SyncError::InvalidState;
        return result;
    }
    // Sanity bounds: 2020-01-01 .. 2100-01-01 UTC ms. Rejects 0/unknown and
    // absurd values without touching the RTC. Past observations are immutable
    // by core construction; only future record_chip_read calls observe the
    // corrected clock.
    constexpr std::uint64_t kMinSane = 1577836800000ULL;
    constexpr std::uint64_t kMaxSane = 4102444800000ULL;
    if (epoch_ms < kMinSane || epoch_ms > kMaxSane) {
        result.error = SyncError::InvalidFrame;
        return result;
    }
    if (!rtc_->set_corrected_epoch_ms(epoch_ms)) {
        result.error = SyncError::ClockUnavailable;
        return result;
    }
    result.ok = true;
    result.error = SyncError::Ok;
    result.applied_epoch_ms = epoch_ms;
    return result;
}

SyncServer::CompactResult SyncServer::compact(TrustedEpochSeconds now_s) {
    CompactResult result;
    result.ack_watermark = core_->ack_watermark();
    if (!authorized(now_s)) {
        result.error = SyncError::Unauthorized;
        return result;
    }
    if (!claimed()) {
        result.error = SyncError::InvalidState;
        return result;
    }
    const std::size_t before = core_->observation_count();
    // Explicit compaction of the acked prefix. Atomic from the sync client's
    // view; reboot between Ack and Compact is safe because the watermark is
    // durable and Compact is idempotent.
    if (!core_->compact_acked()) {
        result.error = SyncError::Internal;
        return result;
    }
    const std::size_t after = core_->observation_count();
    result.freed = static_cast<std::uint32_t>(before >= after ? before - after : 0);
    result.remaining = static_cast<std::uint32_t>(after);
    result.ack_watermark = core_->ack_watermark();
    result.error = SyncError::Ok;
    return result;
}

SyncServer::ProofResult SyncServer::node_proof(const std::array<std::uint8_t, 32>& pwa_nonce,
                                               TrustedEpochSeconds now_s) {
    ProofResult result;
    result.error = SyncError::Unauthorized;
    if (!authorized(now_s)) return result;
    if (!claimed()) {
        result.error = SyncError::InvalidState;
        return result;
    }
    if (!signer_->sign_session(pwa_nonce, result.signature)) {
        result.error = SyncError::Internal;
        return result;
    }
    result.error = SyncError::Ok;
    return result;
}

std::uint64_t contiguous_watermark(std::uint64_t base,
                                   const std::vector<std::uint64_t>& stored) {
    std::uint64_t w = base;
    auto ordered = stored;
    std::sort(ordered.begin(), ordered.end());
    for (const auto sequence : ordered) {
        if (sequence <= w) continue;
        if (w == std::numeric_limits<std::uint64_t>::max() || sequence != w + 1 ||
            sequence == std::numeric_limits<std::uint64_t>::max()) break;
        w = sequence;
    }
    return w;
}

}  // namespace miezmerker::ble
