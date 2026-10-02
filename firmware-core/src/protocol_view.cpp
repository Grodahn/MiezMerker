#include "miezmerker/protocol_view.hpp"

#include <sstream>

namespace miezmerker {

std::string protocol_clock_status(ClockStatus status) {
    switch (status) {
        case ClockStatus::SYNCED:
        case ClockStatus::RTC_ONLY:
            return "known";
        case ClockStatus::UNKNOWN:
            return "unknown";
    }
    return "unknown";
}

namespace {

void append_json_string(std::ostringstream& out, const std::string& value) {
    out << '"';
    for (const char c : value) {
        switch (c) {
            case '"':
                out << "\\\"";
                break;
            case '\\':
                out << "\\\\";
                break;
            case '\n':
                out << "\\n";
                break;
            case '\r':
                out << "\\r";
                break;
            case '\t':
                out << "\\t";
                break;
            default:
                out << c;
                break;
        }
    }
    out << '"';
}

}  // namespace

std::string observation_to_protocol_json(const RawObservation& observation) {
    std::ostringstream out;
    out << "{";
    out << "\"protocol_version\":1,";
    out << "\"node_id\":";
    append_json_string(out, observation.node_id.to_string());
    out << ",\"incarnation\":";
    append_json_string(out, observation.incarnation.to_string());
    out << ",\"sequence\":\"" << observation.sequence << "\",";
    out << "\"chip_id\":";
    append_json_string(out, observation.chip_id.value);
    out << ",\"monotonic_ms\":\"" << observation.monotonic_ms << "\",";
    if (observation.clock_status == ClockStatus::UNKNOWN || !observation.observed_at_epoch_ms.has_value()) {
        out << "\"observed_at_epoch_ms\":null,";
    } else {
        out << "\"observed_at_epoch_ms\":\"" << *observation.observed_at_epoch_ms << "\",";
    }
    out << "\"clock_status\":";
    append_json_string(out, protocol_clock_status(observation.clock_status));
    out << "}";
    return out.str();
}

}  // namespace miezmerker
