#include "miezmerker/core.hpp"
#include <iostream>
#include <string_view>

struct SimulatedClock final : miezmerker::Clock {
    std::uint64_t monotonic_ms() const override { return 0; }
};
struct SimulatedStorage final : miezmerker::Storage {
    bool available;
    explicit SimulatedStorage(bool value) : available(value) {}
    bool open() override { return available; }
};
int main(int argc, char** argv) {
    SimulatedClock clock;
    SimulatedStorage storage(!(argc > 1 && std::string_view(argv[1]) == "--storage-unavailable"));
    miezmerker::Core core(clock, storage);
    if (!core.initialize()) { std::cerr << "core storage unavailable\n"; return 1; }
    std::cout << "core ready at " << core.started_at_ms() << " ms\n";
}
