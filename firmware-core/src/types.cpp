#include "miezmerker/types.hpp"

namespace miezmerker {
namespace {

constexpr char kHex[] = "0123456789abcdef";

int hex_value(char c) {
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    if (c >= 'A' && c <= 'F') return c - 'A' + 10;
    return -1;
}

template <std::size_t N>
std::string format_uuid(const std::array<std::uint8_t, N>& bytes) {
    static_assert(N == 16, "UUID is 128-bit");
    std::string out;
    out.reserve(36);
    for (std::size_t i = 0; i < 16; ++i) {
        if (i == 4 || i == 6 || i == 8 || i == 10) out.push_back('-');
        out.push_back(kHex[(bytes[i] >> 4) & 0xF]);
        out.push_back(kHex[bytes[i] & 0xF]);
    }
    return out;
}

template <typename T>
std::optional<T> parse_uuid(std::string_view text) {
    if (text.size() != 36u) return std::nullopt;
    if (text[8] != '-' || text[13] != '-' || text[18] != '-' || text[23] != '-') return std::nullopt;
    T id{};
    std::size_t out = 0;
    for (std::size_t i = 0; i < 36u;) {
        if (text[i] == '-') {
            ++i;
            continue;
        }
        if (i + 1 >= 36u) return std::nullopt;
        const int hi = hex_value(text[i]);
        const int lo = hex_value(text[i + 1]);
        if (hi < 0 || lo < 0) return std::nullopt;
        if (out >= 16u) return std::nullopt;
        id.bytes[out++] = static_cast<std::uint8_t>((hi << 4) | lo);
        i += 2;
    }
    if (out != 16u) return std::nullopt;
    return id;
}

}  // namespace

std::string NodeId::to_string() const { return format_uuid(bytes); }

std::optional<NodeId> NodeId::parse(std::string_view text) { return parse_uuid<NodeId>(text); }

bool NodeId::is_set() const {
    for (const auto b : bytes) {
        if (b != 0) return true;
    }
    return false;
}

std::string IncarnationId::to_string() const { return format_uuid(bytes); }

std::optional<IncarnationId> IncarnationId::parse(std::string_view text) {
    return parse_uuid<IncarnationId>(text);
}

bool IncarnationId::is_set() const {
    for (const auto b : bytes) {
        if (b != 0) return true;
    }
    return false;
}

bool RawObservation::valid() const {
    if (!node_id.is_set() || !incarnation.is_set()) return false;
    if (sequence == kInvalidSequence) return false;
    if (!chip_id.valid()) return false;
    if (clock_status == ClockStatus::UNKNOWN) {
        if (observed_at_epoch_ms.has_value()) return false;
    } else if (clock_status == ClockStatus::SYNCED || clock_status == ClockStatus::RTC_ONLY) {
        if (!observed_at_epoch_ms.has_value() || *observed_at_epoch_ms == 0) return false;
    } else {
        return false;
    }
    return true;
}

bool IdentityRecord::valid() const {
    if (!node_id.is_set() || !incarnation.is_set()) return false;
    if (next_sequence == kInvalidSequence) return false;
    if (claim_state != ClaimState::UNCLAIMED && claim_state != ClaimState::CLAIMED) return false;
    if (reset_pending && (next_sequence != kFirstSequence || boot_counter != 1 ||
                          claim_state != ClaimState::UNCLAIMED)) return false;
    return true;
}

}  // namespace miezmerker
