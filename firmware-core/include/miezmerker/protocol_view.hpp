#pragma once

// Minimal protocol view for issue #5 (forward bridge to #6).
//
// Maps a persisted RawObservation to the logical BLE record v1 defined in
// protocol/raw-observation-v1.schema.json. This is NOT the GATT codec (owned
// by #6); it is a JSON reference rendering of the protocol-v1 fields.
// boot_counter and the local clock-trust distinction are not present in v1.
//
// Mapping (documented, testable):
//   SYNCED | RTC_ONLY -> clock_status "known" with observed_at_epoch_ms string
//   UNKNOWN           -> clock_status "unknown" with observed_at_epoch_ms null
// The local store always keeps the full SYNCED vs RTC_ONLY distinction; a
// future protocol version owned by #6 may expose it on the wire. Until then the
// mapping is deliberately lossy only in that one distinction, and reviewers can
// verify it here instead of reverse-engineering the core.

#include <string>

#include "miezmerker/types.hpp"

namespace miezmerker {

/// Renders one observation as protocol-v1 JSON (decimal strings for 64-bit
/// values so JavaScript cannot round them; see protocol/README.md).
std::string observation_to_protocol_json(const RawObservation& observation);

/// "known" for SYNCED/RTC_ONLY, "unknown" for UNKNOWN.
std::string protocol_clock_status(ClockStatus status);

}  // namespace miezmerker
