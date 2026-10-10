#include "miezmerker/persistent_state.hpp"
#include "miezmerker/ble_codec.hpp"
#include <algorithm>
#include <limits>
#include <stdexcept>

namespace miezmerker::esp32 {
namespace {
std::uint32_t crc(const Bytes& data, std::size_t length) {
    std::uint32_t value = 0xffffffff;
    for (std::size_t i = 0; i < length; ++i) {
        value ^= data[i];
        for (int bit = 0; bit < 8; ++bit) value = (value >> 1) ^ (0xedb88320U & (0U - (value & 1)));
    }
    return ~value;
}
struct Writer {
    Bytes bytes;
    void number(std::uint64_t v, unsigned width) {
        for (unsigned i = 0; i < width; ++i) { bytes.push_back(static_cast<std::uint8_t>(v)); v >>= 8; }
    }
    template<class T> void array(const T& v) {
        for (auto b : v) bytes.push_back(static_cast<std::uint8_t>(b));
    }
    void string(const std::string& v, std::size_t maximum) {
        if (v.size() > maximum || v.find('\0') != std::string::npos) throw std::runtime_error("field size");
        number(v.size(), 2); array(v);
    }
    Bytes finish() { number(crc(bytes, bytes.size()), 4); return std::move(bytes); }
};
struct Reader {
    const Bytes& bytes;
    std::size_t pos{0}, end;
    explicit Reader(const Bytes& data) : bytes(data), end(data.size() >= 4 ? data.size() - 4 : 0) {
        if (data.size() < 8) throw std::runtime_error("short state");
        pos = end;
        const auto checksum = number(4, data.size());
        if (checksum != crc(data, end)) throw std::runtime_error("state checksum");
        pos = 0;
    }
    std::uint64_t number(unsigned width, std::size_t limit = 0) {
        if (pos + width > (limit ? limit : end)) throw std::runtime_error("short field");
        std::uint64_t out = 0;
        for (unsigned i = 0; i < width; ++i) out |= std::uint64_t(bytes[pos++]) << (8 * i);
        return out;
    }
    template<class T> void array(T& v) {
        for (auto& b : v) b = static_cast<std::remove_reference_t<decltype(b)>>(number(1));
    }
    std::string string(std::size_t maximum) {
        const auto n = number(2);
        if (n > maximum || pos + n > end) throw std::runtime_error("field size");
        std::string out(bytes.begin() + pos, bytes.begin() + pos + n); pos += n;
        if (out.find('\0') != std::string::npos) throw std::runtime_error("NUL field");
        return out;
    }
    void done() { if (pos != end) throw std::runtime_error("trailing state"); }
};
constexpr std::uint32_t identity_magic = 0x01494d4d, log_magic = 0x014f4d4d;
bool valid_identity(const NodeIdentity& id) {
    if ((std::to_integer<unsigned>(id.node_id[6]) & 0xf0) != 0x40
        || (std::to_integer<unsigned>(id.node_id[8]) & 0xc0) != 0x80
        || id.public_key[0] != std::byte{4}
        || std::all_of(id.private_key.begin(), id.private_key.end(), [](auto b) { return b == std::byte{0}; })) return false;
    if (!id.organization_id.empty() && (!NodeId::parse(id.organization_id) || id.claim_receipt.empty())) return false;
    if (id.organization_id.empty() && (!id.claim_receipt.empty() || !id.organization_name.empty() || !id.public_contact.empty())) return false;
    if (!id.observations) return true;
    const auto& o = *id.observations;
    return o.valid() && o.next_sequence - 1 == id.sequence
        && o.claim_state == (id.organization_id.empty() ? ClaimState::UNCLAIMED : ClaimState::CLAIMED)
        && std::equal(id.node_id.begin(), id.node_id.end(), o.node_id.bytes.begin(),
            [](auto a, auto b) { return static_cast<std::uint8_t>(a) == b; });
}
}
NodeIdentityLoadResult PersistentIdentity::load(NodeIdentity& out) {
    Bytes bytes;
    const auto result = blob_.read(bytes);
    if (result == BlobResult::missing) return NodeIdentityLoadResult::missing;
    if (result != BlobResult::loaded) return NodeIdentityLoadResult::error;
    try {
        Reader r(bytes);
        if (r.number(4) != identity_magic) return NodeIdentityLoadResult::error;
        NodeIdentity candidate;
        r.array(candidate.node_id); r.array(candidate.public_key); r.array(candidate.private_key);
        candidate.sequence = r.number(8);
        const auto attached = r.number(1);
        if (attached > 1) return NodeIdentityLoadResult::error;
        if (attached) {
            IdentityRecord o;
            r.array(o.node_id.bytes); r.array(o.incarnation.bytes);
            o.next_sequence = r.number(8); o.boot_counter = static_cast<std::uint32_t>(r.number(4));
            o.claim_state = static_cast<ClaimState>(r.number(1));
            const auto pending = r.number(1);
            if (pending > 1) return NodeIdentityLoadResult::error;
            o.reset_pending = pending; candidate.observations = o;
        }
        candidate.organization_id = r.string(36);
        candidate.organization_name = r.string(512);
        candidate.public_contact = r.string(512);
        candidate.claim_receipt = r.string(4096);
        r.done();
        if (!valid_identity(candidate)) return NodeIdentityLoadResult::error;
        out = std::move(candidate);
        return NodeIdentityLoadResult::loaded;
    } catch (const std::exception&) { return NodeIdentityLoadResult::error; }
}
bool PersistentIdentity::save(const NodeIdentity& id) {
    if (!valid_identity(id)) return false;
    try {
        Writer w;
        w.number(identity_magic, 4); w.array(id.node_id); w.array(id.public_key); w.array(id.private_key);
        w.number(id.sequence, 8); w.number(id.observations.has_value(), 1);
        if (id.observations) {
            const auto& o = *id.observations;
            w.array(o.node_id.bytes); w.array(o.incarnation.bytes); w.number(o.next_sequence, 8);
            w.number(o.boot_counter, 4); w.number(static_cast<unsigned>(o.claim_state), 1); w.number(o.reset_pending, 1);
        }
        w.string(id.organization_id, 36); w.string(id.organization_name, 512);
        w.string(id.public_contact, 512); w.string(id.claim_receipt, 4096);
        return blob_.replace(w.finish());
    } catch (const std::exception&) { return false; }
}
bool PersistentObservations::open() {
    ready_ = false;
    Bytes bytes;
    const auto result = blob_.read(bytes);
    if (result == BlobResult::error) return false;
    if (result == BlobResult::missing) {
        records_.clear(); high_ = ack_ = 0; ready_ = true; return true;
    }
    try {
        Reader r(bytes);
        if (r.number(4) != log_magic) return false;
        const auto high = r.number(8), ack = r.number(8), count = r.number(2);
        if (ack > high || count > kCapacity) return false;
        std::vector<RawObservation> records;
        std::uint64_t previous = 0;
        for (std::uint64_t i = 0; i < count; ++i) {
            std::size_t consumed = 0;
            const auto record = ble::decode_record(Bytes(bytes.begin() + r.pos, bytes.begin() + r.end), consumed);
            if (!record || consumed == 0 || record->sequence <= previous || record->sequence > high) return false;
            if (!records.empty() && (record->node_id != records.front().node_id || record->incarnation != records.front().incarnation)) return false;
            records.push_back(*record); previous = record->sequence; r.pos += consumed;
        }
        // Any high-watermark beyond the visible tail must already have been ACKed/compacted.
        if (high > std::max(previous, ack)) return false;
        r.done(); records_ = std::move(records); high_ = high; ack_ = ack; ready_ = true;
        return true;
    } catch (const std::exception&) { return false; }
}
bool PersistentObservations::commit(const std::vector<RawObservation>& records, std::uint64_t high, std::uint64_t ack) {
    if (!ready_) return false;
    Writer w;
    w.number(log_magic, 4); w.number(high, 8); w.number(ack, 8); w.number(records.size(), 2);
    for (const auto& record : records) w.array(ble::encode_record(record));
    if (!blob_.replace(w.finish())) { ready_ = false; return false; }
    records_ = records; high_ = high; ack_ = ack; return true;
}
PutResult PersistentObservations::append(const RawObservation& o) {
    if (!ready_ || !o.valid() || o.sequence <= high_ || (!records_.empty()
        && (o.node_id != records_.front().node_id || o.incarnation != records_.front().incarnation))) return PutResult::IO_ERROR;
    if (size() >= capacity()) return PutResult::FULL;
    auto records = records_; records.push_back(o);
    return commit(records, o.sequence, ack_) ? PutResult::OK : PutResult::IO_ERROR;
}
StoreStatus PersistentObservations::status() const {
    if (!ready_ || size() >= capacity()) return StoreStatus::FULL;
    return size() >= capacity() * 9 / 10 ? StoreStatus::NEARLY_FULL : StoreStatus::OK;
}
bool PersistentObservations::set_ack_watermark(std::uint64_t sequence) {
    if (!ready_ || sequence < ack_ || sequence > high_) return false;
    if (sequence == ack_) return true;
    return commit(records_, high_, sequence);
}
bool PersistentObservations::prune_acked() {
    if (!ready_) return false;
    auto records = records_;
    std::erase_if(records, [this](const auto& o) { return o.sequence <= ack_; });
    return records.size() == records_.size() || commit(records, high_, ack_);
}
bool PersistentObservations::clear() { return commit({}, 0, 0); }
} // namespace miezmerker::esp32
