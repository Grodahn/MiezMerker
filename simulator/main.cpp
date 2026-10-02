#include <cstdint>
#include <iostream>
#include <span>
#include <string_view>

#include "miezmerker/core.hpp"
#include "miezmerker/memory_stores.hpp"
#include "miezmerker/protocol_view.hpp"

namespace {

struct SimulatedClock final : miezmerker::Clock {
    std::uint64_t monotonic_ms() const override { return 0; }
    miezmerker::WallClockReading wall_clock() const override {
        return miezmerker::WallClockReading{miezmerker::ClockStatus::RTC_ONLY, 1700000000000ULL};
    }
};

struct SimulatedStorage final : miezmerker::Storage {
    bool available;
    explicit SimulatedStorage(bool value) : available(value) {}
    bool open() override { return available; }
};

struct SimulatedRandom final : miezmerker::RandomSource {
    void fill_random(std::span<std::uint8_t> out) override {
        static std::uint8_t counter = 0x11;
        for (auto& b : out) b = counter++;
    }
};

}  // namespace

int main(int argc, char** argv) {
    SimulatedClock clock;
    SimulatedStorage storage(!(argc > 1 && std::string_view(argv[1]) == "--storage-unavailable"));
    miezmerker::InMemoryObservationStore observations(1024);
    miezmerker::InMemoryIdentityStore identity;
    SimulatedRandom random;
    miezmerker::Core core(clock, storage, observations, identity, random);
    if (!core.initialize()) {
        std::cerr << "core storage unavailable\n";
        return 1;
    }
    std::cout << "core ready at " << core.started_at_ms() << " ms\n";
    std::cout << "node " << core.node_id().to_string() << " seq " << core.next_sequence() << "\n";
    // Demonstrate one capture with the real core pipeline (no hardware).
    const auto result = core.record_chip_read("276098106000001");
    std::cout << "demo record: " << miezmerker::to_string(result) << "\n";
    for (const auto& obs : core.load_observations()) {
        std::cout << miezmerker::observation_to_protocol_json(obs) << "\n";
    }
    return 0;
}
