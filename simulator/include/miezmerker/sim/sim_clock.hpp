#pragma once

// Simulated time and RTC hardware (issue #22).
//
// The simulator provides a controllable monotonic clock and a separate RTC
// model. The RTC models the hardware real-time clock: it holds a wall-clock
// value with trust status, survives reboot/factory-reset (it is a hardware
// component), and can be set, invalidated, or corrected by scenario events.

#include <cstdint>
#include <optional>

#include "miezmerker/ports.hpp"

namespace miezmerker::sim {

/// Simulated RTC hardware. Survives reboot and factory reset (it is a
/// hardware component, not node state). The scenario controls it explicitly:
/// set a trustworthy value, invalidate it, or correct it later.
class SimRtc {
public:
    SimRtc() = default;

    void set(ClockStatus status, std::optional<std::uint64_t> epoch_ms) {
        reading_.status = status;
        if (status == ClockStatus::UNKNOWN) {
            reading_.epoch_ms.reset();
        } else {
            reading_.epoch_ms = epoch_ms;
        }
    }

    void set_synced(std::uint64_t epoch_ms) {
        reading_.status = ClockStatus::SYNCED;
        reading_.epoch_ms = epoch_ms;
    }

    void set_rtc_only(std::uint64_t epoch_ms) {
        reading_.status = ClockStatus::RTC_ONLY;
        reading_.epoch_ms = epoch_ms;
    }

    void invalidate() {
        reading_.status = ClockStatus::UNKNOWN;
        reading_.epoch_ms.reset();
    }

    void correct(std::uint64_t epoch_ms) {
        reading_.status = ClockStatus::SYNCED;
        reading_.epoch_ms = epoch_ms;
    }

    WallClockReading reading() const { return reading_; }
    ClockStatus status() const { return reading_.status; }
    std::optional<std::uint64_t> epoch_ms() const { return reading_.epoch_ms; }

private:
    WallClockReading reading_{ClockStatus::UNKNOWN, std::nullopt};
};

/// Controllable clock for the simulator: monotonic time advances manually,
/// wall-clock value and trust status come from the attached SimRtc.
class SimClock final : public Clock {
public:
    explicit SimClock(SimRtc& rtc) : rtc_(rtc) {}

    std::uint64_t monotonic_ms() const override { return monotonic_ms_; }
    WallClockReading wall_clock() const override { return rtc_.reading(); }

    void set_monotonic_ms(std::uint64_t value) { monotonic_ms_ = value; }
    void advance_monotonic_ms(std::uint64_t delta) { monotonic_ms_ += delta; }

private:
    SimRtc& rtc_;
    std::uint64_t monotonic_ms_{0};
};

}  // namespace miezmerker::sim