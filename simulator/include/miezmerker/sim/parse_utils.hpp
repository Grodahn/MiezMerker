#pragma once

// Common parsing utilities (issue #22).

#include <cstddef>
#include <cstdint>
#include <string_view>
#include <charconv>

namespace miezmerker::sim {

inline bool parse_uint64(std::string_view s, std::uint64_t& out) {
    if (s.empty()) return false;
    // Accept decimal ("42") and hex ("0xA5..."/"0X...") for seeds/epochs.
    // std::from_chars does not consume the 0x prefix, so strip it first.
    if (s.size() > 2 && s[0] == '0' && (s[1] == 'x' || s[1] == 'X')) {
        std::string_view hex = s.substr(2);
        if (hex.empty()) return false;
        const char* begin = hex.data();
        const char* end = begin + hex.size();
        auto [ptr, ec] = std::from_chars(begin, end, out, 16);
        return ec == std::errc{} && ptr == end;
    }
    const char* begin = s.data();
    const char* end = begin + s.size();
    auto [ptr, ec] = std::from_chars(begin, end, out);
    return ec == std::errc{} && ptr == end;
}

inline bool parse_size_t(std::string_view s, std::size_t& out) {
    if (s.empty()) return false;
    if (s.size() > 2 && s[0] == '0' && (s[1] == 'x' || s[1] == 'X')) {
        std::string_view hex = s.substr(2);
        if (hex.empty()) return false;
        const char* begin = hex.data();
        const char* end = begin + hex.size();
        auto [ptr, ec] = std::from_chars(begin, end, out, 16);
        return ec == std::errc{} && ptr == end;
    }
    const char* begin = s.data();
    const char* end = begin + s.size();
    auto [ptr, ec] = std::from_chars(begin, end, out);
    return ec == std::errc{} && ptr == end;
}

}  // namespace miezmerker::sim