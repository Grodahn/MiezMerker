#pragma once
#include "miezmerker/node_identity.hpp"

namespace miezmerker::esp32 {
using Bytes = std::vector<std::uint8_t>;
enum class BlobResult { missing, loaded, error };
// Board-local atomic blob port: NVS in production, fault injection in host tests.
// A failed write may have committed; callers latch failure until reboot/reload.
class AtomicBlob {
public:
    virtual ~AtomicBlob() = default;
    virtual BlobResult read(Bytes& out) = 0;
    virtual bool replace(const Bytes& bytes) = 0;
};
class PersistentIdentity final : public NodeIdentityStore {
public:
    explicit PersistentIdentity(AtomicBlob& blob) : blob_(blob) {}
    NodeIdentityLoadResult load(NodeIdentity& out) override;
    bool save(const NodeIdentity& identity) override;
    bool clear() override { return false; } // Only Core's journalled reset rotates keys.
private:
    AtomicBlob& blob_;
};
class PersistentObservations final : public ObservationStore, public Storage {
public:
    static constexpr std::size_t kCapacity = 128;
    explicit PersistentObservations(AtomicBlob& blob) : blob_(blob) {}
    bool open() override;
    PutResult append(const RawObservation& observation) override;
    std::vector<RawObservation> load_all() override { return records_; }
    std::size_t size() const override { return records_.size(); }
    std::size_t capacity() const override { return kCapacity; }
    StoreStatus status() const override;
    std::uint64_t ack_watermark() const override { return ack_; }
    bool set_ack_watermark(std::uint64_t sequence) override;
    bool prune_acked() override;
    bool clear() override;
    bool healthy() const { return ready_; }
private:
    bool commit(const std::vector<RawObservation>& records, std::uint64_t high, std::uint64_t ack);
    AtomicBlob& blob_;
    std::vector<RawObservation> records_;
    std::uint64_t high_{0}, ack_{0};
    bool ready_{false};
};
} // namespace miezmerker::esp32
