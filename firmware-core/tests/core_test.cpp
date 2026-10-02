#include "miezmerker/core.hpp"
#include <iostream>
struct Clock final : miezmerker::Clock {
    std::uint64_t monotonic_ms() const override { return 42; }
};
struct Storage final : miezmerker::Storage {
    bool available{true};
    bool open() override { return available; }
};
int main() {
    Clock clock;
    Storage storage;
    miezmerker::Core core(clock, storage);
    if (!core.initialize() || !core.ready() || core.started_at_ms() != 42) return 1;
    storage.available = false;
    if (core.initialize() || core.ready() || core.started_at_ms() != 0) return 2;
    std::cout << "Core initializes through ports and reports unavailable storage\n";
}
