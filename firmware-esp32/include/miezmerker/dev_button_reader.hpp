#pragma once
#if !defined(MM_DEV_GPIO_RFID) || MM_DEV_GPIO_RFID != 1
#error "Development button reader is unavailable in production"
#endif
#include "miezmerker/ports.hpp"

namespace miezmerker::esp32 {
// Electrical sampling only; storage and capture remain in Core::poll_reader.
struct ButtonInput {
    virtual ~ButtonInput() = default;
    virtual bool high() const = 0;
};
class DevButtonReader final : public RfidReader {
public:
    DevButtonReader(ButtonInput& input, Clock& clock, RandomSource& random)
        : input_(input), clock_(clock), random_(random) {}
    std::optional<std::string> poll() override;
private:
    ButtonInput& input_;
    Clock& clock_;
    RandomSource& random_;
    bool initialized_{false}, candidate_high_{true}, armed_{false};
    std::uint64_t changed_at_{0};
};
} // namespace miezmerker::esp32
