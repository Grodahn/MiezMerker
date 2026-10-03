#pragma once

// Deterministic scenario format and parser (issue #22).
//
// Scenario format (simple line-based, human-readable, versionable):
// - Lines starting with # are comments.
// - Blank lines are ignored.
// - Header lines: `key: value` for name, version, description, capacity, debounce_ms, seed.
// - Event lines: `type [args...]`
// - Repeat blocks: `repeat N` ... `end` (no nesting).

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

namespace miezmerker::sim {

struct ScenarioEvent {
    std::string type;
    std::vector<std::string> args;
    std::size_t line{0};  // 1-based source line for diagnostics
};

struct Scenario {
    std::string name;
    std::string format_version;
    std::string description;
    std::size_t capacity{4096};
    std::uint64_t debounce_ms{2000};
    std::uint64_t seed{0xA5A5A5A5A5A5A5A5ULL};
    std::vector<ScenarioEvent> events;
};

struct ParseError {
    std::size_t line{0};
    std::string message;
    bool ok() const { return message.empty(); }
};

ParseError parse_scenario(const std::string& text, Scenario& out);

}  // namespace miezmerker::sim