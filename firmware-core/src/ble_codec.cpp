#include "miezmerker/ble_codec.hpp"

#include <cstring>

namespace miezmerker::ble {

namespace {

void put_u16(std::vector<std::uint8_t>& out, std::uint16_t v) {
    out.push_back(static_cast<std::uint8_t>(v & 0xFF));
    out.push_back(static_cast<std::uint8_t>((v >> 8) & 0xFF));
}
void put_u32(std::vector<std::uint8_t>& out, std::uint32_t v) {
    for (int i = 0; i < 4; ++i) out.push_back(static_cast<std::uint8_t>((v >> (8 * i)) & 0xFF));
}
void put_u64(std::vector<std::uint8_t>& out, std::uint64_t v) {
    for (int i = 0; i < 8; ++i) out.push_back(static_cast<std::uint8_t>((v >> (8 * i)) & 0xFF));
}
void put_bytes(std::vector<std::uint8_t>& out, const std::uint8_t* data, std::size_t n) {
    out.insert(out.end(), data, data + n);
}
void put_str16(std::vector<std::uint8_t>& out, const std::string& s) {
    put_u16(out, static_cast<std::uint16_t>(s.size()));
    out.insert(out.end(), s.begin(), s.end());
}

struct Cursor {
    const std::uint8_t* p;
    std::size_t n;
    bool take_u8(std::uint8_t& v) {
        if (n < 1) return false;
        v = *p;
        ++p;
        --n;
        return true;
    }
    bool take_u16(std::uint16_t& v) {
        if (n < 2) return false;
        v = static_cast<std::uint16_t>(p[0] | (p[1] << 8));
        p += 2;
        n -= 2;
        return true;
    }
    bool take_u32(std::uint32_t& v) {
        if (n < 4) return false;
        v = 0;
        for (int i = 0; i < 4; ++i) v |= static_cast<std::uint32_t>(p[i]) << (8 * i);
        p += 4;
        n -= 4;
        return true;
    }
    bool take_u64(std::uint64_t& v) {
        if (n < 8) return false;
        v = 0;
        for (int i = 0; i < 8; ++i) v |= static_cast<std::uint64_t>(p[i]) << (8 * i);
        p += 8;
        n -= 8;
        return true;
    }
    bool take_bytes(std::uint8_t* out, std::size_t count) {
        if (n < count) return false;
        std::memcpy(out, p, count);
        p += count;
        n -= count;
        return true;
    }
    bool take_str16(std::string& s, std::size_t max = 2048) {
        std::uint16_t len = 0;
        if (!take_u16(len)) return false;
        if (len > max || n < len) return false;
        s.assign(reinterpret_cast<const char*>(p), len);
        p += len;
        n -= len;
        return true;
    }
    bool empty() const { return n == 0; }
};

bool encode_uuid_check(const NodeId& id, const IncarnationId& inc) {
    return id.is_set() && inc.is_set();
}

}  // namespace

std::vector<std::uint8_t> encode_frame(const Frame& frame) {
    std::vector<std::uint8_t> out;
    out.reserve(4 + frame.payload.size());
    out.push_back(frame.version);
    out.push_back(static_cast<std::uint8_t>(frame.opcode));
    put_u16(out, static_cast<std::uint16_t>(frame.payload.size()));
    out.insert(out.end(), frame.payload.begin(), frame.payload.end());
    return out;
}

std::optional<Frame> decode_frame(const std::vector<std::uint8_t>& bytes) {
    if (bytes.size() < 4) return std::nullopt;
    Cursor c{bytes.data(), bytes.size()};
    std::uint8_t ver = 0, op = 0;
    std::uint16_t len = 0;
    if (!c.take_u8(ver) || !c.take_u8(op) || !c.take_u16(len)) return std::nullopt;
    if (c.n != len) return std::nullopt;
    Frame f;
    f.version = ver;
    f.opcode = static_cast<Opcode>(op);
    f.payload.assign(c.p, c.p + c.n);
    return f;
}

std::vector<std::uint8_t> encode_hello_request(std::uint8_t client_ver, std::uint16_t caps) {
    std::vector<std::uint8_t> out;
    out.push_back(client_ver);
    put_u16(out, caps);
    return out;
}
std::optional<HelloRequest> decode_hello_request(const std::vector<std::uint8_t>& payload) {
    Cursor c{payload.data(), payload.size()};
    HelloRequest m;
    if (!c.take_u8(m.client_ver) || !c.take_u16(m.caps) || !c.empty()) return std::nullopt;
    return m;
}

std::vector<std::uint8_t> encode_hello_public(const HelloPublic& msg) {
    std::vector<std::uint8_t> out;
    out.push_back(msg.server_ver);
    put_u16(out, msg.caps);
    put_bytes(out, msg.node_id.bytes.data(), 16);
    put_bytes(out, msg.incarnation.bytes.data(), 16);
    if (msg.firmware_version.size() > 64) return {};
    out.push_back(static_cast<std::uint8_t>(msg.firmware_version.size()));
    out.insert(out.end(), msg.firmware_version.begin(), msg.firmware_version.end());
    out.push_back(static_cast<std::uint8_t>(msg.claim_state));
    out.push_back(static_cast<std::uint8_t>(msg.clock_status));
    return out;
}
std::optional<HelloPublic> decode_hello_public(const std::vector<std::uint8_t>& payload) {
    Cursor c{payload.data(), payload.size()};
    HelloPublic m;
    std::uint8_t claim = 0, clock = 0, fw_len = 0;
    if (!c.take_u8(m.server_ver) || !c.take_u16(m.caps)) return std::nullopt;
    if (!c.take_bytes(m.node_id.bytes.data(), 16)) return std::nullopt;
    if (!c.take_bytes(m.incarnation.bytes.data(), 16)) return std::nullopt;
    if (!c.take_u8(fw_len)) return std::nullopt;
    if (c.n < fw_len) return std::nullopt;
    m.firmware_version.assign(reinterpret_cast<const char*>(c.p), fw_len);
    c.p += fw_len;
    c.n -= fw_len;
    if (!c.take_u8(claim) || !c.take_u8(clock) || !c.empty()) return std::nullopt;
    if (claim > 1 || clock > 2) return std::nullopt;
    if (!encode_uuid_check(m.node_id, m.incarnation)) return std::nullopt;
    m.claim_state = static_cast<ClaimState>(claim);
    m.clock_status = static_cast<ClockStatus>(clock);
    return m;
}

std::vector<std::uint8_t> encode_owner_response(const OwnerInfo& msg) {
    std::vector<std::uint8_t> out;
    put_bytes(out, msg.node_id.bytes.data(), 16);
    out.push_back(static_cast<std::uint8_t>(msg.claim_state));
    put_str16(out, msg.organization_id);
    put_str16(out, msg.organization_slug);
    put_str16(out, msg.organization_name);
    put_str16(out, msg.public_contact);
    return out;
}
std::optional<OwnerInfo> decode_owner_response(const std::vector<std::uint8_t>& payload) {
    Cursor c{payload.data(), payload.size()};
    OwnerInfo m;
    std::uint8_t claim = 0;
    if (!c.take_bytes(m.node_id.bytes.data(), 16)) return std::nullopt;
    if (!c.take_u8(claim)) return std::nullopt;
    if (claim > 1) return std::nullopt;
    m.claim_state = static_cast<ClaimState>(claim);
    if (!c.take_str16(m.organization_id, 128) || !c.take_str16(m.organization_slug, 128) ||
        !c.take_str16(m.organization_name, 128) || !c.take_str16(m.public_contact, 256) ||
        !c.empty())
        return std::nullopt;
    if (!m.node_id.is_set()) return std::nullopt;
    return m;
}

std::vector<std::uint8_t> encode_challenge_response(const std::array<std::uint8_t, 32>& nonce) {
    return std::vector<std::uint8_t>(nonce.begin(), nonce.end());
}
std::optional<std::array<std::uint8_t, 32>> decode_challenge_response(
    const std::vector<std::uint8_t>& payload) {
    if (payload.size() != 32) return std::nullopt;
    std::array<std::uint8_t, 32> out{};
    std::memcpy(out.data(), payload.data(), 32);
    return out;
}

std::vector<std::uint8_t> encode_auth_request(const AuthRequest& msg) {
    if (msg.credential.size() > 2048) return {};
    std::vector<std::uint8_t> out;
    put_str16(out, msg.credential);
    out.insert(out.end(), msg.proof.begin(), msg.proof.end());
    return out;
}
std::optional<AuthRequest> decode_auth_request(const std::vector<std::uint8_t>& payload) {
    Cursor c{payload.data(), payload.size()};
    AuthRequest m;
    if (!c.take_str16(m.credential, 2048)) return std::nullopt;
    if (c.n != 64) return std::nullopt;
    std::memcpy(m.proof.data(), c.p, 64);
    if (m.credential.empty()) return std::nullopt;
    return m;
}

std::vector<std::uint8_t> encode_auth_response(const AuthResponse& msg) {
    std::vector<std::uint8_t> out;
    out.push_back(msg.ok ? 1 : 0);
    put_u64(out, msg.expires_s);
    out.push_back(static_cast<std::uint8_t>(msg.error));
    return out;
}
std::optional<AuthResponse> decode_auth_response(const std::vector<std::uint8_t>& payload) {
    Cursor c{payload.data(), payload.size()};
    AuthResponse m;
    std::uint8_t ok = 0, err = 0;
    if (!c.take_u8(ok) || !c.take_u64(m.expires_s) || !c.take_u8(err) || !c.empty())
        return std::nullopt;
    if (ok > 1 || err > 15) return std::nullopt;
    m.ok = ok == 1;
    m.error = static_cast<SyncError>(err);
    return m;
}

std::vector<std::uint8_t> encode_node_proof_request(const std::array<std::uint8_t, 32>& nonce) {
    return std::vector<std::uint8_t>(nonce.begin(), nonce.end());
}
std::optional<std::array<std::uint8_t, 32>> decode_node_proof_request(
    const std::vector<std::uint8_t>& payload) {
    if (payload.size() != 32) return std::nullopt;
    std::array<std::uint8_t, 32> out{};
    std::memcpy(out.data(), payload.data(), 32);
    return out;
}
std::vector<std::uint8_t> encode_node_proof_response(const std::array<std::uint8_t, 64>& sig) {
    return std::vector<std::uint8_t>(sig.begin(), sig.end());
}
std::optional<std::array<std::uint8_t, 64>> decode_node_proof_response(
    const std::vector<std::uint8_t>& payload) {
    if (payload.size() != 64) return std::nullopt;
    std::array<std::uint8_t, 64> out{};
    std::memcpy(out.data(), payload.data(), 64);
    return out;
}

std::vector<std::uint8_t> encode_record(const RawObservation& obs) {
    if (!obs.valid()) return {};
    if (obs.chip_id.value.size() > ChipId::kMaxLength) return {};
    std::vector<std::uint8_t> out;
    put_bytes(out, obs.node_id.bytes.data(), 16);
    put_bytes(out, obs.incarnation.bytes.data(), 16);
    put_u64(out, obs.sequence);
    put_u16(out, static_cast<std::uint16_t>(obs.chip_id.value.size()));
    out.insert(out.end(), obs.chip_id.value.begin(), obs.chip_id.value.end());
    out.push_back(static_cast<std::uint8_t>(obs.clock_status));
    put_u64(out, obs.observed_at_epoch_ms.value_or(0));
    put_u64(out, obs.monotonic_ms);
    put_u32(out, obs.boot_counter);
    return out;
}
std::optional<RawObservation> decode_record(const std::vector<std::uint8_t>& bytes,
                                             std::size_t& consumed) {
    if (consumed > bytes.size()) return std::nullopt;
    Cursor c{bytes.data() + consumed, bytes.size() - consumed};
    RawObservation obs;
    std::uint16_t chip_len = 0;
    std::uint8_t clock = 0;
    std::uint64_t epoch = 0;
    std::uint32_t boot = 0;
    if (!c.take_bytes(obs.node_id.bytes.data(), 16)) return std::nullopt;
    if (!c.take_bytes(obs.incarnation.bytes.data(), 16)) return std::nullopt;
    if (!c.take_u64(obs.sequence)) return std::nullopt;
    if (!c.take_u16(chip_len)) return std::nullopt;
    if (chip_len == 0 || chip_len > ChipId::kMaxLength || c.n < chip_len) return std::nullopt;
    obs.chip_id.value.assign(reinterpret_cast<const char*>(c.p), chip_len);
    c.p += chip_len;
    c.n -= chip_len;
    if (!c.take_u8(clock)) return std::nullopt;
    if (clock > 2) return std::nullopt;
    obs.clock_status = static_cast<ClockStatus>(clock);
    if (!c.take_u64(epoch)) return std::nullopt;
    if (!c.take_u64(obs.monotonic_ms)) return std::nullopt;
    if (!c.take_u32(boot)) return std::nullopt;
    obs.boot_counter = boot;
    if (obs.clock_status == ClockStatus::UNKNOWN) {
        if (epoch != 0) return std::nullopt;
        obs.observed_at_epoch_ms.reset();
    } else {
        if (epoch == 0) return std::nullopt;
        obs.observed_at_epoch_ms = epoch;
    }
    if (!obs.valid()) return std::nullopt;
    consumed = bytes.size() - c.n;
    return obs;
}

std::vector<std::uint8_t> encode_batch_request(const BatchRequest& msg) {
    std::vector<std::uint8_t> out;
    put_u64(out, msg.from_sequence);
    put_u16(out, msg.max_records);
    return out;
}
std::optional<BatchRequest> decode_batch_request(const std::vector<std::uint8_t>& payload) {
    Cursor c{payload.data(), payload.size()};
    BatchRequest m;
    if (!c.take_u64(m.from_sequence) || !c.take_u16(m.max_records) || !c.empty())
        return std::nullopt;
    if (m.from_sequence == kInvalidSequence || m.max_records == 0 || m.max_records > 64)
        return std::nullopt;
    return m;
}

std::vector<std::uint8_t> encode_batch_response(const BatchResponse& msg) {
    std::vector<std::uint8_t> out;
    put_u64(out, msg.from_sequence);
    put_u16(out, static_cast<std::uint16_t>(msg.records.size()));
    out.push_back(msg.more ? 1 : 0);
    put_u64(out, msg.next_cursor);
    for (const auto& r : msg.records) {
        auto enc = encode_record(r);
        if (enc.empty()) return {};
        out.insert(out.end(), enc.begin(), enc.end());
    }
    return out;
}
std::optional<BatchResponse> decode_batch_response(const std::vector<std::uint8_t>& payload) {
    Cursor c{payload.data(), payload.size()};
    BatchResponse m;
    std::uint16_t count = 0;
    std::uint8_t more = 0;
    if (!c.take_u64(m.from_sequence) || !c.take_u16(count) || !c.take_u8(more) ||
        !c.take_u64(m.next_cursor))
        return std::nullopt;
    if (more > 1 || count > 64) return std::nullopt;
    m.more = more == 1;
    // Remaining bytes are concatenated records.
    std::vector<std::uint8_t> rest(c.p, c.p + c.n);
    std::size_t off = 0;
    for (std::uint16_t i = 0; i < count; ++i) {
        auto rec = decode_record(rest, off);
        if (!rec) return std::nullopt;
        m.records.push_back(*rec);
    }
    if (off != rest.size()) return std::nullopt;
    return m;
}

std::vector<std::uint8_t> encode_ack_request(std::uint64_t watermark) {
    std::vector<std::uint8_t> out;
    put_u64(out, watermark);
    return out;
}
std::optional<std::uint64_t> decode_ack_request(const std::vector<std::uint8_t>& payload) {
    if (payload.size() != 8) return std::nullopt;
    Cursor c{payload.data(), payload.size()};
    std::uint64_t w = 0;
    if (!c.take_u64(w) || !c.empty()) return std::nullopt;
    return w;
}
std::vector<std::uint8_t> encode_ack_response(const AckResponse& msg) {
    std::vector<std::uint8_t> out;
    put_u64(out, msg.new_watermark);
    out.push_back(static_cast<std::uint8_t>(msg.error));
    return out;
}
std::optional<AckResponse> decode_ack_response(const std::vector<std::uint8_t>& payload) {
    Cursor c{payload.data(), payload.size()};
    AckResponse m;
    std::uint8_t err = 0;
    if (!c.take_u64(m.new_watermark) || !c.take_u8(err) || !c.empty()) return std::nullopt;
    if (err > 15) return std::nullopt;
    m.error = static_cast<SyncError>(err);
    return m;
}

std::vector<std::uint8_t> encode_time_request(std::uint64_t epoch_ms) {
    std::vector<std::uint8_t> out;
    put_u64(out, epoch_ms);
    return out;
}
std::optional<std::uint64_t> decode_time_request(const std::vector<std::uint8_t>& payload) {
    if (payload.size() != 8) return std::nullopt;
    Cursor c{payload.data(), payload.size()};
    std::uint64_t v = 0;
    if (!c.take_u64(v) || !c.empty()) return std::nullopt;
    return v;
}
std::vector<std::uint8_t> encode_time_response(const TimeResponse& msg) {
    std::vector<std::uint8_t> out;
    out.push_back(msg.ok ? 1 : 0);
    out.push_back(static_cast<std::uint8_t>(msg.error));
    put_u64(out, msg.applied_epoch_ms);
    return out;
}
std::optional<TimeResponse> decode_time_response(const std::vector<std::uint8_t>& payload) {
    Cursor c{payload.data(), payload.size()};
    TimeResponse m;
    std::uint8_t ok = 0, err = 0;
    if (!c.take_u8(ok) || !c.take_u8(err) || !c.take_u64(m.applied_epoch_ms) || !c.empty())
        return std::nullopt;
    if (ok > 1 || err > 15) return std::nullopt;
    m.ok = ok == 1;
    m.error = static_cast<SyncError>(err);
    return m;
}

std::vector<std::uint8_t> encode_status_response(const StatusResponse& msg) {
    std::vector<std::uint8_t> out;
    put_u32(out, msg.pending);
    put_u64(out, msg.ack_watermark);
    out.push_back(static_cast<std::uint8_t>(msg.store_status));
    out.push_back(static_cast<std::uint8_t>(msg.clock_status));
    put_u64(out, msg.epoch_ms);
    put_u64(out, msg.next_sequence);
    return out;
}
std::optional<StatusResponse> decode_status_response(const std::vector<std::uint8_t>& payload) {
    Cursor c{payload.data(), payload.size()};
    StatusResponse m;
    std::uint8_t store = 0, clock = 0;
    if (!c.take_u32(m.pending) || !c.take_u64(m.ack_watermark) || !c.take_u8(store) ||
        !c.take_u8(clock) || !c.take_u64(m.epoch_ms) || !c.take_u64(m.next_sequence) ||
        !c.empty())
        return std::nullopt;
    if (store > 2 || clock > 2) return std::nullopt;
    m.store_status = static_cast<StoreStatus>(store);
    m.clock_status = static_cast<ClockStatus>(clock);
    if (m.clock_status == ClockStatus::UNKNOWN && m.epoch_ms != 0) return std::nullopt;
    if (m.clock_status != ClockStatus::UNKNOWN && m.epoch_ms == 0) return std::nullopt;
    if (m.next_sequence == 0) return std::nullopt;
    return m;
}

std::vector<std::uint8_t> encode_compact_response(const CompactResponse& msg) {
    std::vector<std::uint8_t> out;
    put_u32(out, msg.freed);
    put_u32(out, msg.remaining);
    put_u64(out, msg.ack_watermark);
    out.push_back(static_cast<std::uint8_t>(msg.error));
    return out;
}
std::optional<CompactResponse> decode_compact_response(const std::vector<std::uint8_t>& payload) {
    Cursor c{payload.data(), payload.size()};
    CompactResponse m;
    std::uint8_t err = 0;
    if (!c.take_u32(m.freed) || !c.take_u32(m.remaining) || !c.take_u64(m.ack_watermark) ||
        !c.take_u8(err) || !c.empty())
        return std::nullopt;
    if (err > 15) return std::nullopt;
    m.error = static_cast<SyncError>(err);
    return m;
}

std::vector<std::uint8_t> encode_error_payload(const ErrorMsg& msg) {
    std::vector<std::uint8_t> out;
    out.push_back(static_cast<std::uint8_t>(msg.code));
    put_str16(out, msg.message);
    return out;
}
std::optional<ErrorMsg> decode_error_payload(const std::vector<std::uint8_t>& payload) {
    Cursor c{payload.data(), payload.size()};
    ErrorMsg m;
    std::uint8_t code = 0;
    if (!c.take_u8(code) || !c.take_str16(m.message, 256) || !c.empty()) return std::nullopt;
    if (code > 15) return std::nullopt;
    m.code = static_cast<SyncError>(code);
    return m;
}

std::vector<std::uint8_t> encode_advertisement(const Advertisement& adv) {
    std::vector<std::uint8_t> out;
    out.push_back(adv.protocol_version);
    std::uint8_t flags = 0;
    if (adv.claimed) flags |= 0x01;
    if (adv.claim_mode) flags |= 0x02;
    out.push_back(flags);
    put_u16(out, 0);
    return out;
}
std::optional<Advertisement> decode_advertisement(const std::vector<std::uint8_t>& bytes) {
    if (bytes.size() != 4) return std::nullopt;
    Advertisement adv;
    adv.protocol_version = bytes[0];
    const std::uint8_t flags = bytes[1];
    if ((flags & 0xFC) != 0) return std::nullopt;
    if (bytes[2] != 0 || bytes[3] != 0) return std::nullopt;
    adv.claimed = (flags & 0x01) != 0;
    adv.claim_mode = (flags & 0x02) != 0;
    return adv;
}

std::vector<std::uint8_t> encode_claim_advertisement(const ClaimAdvertisementPayload& msg) {
    std::vector<std::uint8_t> out;
    put_bytes(out, msg.node_id.bytes.data(), 16);
    put_bytes(out, msg.public_key.data(), 65);
    put_u64(out, msg.timestamp_ms);
    put_bytes(out, msg.signature.data(), 64);
    return out;
}
std::optional<ClaimAdvertisementPayload> decode_claim_advertisement(const std::vector<std::uint8_t>& payload) {
    Cursor c{payload.data(), payload.size()};
    ClaimAdvertisementPayload msg;
    if (!c.take_bytes(msg.node_id.bytes.data(), 16) || !c.take_bytes(msg.public_key.data(), 65) ||
        !c.take_u64(msg.timestamp_ms) || !c.take_bytes(msg.signature.data(), 64) || !c.empty() ||
        !msg.node_id.is_set() || msg.public_key[0] != 4 || msg.timestamp_ms == 0) return std::nullopt;
    return msg;
}
std::vector<std::uint8_t> encode_claim_receipt(const std::string& receipt) {
    std::vector<std::uint8_t> out;
    if (receipt.empty() || receipt.size() > 4096) return out;
    put_str16(out, receipt);
    return out;
}
std::optional<std::string> decode_claim_receipt(const std::vector<std::uint8_t>& payload) {
    Cursor c{payload.data(), payload.size()};
    std::string receipt;
    if (!c.take_str16(receipt, 4096) || !c.empty() || receipt.empty()) return std::nullopt;
    return receipt;
}

}  // namespace miezmerker::ble
