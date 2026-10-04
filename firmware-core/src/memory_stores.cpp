#include "miezmerker/memory_stores.hpp"

namespace miezmerker {

// --- InMemoryObservationStore ---

InMemoryObservationStore::InMemoryObservationStore(std::size_t capacity) : capacity_(capacity) {}

PutResult InMemoryObservationStore::append(const RawObservation& observation) {
    ++append_attempts_;
    if (fail_next_append_) {
        // Simulated power loss / flash error: nothing is committed, earlier
        // records stay intact.
        fail_next_append_ = false;
        return PutResult::IO_ERROR;
    }
    if (!observation.valid()) return PutResult::IO_ERROR;
    if (records_.size() >= capacity_) return PutResult::FULL;
    records_.push_back(observation);
    if (observation.sequence > high_sequence_) high_sequence_ = observation.sequence;
    return PutResult::OK;
}

std::vector<RawObservation> InMemoryObservationStore::load_all() { return records_; }

std::size_t InMemoryObservationStore::size() const { return records_.size(); }

std::size_t InMemoryObservationStore::capacity() const { return capacity_; }

StoreStatus InMemoryObservationStore::status() const {
    if (records_.size() >= capacity_) return StoreStatus::FULL;
    if (capacity_ > 0 && records_.size() > 0) {
        // NEARLY_FULL at >= 90% occupancy (integer math, no floats in core).
        const std::size_t threshold = capacity_ - capacity_ / 10;
        if (records_.size() >= threshold) return StoreStatus::NEARLY_FULL;
    }
    return StoreStatus::OK;
}

std::uint64_t InMemoryObservationStore::ack_watermark() const { return ack_watermark_; }

bool InMemoryObservationStore::set_ack_watermark(std::uint64_t sequence) {
    if (fail_next_watermark_) {
        fail_next_watermark_ = false;
        return false;
    }
    if (sequence < ack_watermark_) return false;
    if (sequence == ack_watermark_) return true;
    // The durable high-water mark survives prune_acked(): an empty log with a
    // nonzero watermark still accepts the same watermark idempotently, but
    // nothing beyond the highest sequence ever appended.
    const std::uint64_t last =
        records_.empty() ? high_sequence_ : records_.back().sequence > high_sequence_
                                                   ? records_.back().sequence
                                                   : high_sequence_;
    if (last == 0 || sequence > last) return false;
    ack_watermark_ = sequence;
    return true;
}

bool InMemoryObservationStore::prune_acked() {
    if (fail_next_prune_) {
        fail_next_prune_ = false;
        return false;
    }
    std::vector<RawObservation> kept;
    kept.reserve(records_.size());
    for (const auto& r : records_) {
        if (r.sequence > ack_watermark_) kept.push_back(r);
    }
    records_.swap(kept);
    return true;
}

bool InMemoryObservationStore::clear() {
    if (fail_next_clear_) {
        fail_next_clear_ = false;
        return false;
    }
    records_.clear();
    ack_watermark_ = 0;
    high_sequence_ = 0;
    return true;
}

// --- InMemoryIdentityStore ---

IdentityLoadResult InMemoryIdentityStore::load(IdentityRecord& out) {
    if (fail_next_load_) {
        fail_next_load_ = false;
        return IdentityLoadResult::IO_ERROR;
    }
    if (!has_record_) return IdentityLoadResult::NOT_FOUND;
    out = record_;
    return IdentityLoadResult::OK;
}

bool InMemoryIdentityStore::store(const IdentityRecord& record) {
    ++store_attempts_;
    if (fail_next_store_) {
        // Simulated torn write: previous record stays readable.
        fail_next_store_ = false;
        return false;
    }
    if (!record.valid()) return false;
    record_ = record;
    has_record_ = true;
    return true;
}

bool InMemoryIdentityStore::erase() {
    record_ = IdentityRecord{};
    has_record_ = false;
    return true;
}

// --- FixedRandomSource ---

FixedRandomSource::FixedRandomSource(std::uint8_t seed) : seed_(seed) {}

void FixedRandomSource::fill_random(std::span<std::uint8_t> out) {
    for (auto& b : out) {
        // Simple deterministic LCG-like stream; only test entropy.
        counter_ = counter_ * 6364136223846793005ULL + 1442695040888963407ULL + seed_;
        b = static_cast<std::uint8_t>((counter_ >> 33) & 0xFFu);
    }
    if (out.empty()) return;
    // Ensure nonzero output even for seed 0 / counter start.
    bool all_zero = true;
    for (const auto b : out) {
        if (b != 0) {
            all_zero = false;
            break;
        }
    }
    if (all_zero) out[0] = 0x01;
}

// --- ManualClock ---

ManualClock::ManualClock() { wall_.status = ClockStatus::UNKNOWN; }

std::uint64_t ManualClock::monotonic_ms() const { return monotonic_ms_; }

WallClockReading ManualClock::wall_clock() const { return wall_; }

void ManualClock::set_wall_clock(ClockStatus status, std::optional<std::uint64_t> epoch_ms) {
    wall_.status = status;
    if (status == ClockStatus::UNKNOWN) {
        wall_.epoch_ms.reset();
    } else {
        wall_.epoch_ms = epoch_ms;
    }
}

}  // namespace miezmerker
