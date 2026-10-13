#pragma once
// Backend-signed UTC proof verification (issue #130, ADR 0017).
//
// ESP32-compatible (PSA on device, mbed-TLS on host) strict ES256 JWS
// verification for BackendTimeProofV1. Reuses the exact #129 contract:
// alg=ES256, pinned issuer/kid, purpose node-time-bootstrap, version 1,
// node/incarnation/boot/nonce/fingerprint binding, positive utc_ms, jti.
//
// Trust anchor is the independently provisioned issuer public key. Never
// bootstrap trust from a PWA-supplied key, the proof itself, a claim receipt
// or an unverified issuer endpoint.

#include <array>
#include <cstdint>
#include <span>
#include <string>
#include <string_view>
#include <vector>

#include "miezmerker/node_time.hpp"

namespace miezmerker {

using TimeProofClaims = node_time::TimeProofClaims;

// Strict verification against the independently pinned issuer key.
// Returns false on any malformed, oversized, noncanonical, mismatched or
// badly signed input. Never throws.
bool verify_time_proof(std::string_view proof,
                       std::span<const unsigned char, 65> issuer_key,
                       TimeProofClaims& out);

// SHA-256 and raw ES256 verification used by the bootstrap manager.
// Separated for host/ESP reuse and deterministic unit tests.
bool node_time_sha256(std::span<const unsigned char> input,
                      std::array<unsigned char, 32>& digest);
bool node_time_verify_es256(std::span<const unsigned char, 65> key,
                            std::span<const unsigned char> message,
                            std::span<const unsigned char, 64> signature);

}  // namespace miezmerker
