#pragma once

// Common parsing utilities (issue #22).

#include <cstddef>
#include <cstdint>
#include <string_view>
#include <charconv>

namespace miezmerker::sim {

inline bool parse_uint64(std::string_view s, std::uint64_t& out) {
    const char* begin = s.data();
    const char* end = begin + s.size();
    auto [ptr, ec] = std::from_chars(begin, end, out);
    return ec == std::errc{} && ptr == end;
}

inline bool parse_size_t(std::string_view s, std::size_t& out) {
    const char* begin = s.data();
    const char* end = begin + s.size();
    auto [ptr, ec] = std::from_chars(begin, end, out);
    return ec == std::errc{} && ptr == end;
}

}  // namespace miezmerker::sim