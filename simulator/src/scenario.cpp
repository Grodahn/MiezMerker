#include "miezmerker/sim/scenario.hpp"
#include "miezmerker/sim/parse_utils.hpp"

#include <algorithm>
#include <cctype>
#include <sstream>
#include <string_view>
#include <utility>

namespace miezmerker::sim {

static inline std::string trim(std::string_view s) {
    std::size_t i = 0;
    while (i < s.size() && std::isspace(static_cast<unsigned char>(s[i]))) ++i;
    std::size_t j = s.size();
    while (j > i && std::isspace(static_cast<unsigned char>(s[j - 1]))) --j;
    return std::string(s.substr(i, j - i));
}

static inline std::vector<std::string> split_lines(std::string_view text) {
    std::vector<std::string> lines;
    std::size_t start = 0;
    while (start < text.size()) {
        std::size_t end = text.find('\n', start);
        if (end == std::string_view::npos) end = text.size();
        lines.emplace_back(text.substr(start, end - start));
        start = end + 1;
    }
    return lines;
}

static inline std::vector<std::string> tokenize(std::string_view line) {
    std::vector<std::string> tokens;
    std::string current;
    bool in_quotes = false;
    bool last_was_quote = false;
    for (std::size_t i = 0; i < line.size(); ++i) {
        char c = line[i];
        if (c == '"') {
            if (in_quotes && last_was_quote) {
                // Empty quoted string - push empty token
                tokens.push_back("");
            }
            in_quotes = !in_quotes;
            last_was_quote = true;
            continue;
        }
        last_was_quote = false;
        if (!in_quotes && std::isspace(static_cast<unsigned char>(c))) {
            if (!current.empty()) {
                tokens.push_back(current);
                current.clear();
            }
        } else {
            current += c;
        }
    }
    if (!current.empty() || (in_quotes && line.size() > 0 && line.back() == '"')) {
        tokens.push_back(current);
    }
    return tokens;
}

static bool is_known_header_key(std::string_view key) {
    static const std::string_view known[] = {
        "name", "version", "description", "capacity", "debounce_ms", "seed"
    };
    for (auto k : known) if (k == key) return true;
    return false;
}

static bool is_known_event_type(std::string_view type) {
    static const std::string_view known[] = {
        "boot", "reboot", "factory_reset",
        "advance_ms", "set_ms",
        "rtc", "rtc_invalid", "rtc_correct_ms",
        "read", "read_absent", "expect_read",
        "ack", "expect_ack",
        "storage_available", "storage_unavailable",
        "fail_load_next",
        "fail_identity_store", "crash_identity_store",
        "fail_append", "crash_append",
        "fail_clear_next", "crash_clear_next",
        "fail_watermark_next",
        "assert",
        "repeat", "end"
    };
    for (auto k : known) if (k == type) return true;
    return false;
}

static bool validate_event_arity(std::string_view type, std::size_t argc, std::string& err) {
    if (type == "boot" || type == "reboot" || type == "factory_reset" ||
        type == "read_absent" || type == "fail_load_next" ||
        type == "fail_clear_next" || type == "crash_clear_next" ||
        type == "fail_watermark_next" || type == "storage_available" ||
        type == "storage_unavailable" || type == "rtc_invalid" || type == "end") {
        if (argc != 0) { err = std::string(type) + " takes 0 arguments"; return false; }
    } else if (type == "advance_ms" || type == "set_ms" ||
               type == "rtc_correct_ms" ||
               type == "read" || type == "fail_identity_store" ||
               type == "crash_identity_store" || type == "fail_append" ||
               type == "crash_append" || type == "ack" || type == "repeat") {
        if (argc != 1) { err = std::string(type) + " takes 1 argument"; return false; }
    } else if (type == "rtc") {
        if (argc < 1 || argc > 2) { err = "rtc takes 1 or 2 arguments"; return false; }
    } else if (type == "expect_read" || type == "expect_ack") {
        if (argc != 2) { err = std::string(type) + " takes 2 arguments"; return false; }
    } else if (type == "assert") {
        if (argc < 1) { err = "assert takes at least 1 argument"; return false; }
    } else {
        err = "unknown event type: " + std::string(type);
        return false;
    }
    return true;
}

ParseError parse_scenario(const std::string& text, Scenario& out) {
    out = Scenario{};
    std::vector<ScenarioEvent>* current = &out.events;

    struct RepeatFrame {
        std::size_t count;
        std::vector<ScenarioEvent>* parent;
        std::vector<ScenarioEvent> events;
    };
    std::vector<RepeatFrame> repeat_stack;

    bool header_done = false;
    std::size_t line_no = 0;
    auto lines = split_lines(text);

    for (const auto& raw_line : lines) {
        ++line_no;
        std::string line = trim(raw_line);
        if (line.empty() || line[0] == '#') continue;

        std::vector<std::string> tokens = tokenize(line);
        if (tokens.empty()) continue;

        if (!header_done) {
            auto colon = line.find(':');
            if (colon != std::string::npos) {
                std::string key = trim(line.substr(0, colon));
                // Check if this looks like a header line (key has no spaces)
                bool looks_like_header = key.find(' ') == std::string::npos;
                if (looks_like_header) {
                    std::string value = trim(line.substr(colon + 1));
                    if (is_known_header_key(key)) {
                        if (key == "name") out.name = value;
                        else if (key == "version") out.format_version = value;
                        else if (key == "description") out.description = value;
                        else if (key == "capacity") {
                            if (!parse_size_t(value, out.capacity))
                                return {line_no, "invalid capacity value"};
                        } else if (key == "debounce_ms") {
                            if (!parse_uint64(value, out.debounce_ms))
                                return {line_no, "invalid debounce_ms value"};
                        } else if (key == "seed") {
                            if (!parse_uint64(value, out.seed))
                                return {line_no, "invalid seed value"};
                        }
                        continue;
                    } else {
                        return {line_no, "unknown header key: " + key};
                    }
                }
            }
            header_done = true;
        }

        std::string type = tokens[0];
        std::vector<std::string> args(tokens.begin() + 1, tokens.end());

        if (type == "repeat") {
            if (args.size() != 1) return {line_no, "repeat takes 1 argument"};
            std::size_t count;
            if (!parse_size_t(args[0], count) || count == 0)
                return {line_no, "invalid repeat count"};
            if (!repeat_stack.empty())
                return {line_no, "nested repeat not supported"};
            repeat_stack.push_back({count, current, {}});
            current = &repeat_stack.back().events;
            continue;
        }
        if (type == "end") {
            if (repeat_stack.empty()) return {line_no, "end without repeat"};
            if (!args.empty()) return {line_no, "end takes 0 arguments"};
            auto frame = repeat_stack.back();
            repeat_stack.pop_back();
            for (std::size_t i = 0; i < frame.count; ++i) {
                frame.parent->insert(frame.parent->end(),
                                     frame.events.begin(), frame.events.end());
            }
            current = frame.parent;
            continue;
        }

        if (!is_known_event_type(type))
            return {line_no, "unknown event type: " + type};

        std::string err;
        if (!validate_event_arity(type, args.size(), err))
            return {line_no, err};

        current->push_back(ScenarioEvent{type, std::move(args), line_no});
    }

    if (!repeat_stack.empty())
        return {0, "unclosed repeat block"};

    if (out.name.empty())
        return {0, "scenario missing required 'name' header"};
    if (out.format_version.empty())
        return {0, "scenario missing required 'version' header"};

    return ParseError{};
}

}  // namespace miezmerker::sim