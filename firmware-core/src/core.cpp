#include "miezmerker/core.hpp"
namespace miezmerker {
bool Core::initialize() {
    ready_ = storage_.open();
    started_at_ms_ = ready_ ? clock_.monotonic_ms() : 0;
    return ready_;
}
}
