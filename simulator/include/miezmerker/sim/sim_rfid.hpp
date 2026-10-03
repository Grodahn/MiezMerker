#pragma once

// Simulated RFID reader (issue #22).
//
// The reader holds a queue of chip IDs to return on poll(). Empty queue or
// an explicitly queued nullopt means "no tag present".

#include <deque>
#include <optional>
#include <string>

#include "miezmerker/ports.hpp"

namespace miezmerker::sim {

class SimRfidReader final : public RfidReader {
public:
    void present(const std::string& chip_id) { queue_.push_back(chip_id); }
    void absent() { queue_.push_back(std::nullopt); }

    std::optional<std::string> poll() override {
        if (queue_.empty()) return std::nullopt;
        auto next = queue_.front();
        queue_.pop_front();
        return next;
    }

    bool empty() const { return queue_.empty(); }
    void clear() { queue_.clear(); }

private:
    std::deque<std::optional<std::string>> queue_;
};

}  // namespace miezmerker::sim