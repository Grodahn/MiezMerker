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
    void string(const std::string& v, std::size_t maximum, bool opaque = false) {
        if (v.size() > maximum || (!opaque && v.find('\0') != std::string::npos)) throw std::runtime_error("field size");
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
    std::string string(std::size_t maximum, bool opaque = false) {
        const auto n = number(2);
        if (n > maximum || pos + n > end) throw std::runtime_error("field size");
        std::string out(bytes.begin() + pos, bytes.begin() + pos + n); pos += n;
        if (!opaque && out.find('\0') != std::string::npos) throw std::runtime_error("NUL field");
        return out;
    }
    void done() { if (pos != end) throw std::runtime_error("trailing state"); }
};
constexpr std::uint32_t identity_magic = 0x01494d4d, log_magic = 0x014f4d4d;
// Complete owner metadata uses the backend's character bounds in UTF-8 bytes.
constexpr std::size_t name_bytes = 255 * 4, contact_bytes = 500 * 4;
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
        candidate.organization_name = r.string(name_bytes);
        candidate.public_contact = r.string(contact_bytes);
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
        w.string(id.organization_id, 36); w.string(id.organization_name, name_bytes);
        w.string(id.public_contact, contact_bytes); w.string(id.claim_receipt, 4096);
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

namespace {
constexpr std::uint32_t evidence_magic = 0x01544d4d;
void write_boot(Writer& w, const BootEpochV1& b) {
    w.number(b.format_version, 2); w.array(b.node_id.bytes); w.array(b.incarnation.bytes);
    w.number(b.boot_counter, 4); w.number(b.reset_reason, 4);
    w.number(b.started_uptime_ms, 8); w.number(b.first_possible_sequence, 8);
}
BootEpochV1 read_boot(Reader& r) {
    BootEpochV1 b; b.format_version = static_cast<std::uint16_t>(r.number(2));
    r.array(b.node_id.bytes); r.array(b.incarnation.bytes);
    b.boot_counter = static_cast<std::uint32_t>(r.number(4)); b.reset_reason = static_cast<std::uint32_t>(r.number(4));
    b.started_uptime_ms = r.number(8); b.first_possible_sequence = r.number(8); return b;
}
void write_anchor(Writer& w, const TimeAnchorV1& a) {
    w.number(a.format_version, 2); w.array(a.anchor_id.bytes); w.array(a.node_id.bytes); w.array(a.incarnation.bytes);
    w.number(a.boot_counter, 4); w.number(a.uptime_ms, 8); w.number(static_cast<std::uint64_t>(a.utc_ms), 8);
    w.number(static_cast<unsigned>(a.source), 1); w.number(a.verified_security_time, 1);
    w.number(a.uncertainty_minus_ms, 8); w.number(a.uncertainty_plus_ms, 8); w.number(a.drift_ppm_bound, 4);
    w.string(a.proof_id_or_digest, 128); w.string(a.proof, 4096, true); w.string(a.acceptance, 512, true);
}
TimeAnchorV1 read_anchor(Reader& r) {
    TimeAnchorV1 a; a.format_version = static_cast<std::uint16_t>(r.number(2));
    r.array(a.anchor_id.bytes); r.array(a.node_id.bytes); r.array(a.incarnation.bytes);
    a.boot_counter = static_cast<std::uint32_t>(r.number(4)); a.uptime_ms = r.number(8);
    const auto utc = r.number(8);
    if (utc > static_cast<std::uint64_t>(std::numeric_limits<std::int64_t>::max())) throw std::runtime_error("anchor UTC");
    a.utc_ms = static_cast<std::int64_t>(utc); a.source = static_cast<AnchorSource>(r.number(1));
    const auto verified = r.number(1);
    if (verified > 1) throw std::runtime_error("anchor trust");
    a.verified_security_time = verified != 0;
    a.uncertainty_minus_ms = r.number(8); a.uncertainty_plus_ms = r.number(8);
    a.drift_ppm_bound = static_cast<std::uint32_t>(r.number(4));
    a.proof_id_or_digest = r.string(128); a.proof = r.string(4096, true); a.acceptance = r.string(512, true); return a;
}
bool same_boot(const BootEpochV1& b, const TimeAnchorV1& a) {
    return b.node_id == a.node_id && b.incarnation == a.incarnation && b.boot_counter == a.boot_counter;
}
bool valid_evidence(const std::vector<BootEpochV1>& boots, const std::vector<TimeAnchorV1>& anchors) {
    if (boots.size() > PersistentTimeEvidence::kMaxBoots || anchors.size() > PersistentTimeEvidence::kMaxAnchors) return false;
    for (std::size_t i = 0; i < boots.size(); ++i) {
        const auto& b = boots[i];
        if (!b.valid()) return false;
        if (i && (b.node_id != boots[0].node_id || b.incarnation != boots[0].incarnation
            || b.boot_counter <= boots[i-1].boot_counter || b.first_possible_sequence < boots[i-1].first_possible_sequence)) return false;
    }
    for (std::size_t i = 0; i < anchors.size(); ++i) {
        const auto& a = anchors[i];
        if (!a.valid() || std::none_of(boots.begin(), boots.end(), [&](const auto& b) {
            return same_boot(b, a) && a.uptime_ms >= b.started_uptime_ms;
        })) return false;
        for (std::size_t j = 0; j < i; ++j) if (a.anchor_id == anchors[j].anchor_id) return false;
    }
    return true;
}
}
bool PersistentTimeEvidence::open() {
    ready_ = false;
    Bytes bytes; const auto result = blob_.read(bytes);
    if (result == BlobResult::error) return false;
    if (result == BlobResult::missing) { boots_.clear(); anchors_.clear(); ready_ = true; return true; }
    if (bytes.size() > kMaxBytes) return false;
    try {
        Reader r(bytes); if (r.number(4) != evidence_magic) return false;
        const auto nb = r.number(2), na = r.number(2);
        if (nb > kMaxBoots || na > kMaxAnchors) return false;
        std::vector<BootEpochV1> boots; std::vector<TimeAnchorV1> anchors;
        for (std::uint64_t i = 0; i < nb; ++i) boots.push_back(read_boot(r));
        for (std::uint64_t i = 0; i < na; ++i) anchors.push_back(read_anchor(r));
        r.done(); if (!valid_evidence(boots, anchors)) return false;
        boots_ = std::move(boots); anchors_ = std::move(anchors); ready_ = true; return true;
    } catch (const std::exception&) { return false; }
}
PutResult PersistentTimeEvidence::commit(const std::vector<BootEpochV1>& boots, const std::vector<TimeAnchorV1>& anchors) {
    if (!ready_ || !valid_evidence(boots, anchors)) return PutResult::IO_ERROR;
    try {
        Writer w; w.number(evidence_magic, 4); w.number(boots.size(), 2); w.number(anchors.size(), 2);
        for (const auto& b : boots) write_boot(w, b);
        for (const auto& a : anchors) write_anchor(w, a);
        const auto bytes = w.finish(); if (bytes.size() > kMaxBytes) return PutResult::FULL;
        if (!blob_.replace(bytes)) { ready_ = false; return PutResult::IO_ERROR; }
        boots_ = boots; anchors_ = anchors; return PutResult::OK;
    } catch (const std::exception&) { return PutResult::IO_ERROR; }
}
PutResult PersistentTimeEvidence::append_boot(const BootEpochV1& b) {
    if (!ready_ || !b.valid()) return PutResult::IO_ERROR;
    for (const auto& old : boots_) {
        if (old.node_id == b.node_id && old.incarnation == b.incarnation && old.boot_counter == b.boot_counter)
            return old == b ? PutResult::OK : PutResult::IO_ERROR;
    }
    if (boots_.size() == kMaxBoots) return PutResult::FULL;
    auto boots = boots_; boots.push_back(b); return commit(boots, anchors_);
}
PutResult PersistentTimeEvidence::append_anchor(const TimeAnchorV1& a) {
    if (!ready_ || !a.valid()) return PutResult::IO_ERROR;
    for (const auto& old : anchors_) if (old.anchor_id == a.anchor_id) return old == a ? PutResult::OK : PutResult::IO_ERROR;
    if (anchors_.size() == kMaxAnchors) return PutResult::FULL;
    auto anchors = anchors_; anchors.push_back(a); return commit(boots_, anchors);
}
bool PersistentTimeEvidence::clear_for_factory_reset() { return commit({}, {}) == PutResult::OK; }
} // namespace miezmerker::esp32
