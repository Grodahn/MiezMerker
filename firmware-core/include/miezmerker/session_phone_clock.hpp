#pragma once
#include "miezmerker/ports.hpp"
#include <limits>

namespace miezmerker {
// FIELD_ALPHA_PHONE_CLOCK only. Untrusted authorization-time candidate, never
// observation time or verified evidence. One sample per BLE connection; the
// owner must clear it on disconnect and construct a fresh instance every boot.
class SessionPhoneClock {
public:
    explicit SessionPhoneClock(Clock& clock) : clock_(clock) {}
    void disconnect() { sampled_ = false; invalid_ = false; utc_ms_ = 0; }
    bool sample(std::uint64_t utc_ms) {
        if (sampled_ || invalid_ || utc_ms == 0 ||
            utc_ms > static_cast<std::uint64_t>(std::numeric_limits<std::int64_t>::max())) return false;
        const auto now = clock_.monotonic_ms();
        if (!clock_.monotonic_valid()) { invalid_ = true; return false; }
        sampled_ = true; utc_ms_ = utc_ms; sampled_at_ = last_ = now;
        return true;
    }
    bool interval(std::int64_t& lower, std::int64_t& upper) const {
        lower = upper = 0;
        if (!sampled_ || invalid_) return false;
        const auto now = clock_.monotonic_ms();
        if (!clock_.monotonic_valid() || now < last_ || now < sampled_at_ ||
            now - sampled_at_ > static_cast<std::uint64_t>(std::numeric_limits<std::int64_t>::max()) - utc_ms_) {
            invalid_ = true; return false;
        }
        last_ = now;
        lower = upper = static_cast<std::int64_t>(utc_ms_ + (now - sampled_at_));
        return true;
    }
private:
    Clock& clock_;
    bool sampled_{false};
    mutable bool invalid_{false};
    std::uint64_t utc_ms_{0}, sampled_at_{0};
    mutable std::uint64_t last_{0};
};
}
