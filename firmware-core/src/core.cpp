#include "miezmerker/core.hpp"

#include <limits>
#include <span>

namespace miezmerker {

Core::Core(Clock& clock, Storage& storage, ObservationStore& observations,
           DeviceIdentityStore& identity, RandomSource& random, const CoreConfig& config)
    : clock_(&clock),
      storage_(&storage),
      observations_(&observations),
      identity_store_(&identity),
      random_(&random),
      config_(config),
      legacy_mode_(false) {}

Core::Core(Clock& clock, Storage& storage)
    : clock_(&clock), storage_(&storage), legacy_mode_(true) {}

bool Core::provision_fresh_identity(bool is_factory_reset) {
    if (identity_store_ == nullptr || random_ == nullptr) return false;
    NodeId node{};
    IncarnationId incarnation{};
    random_->fill_random(std::span<std::uint8_t>(node.bytes.data(), node.bytes.size()));
    random_->fill_random(std::span<std::uint8_t>(incarnation.bytes.data(), incarnation.bytes.size()));
    // Check before setting UUID format bits, which would hide an all-zero
    // entropy failure behind an apparently nonzero identifier.
    if (!node.is_set() || !incarnation.is_set()) return false;
    apply_uuidv4_variant(node.bytes);
    apply_uuidv4_variant(incarnation.bytes);
    // A repeated RNG stream must never restart an existing sequence lifetime.
    if (is_factory_reset && (node == identity_.node_id || incarnation == identity_.incarnation)) {
        return false;
    }
    for (const auto& obs : observations_->load_all()) {
        if (obs.node_id == node) return false;
    }
    IdentityRecord fresh{};
    fresh.node_id = node;
    fresh.incarnation = incarnation;
    fresh.next_sequence = kFirstSequence;
    fresh.boot_counter = 1;
    fresh.claim_state = ClaimState::UNCLAIMED;
    fresh.reset_pending = is_factory_reset;
    if (!fresh.valid()) return false;
    if (!identity_store_->store(fresh)) return false;
    identity_ = fresh;
    return true;
}

bool Core::complete_pending_reset() {
    if (!identity_.reset_pending) return true;
    // Persisting the fresh identity first makes this two-store operation
    // resumable. Until the journal is cleared, no read or sync is permitted.
    if (!observations_->clear()) return false;
    IdentityRecord completed = identity_;
    completed.reset_pending = false;
    if (!identity_store_->store(completed)) return false;
    identity_ = completed;
    last_recorded_ms_.clear();
    return true;
}

bool Core::reconcile_after_load() {
    if (observations_ == nullptr || identity_store_ == nullptr) return false;
    std::vector<RawObservation> records = observations_->load_all();
    Sequence max_seen = kInvalidSequence;
    for (const auto& obs : records) {
        if (!obs.valid()) return false;
        // Sequence belongs to node_id's lifetime, even if incarnation metadata
        // differs. A fresh lifetime is permitted only with a fresh node_id.
        if (obs.node_id == identity_.node_id) {
            if (obs.sequence > max_seen) max_seen = obs.sequence;
        }
    }
    Sequence required_next = identity_.next_sequence;
    if (max_seen != kInvalidSequence) {
        if (max_seen == std::numeric_limits<Sequence>::max()) return false;
        const Sequence after_max = max_seen + 1;
        if (after_max > required_next) required_next = after_max;
    }
    if (required_next != identity_.next_sequence) {
        IdentityRecord advanced = identity_;
        advanced.next_sequence = required_next;
        if (!advanced.valid()) return false;
        if (!identity_store_->store(advanced)) return false;
        identity_ = advanced;
    }
    return true;
}

bool Core::initialize() {
    ready_ = false;
    if (legacy_mode_) {
        // Preserved #2 boot gate.
        ready_ = storage_ != nullptr && storage_->open();
        started_at_ms_ = ready_ && clock_ != nullptr ? clock_->monotonic_ms() : 0;
        return ready_;
    }
    if (clock_ == nullptr || storage_ == nullptr || observations_ == nullptr ||
        identity_store_ == nullptr || random_ == nullptr) {
        ready_ = false;
        return false;
    }
    if (!storage_->open()) {
        ready_ = false;
        started_at_ms_ = 0;
        return false;
    }
    started_at_ms_ = clock_->monotonic_ms();

    IdentityRecord loaded{};
    const auto load = identity_store_->load(loaded);
    if (load == IdentityLoadResult::NOT_FOUND) {
        // Only a genuinely empty device may be provisioned automatically.
        // An orphaned log/cursor requires explicit recovery or factory reset.
        if (observations_->size() != 0 || observations_->ack_watermark() != 0) return false;
        if (!provision_fresh_identity(false)) {
            ready_ = false;
            return false;
        }
        last_recorded_ms_.clear();
        ready_ = true;
        return true;
    }
    if (load != IdentityLoadResult::OK || !loaded.valid()) return false;
    identity_ = loaded;
    if (!complete_pending_reset()) return false;

    // Normal reboot path: identity (node_id + incarnation) is retained.
    // Reconcile the sequence counter before accepting any read so no normal
    // crash/reboot path can reuse a (node_id, sequence) pair.
    if (!reconcile_after_load()) {
        ready_ = false;
        return false;
    }
    // New boot epoch (technical metadata, persisted).
    if (identity_.boot_counter == std::numeric_limits<std::uint32_t>::max()) {
        ready_ = false;
        return false;
    }
    IdentityRecord rebooted = identity_;
    rebooted.boot_counter += 1;
    if (!rebooted.valid() || !identity_store_->store(rebooted)) {
        ready_ = false;
        return false;
    }
    identity_ = rebooted;
    last_recorded_ms_.clear();
    ready_ = true;
    return true;
}

RecordResult Core::record_chip_read(const std::string& chip_id) {
    if (legacy_mode_) return RecordResult::NOT_READY;
    if (!ready_ || clock_ == nullptr || observations_ == nullptr || identity_store_ == nullptr) {
        return RecordResult::NOT_READY;
    }
    if (chip_id.empty() || chip_id.size() > ChipId::kMaxLength) {
        return RecordResult::REJECTED_INVALID;
    }
    const std::uint64_t now_ms = clock_->monotonic_ms();

    // Technical debounce: fixed-window sampling per identical chip_id on the
    // monotonic clock. Suppressed repeats create nothing and delete nothing;
    // only a RECORDED read refreshes the window. Different chip IDs are
    // independent. This is radio-repeat suppression, never visit grouping.
    if (config_.debounce_interval_ms > 0) {
        const auto it = last_recorded_ms_.find(chip_id);
        if (it != last_recorded_ms_.end()) {
            const std::uint64_t elapsed = now_ms - it->second;
            if (elapsed < config_.debounce_interval_ms) return RecordResult::DEBOUNCED;
        }
    }

    // RTC value actually observed at read time, with the trust status that
    // applied at that instant. Never synthesize a trustworthy timestamp.
    WallClockReading wall = clock_->wall_clock();
    ClockStatus status = wall.status;
    std::optional<std::uint64_t> epoch = wall.epoch_ms;
    if (status == ClockStatus::UNKNOWN) {
        epoch.reset();
    } else if (!epoch.has_value()) {
        status = ClockStatus::UNKNOWN;
    }

    // Sample the RTC before any potentially slow persistence-adapter call.
    if (observations_->status() == StoreStatus::FULL) return RecordResult::REJECTED_FULL;

    // Reserve-then-append: persist the advanced counter BEFORE the observation
    // so any crash between the two leaves a gap, never a duplicate.
    const Sequence reserved = identity_.next_sequence;
    if (reserved == kInvalidSequence) return RecordResult::IO_ERROR;
    if (reserved == std::numeric_limits<Sequence>::max()) {
        // Unreachable in practice (2^64 reads); stuck rather than wrap/reuse.
        return RecordResult::IO_ERROR;
    }
    IdentityRecord advanced = identity_;
    advanced.next_sequence = reserved + 1;
    if (!advanced.valid() || !identity_store_->store(advanced)) {
        return RecordResult::IO_ERROR;
    }
    identity_.next_sequence = reserved + 1;

    RawObservation obs{};
    obs.node_id = identity_.node_id;
    obs.incarnation = identity_.incarnation;
    obs.sequence = reserved;
    obs.chip_id.value = chip_id;
    obs.observed_at_epoch_ms = epoch;
    obs.clock_status = status;
    obs.monotonic_ms = now_ms;
    obs.boot_counter = identity_.boot_counter;
    if (!obs.valid()) return RecordResult::IO_ERROR;

    const PutResult put = observations_->append(obs);
    if (put == PutResult::FULL) return RecordResult::REJECTED_FULL;
    if (put != PutResult::OK) return RecordResult::IO_ERROR;

    if (config_.debounce_interval_ms > 0) {
        // Keep only live windows rather than all chips ever seen by the node.
        for (auto it = last_recorded_ms_.begin(); it != last_recorded_ms_.end();) {
            if (now_ms - it->second >= config_.debounce_interval_ms) {
                it = last_recorded_ms_.erase(it);
            } else {
                ++it;
            }
        }
        last_recorded_ms_[chip_id] = now_ms;
    }
    return RecordResult::RECORDED;
}

std::optional<RecordResult> Core::poll_reader(RfidReader& reader) {
    const std::optional<std::string> chip = reader.poll();
    if (!chip.has_value()) return std::nullopt;
    return record_chip_read(*chip);
}

bool Core::factory_reset() {
    if (legacy_mode_) return false;
    ready_ = false;
    if (clock_ == nullptr || storage_ == nullptr || observations_ == nullptr ||
        identity_store_ == nullptr || random_ == nullptr) {
        return false;
    }
    if (!storage_->open()) {
        ready_ = false;
        return false;
    }
    // Read the durable identity even when called before initialize(). Never
    // erase it before generating the replacement: it is the collision guard
    // and the journal needed to resume reset after any interruption.
    IdentityRecord loaded{};
    const auto load = identity_store_->load(loaded);
    if (load != IdentityLoadResult::OK && load != IdentityLoadResult::NOT_FOUND) return false;
    if (load == IdentityLoadResult::OK) {
        identity_ = loaded;
        if (identity_.valid() && identity_.reset_pending) {
            if (!complete_pending_reset()) return false;
            started_at_ms_ = clock_->monotonic_ms();
            ready_ = true;
            return true;
        }
    }
    if (!provision_fresh_identity(true)) {
        ready_ = false;
        return false;
    }
    if (!complete_pending_reset()) return false;
    started_at_ms_ = clock_->monotonic_ms();
    ready_ = true;
    return true;
}

std::size_t Core::observation_count() const {
    if (observations_ == nullptr) return 0;
    return observations_->size();
}

StoreStatus Core::store_status() const {
    if (observations_ == nullptr) return StoreStatus::OK;
    return observations_->status();
}

std::uint64_t Core::ack_watermark() const {
    if (observations_ == nullptr) return 0;
    return observations_->ack_watermark();
}

bool Core::set_ack_watermark(std::uint64_t sequence) {
    if (!ready_ || observations_ == nullptr) return false;
    // Never pre-acknowledge data that has not yet been recorded. Gaps are
    // allowed, but the cursor cannot pass the last durable observation.
    Sequence last = kInvalidSequence;
    for (const auto& obs : observations_->load_all()) {
        if (obs.node_id == identity_.node_id && obs.sequence > last) last = obs.sequence;
    }
    if (sequence > last) {
        // After prune_acked() the acked prefix is gone; the durable watermark
        // itself remains the proof of the highest acked sequence. Re-ACKing
        // the same watermark is idempotent even when the log is empty.
        if (sequence != ack_watermark() || observations_->size() != 0) return false;
        return observations_->set_ack_watermark(sequence);
    }
    return observations_->set_ack_watermark(sequence);
}

bool Core::compact_acked() {
    if (!ready_ || observations_ == nullptr) return false;
    if (identity_.reset_pending) return false;
    return observations_->prune_acked();
}

std::vector<RawObservation> Core::load_observations() {
    if (legacy_mode_ || !ready_ || observations_ == nullptr) return {};
    return observations_->load_all();
}

}  // namespace miezmerker
