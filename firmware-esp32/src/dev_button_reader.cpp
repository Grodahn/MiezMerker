#include "miezmerker/dev_button_reader.hpp"
#include <array>
namespace miezmerker::esp32 {
std::optional<std::string> DevButtonReader::poll() {
    const auto now = clock_.monotonic_ms();
    const bool high = input_.high();
    if (!initialized_ || high != candidate_high_) {
        initialized_ = true;
        candidate_high_ = high;
        changed_at_ = now;
        return std::nullopt;
    }
    if (now - changed_at_ < 50) return std::nullopt;
    if (high) {
        armed_ = true; // Also requires a stable release after boot while held.
        return std::nullopt;
    }
    if (!armed_) return std::nullopt;
    armed_ = false; // Consume the press even if Core rejects this attempt.
    std::array<std::uint8_t, 1> byte{};
    // Rejection sampling avoids modulo bias: 255 is not divisible into five.
    do { random_.fill_random(byte); } while (byte[0] == 255);
    constexpr std::array chips{"1", "2", "3", "4", "5"};
    selected_ = chips[byte[0] % chips.size()];
    return selected_;
}
} // namespace miezmerker::esp32
