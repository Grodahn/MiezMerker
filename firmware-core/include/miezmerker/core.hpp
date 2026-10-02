#pragma once
#include "ports.hpp"

namespace miezmerker {
// Shared boot boundary. RFID capture, durable records and sync are future tickets.
class Core {
public:
    Core(Clock& clock, Storage& storage) : clock_(clock), storage_(storage) {}
    bool initialize();
    bool ready() const { return ready_; }
    std::uint64_t started_at_ms() const { return started_at_ms_; }
private:
    Clock& clock_;
    Storage& storage_;
    bool ready_{false};
    std::uint64_t started_at_ms_{0};
};
}
