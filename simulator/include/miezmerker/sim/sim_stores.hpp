#pragma once

// Deterministic simulator adapters around the firmware-core ports (issue #22).
//
// These fakes contain no firmware business logic. They wrap the in-memory
// durable-media cells from firmware-core (which outlive Core, modelling
// flash) and add scenario-level fault injection: persistence failures and
// power loss at defined points.

#include <cstddef>
#include <cstdint>
#include <span>
#include <vector>

#include "miezmerker/memory_stores.hpp"
#include "miezmerker/ports.hpp"

namespace miezmerker::sim {

/// Thrown by simulated durable media to model a power loss exactly when the
/// scenario armed a crash. The Core (volatile) is destroyed by the runner;
/// the media outlive it, so the next boot observes the committed prefix.
struct PowerLoss {};

/// Controllable storage availability (legacy #2 boot gate).
class SimStorage final : public Storage {
public:
    void set_available(bool available) { available_ = available; }
    bool open() override { return available_; }

private:
    bool available_{true};
};

/// Deterministic entropy for the simulator: seeds a fixed byte stream.
/// Repeated seeds produce identical sequences, enabling collision tests.
class SimRandomSource final : public RandomSource {
public:
    explicit SimRandomSource(std::uint64_t seed = 0xA5A5A5A5A5A5A5A5ULL)
        : seed_(seed), counter_(0) {}

    void fill_random(std::span<std::uint8_t> out) override {
        for (auto& b : out) {
            counter_ = counter_ * 6364136223846793005ULL + 1442695040888963407ULL + seed_;
            b = static_cast<std::uint8_t>((counter_ >> 33) & 0xFFu);
        }
        if (out.empty()) return;
        bool all_zero = true;
        for (const auto b : out) {
            if (b != 0) { all_zero = false; break; }
        }
        if (all_zero) out[0] = 0x01;
    }

private:
    std::uint64_t seed_;
    std::uint64_t counter_{0};
};

/// Simulated observation store with fault injection.
/// Wraps InMemoryObservationStore (durable media) and adds scenario-level
/// fault injection: fail next append, crash after Nth append, etc.
class SimObservationStore final : public ObservationStore {
public:
    explicit SimObservationStore(std::size_t capacity = 4096) : media_(capacity) {}

    // ObservationStore interface
    PutResult append(const RawObservation& observation) override {
        ++append_count_;
        if (fail_append_at_ == append_count_) {
            fail_append_at_ = 0;
            return PutResult::IO_ERROR;
        }
        if (fail_next_append_) {
            fail_next_append_ = false;
            return PutResult::IO_ERROR;
        }
        const PutResult result = media_.append(observation);
        if (result == PutResult::OK && crash_append_at_ == append_count_) {
            crash_append_at_ = 0;
            throw PowerLoss{};
        }
        return result;
    }

    std::vector<RawObservation> load_all() override { return media_.load_all(); }
    std::size_t size() const override { return media_.size(); }
    std::size_t capacity() const override { return media_.capacity(); }
    StoreStatus status() const override { return media_.status(); }
    std::uint64_t ack_watermark() const override { return media_.ack_watermark(); }
    bool set_ack_watermark(std::uint64_t sequence) override {
        if (fail_next_watermark_) {
            fail_next_watermark_ = false;
            return false;
        }
        return media_.set_ack_watermark(sequence);
    }
    bool clear() override {
        if (fail_next_clear_) {
            fail_next_clear_ = false;
            return false;
        }
        if (crash_next_clear_) {
            crash_next_clear_ = false;
            throw PowerLoss{};
        }
        return media_.clear();
    }

    // Fault injection (scenario engine)
    void reset_counters() { append_count_ = 0; }
    void disarm() {
        fail_append_at_ = 0;
        crash_append_at_ = 0;
        fail_next_append_ = false;
        fail_next_clear_ = false;
        crash_next_clear_ = false;
        fail_next_watermark_ = false;
        append_count_ = 0;
    }

    void fail_append_at(std::size_t attempt) { fail_append_at_ = attempt; }
    void crash_append_at(std::size_t attempt) { crash_append_at_ = attempt; }
    void fail_next_append() { fail_next_append_ = true; }
    void fail_next_clear() { fail_next_clear_ = true; }
    void crash_next_clear() { crash_next_clear_ = true; }
    void fail_next_watermark() { fail_next_watermark_ = true; }

    bool has_crash_armed() const {
        return crash_append_at_ != 0 || crash_next_clear_;
    }

    const InMemoryObservationStore& media() const { return media_; }

private:
    InMemoryObservationStore media_;
    std::size_t append_count_{0};
    std::size_t fail_append_at_{0};
    std::size_t crash_append_at_{0};
    bool fail_next_append_{false};
    bool fail_next_clear_{false};
    bool crash_next_clear_{false};
    bool fail_next_watermark_{false};
};

/// Simulated device identity store with fault injection.
/// Wraps InMemoryIdentityStore (durable media) and adds scenario-level
/// fault injection: fail next store, crash after Nth store, etc.
class SimDeviceIdentity final : public DeviceIdentityStore {
public:
    SimDeviceIdentity() = default;

    // DeviceIdentityStore interface
    IdentityLoadResult load(IdentityRecord& out) override {
        if (fail_next_load_) {
            fail_next_load_ = false;
            return IdentityLoadResult::IO_ERROR;
        }
        return media_.load(out);
    }

    bool store(const IdentityRecord& record) override {
        ++store_count_;
        if (fail_store_at_ == store_count_) {
            fail_store_at_ = 0;
            return false;
        }
        if (fail_next_store_) {
            fail_next_store_ = false;
            return false;
        }
        const bool result = media_.store(record);
        if (result && crash_store_at_ == store_count_) {
            crash_store_at_ = 0;
            throw PowerLoss{};
        }
        return result;
    }

    bool erase() override { return media_.erase(); }

    // Fault injection (scenario engine)
    void reset_counters() { store_count_ = 0; }
    void disarm() {
        fail_store_at_ = 0;
        crash_store_at_ = 0;
        fail_next_store_ = false;
        fail_next_load_ = false;
        store_count_ = 0;
    }

    void fail_store_at(std::size_t attempt) { fail_store_at_ = attempt; }
    void crash_store_at(std::size_t attempt) { crash_store_at_ = attempt; }
    void fail_next_store() { fail_next_store_ = true; }
    void fail_next_load() { fail_next_load_ = true; }

    bool has_crash_armed() const { return crash_store_at_ != 0; }

    const InMemoryIdentityStore& media() const { return media_; }

private:
    InMemoryIdentityStore media_;
    std::size_t store_count_{0};
    std::size_t fail_store_at_{0};
    std::size_t crash_store_at_{0};
    bool fail_next_store_{false};
    bool fail_next_load_{false};
};

}  // namespace miezmerker::sim