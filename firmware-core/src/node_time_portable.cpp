// Portable RTC-free time bootstrap (issue #130, ADR 0017).
// Portable checked arithmetic throughout (no compiler-specific 128-bit type) so MSVC and RV32-GCC build.
#include "miezmerker/node_time.hpp"
#include "miezmerker/ports.hpp"
#include "miezmerker/node_identity.hpp"
#include "miezmerker/time_evidence.hpp"

#include <algorithm>
#include <cstring>
#include <limits>

namespace miezmerker::node_time {
namespace {

// --- Minimal public-domain SHA-256 (no external deps) --------------------
struct Sha256Ctx {
    std::uint32_t h[8] = {0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a,
                          0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19};
    std::uint64_t total = 0;
    std::uint8_t block[64] = {};
    std::size_t used = 0;
};
inline std::uint32_t rotr(std::uint32_t x, unsigned n) { return (x >> n) | (x << (32 - n)); }
void compress(Sha256Ctx& c, const std::uint8_t* p) {
    static constexpr std::uint32_t k[64] = {
        0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
        0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
        0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
        0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
        0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
        0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
        0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
        0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2};
    std::uint32_t w[64];
    for (int i = 0; i < 16; ++i)
        w[i] = (std::uint32_t(p[4 * i]) << 24) | (std::uint32_t(p[4 * i + 1]) << 16) |
               (std::uint32_t(p[4 * i + 2]) << 8) | std::uint32_t(p[4 * i + 3]);
    for (int i = 16; i < 64; ++i) {
        const std::uint32_t s0 = rotr(w[i - 15], 7) ^ rotr(w[i - 15], 18) ^ (w[i - 15] >> 3);
        const std::uint32_t s1 = rotr(w[i - 2], 17) ^ rotr(w[i - 2], 19) ^ (w[i - 2] >> 10);
        w[i] = w[i - 16] + s0 + w[i - 7] + s1;
    }
    std::uint32_t a = c.h[0], b = c.h[1], cc = c.h[2], d = c.h[3];
    std::uint32_t e = c.h[4], f = c.h[5], g = c.h[6], h = c.h[7];
    for (int i = 0; i < 64; ++i) {
        const std::uint32_t s1 = rotr(e, 6) ^ rotr(e, 11) ^ rotr(e, 25);
        const std::uint32_t ch = (e & f) ^ (~e & g);
        const std::uint32_t t1 = h + s1 + ch + k[i] + w[i];
        const std::uint32_t s0 = rotr(a, 2) ^ rotr(a, 13) ^ rotr(a, 22);
        const std::uint32_t mj = (a & b) ^ (a & cc) ^ (b & cc);
        const std::uint32_t t2 = s0 + mj;
        h = g; g = f; f = e; e = d + t1; d = cc; cc = b; b = a; a = t1 + t2;
    }
    c.h[0] += a; c.h[1] += b; c.h[2] += cc; c.h[3] += d;
    c.h[4] += e; c.h[5] += f; c.h[6] += g; c.h[7] += h;
}
void update(Sha256Ctx& c, const std::uint8_t* data, std::size_t len) {
    c.total += len;
    while (len) {
        const std::size_t room = 64 - c.used;
        const std::size_t take = std::min(room, len);
        std::memcpy(c.block + c.used, data, take);
        c.used += take; data += take; len -= take;
        if (c.used == 64) { compress(c, c.block); c.used = 0; }
    }
}
std::array<std::uint8_t, 32> finish(Sha256Ctx& c) {
    const std::uint64_t bits = c.total * 8;
    std::uint8_t pad = 0x80;
    update(c, &pad, 1);
    std::uint8_t zero = 0;
    while (c.used != 56) update(c, &zero, 1);
    std::uint8_t len[8];
    for (int i = 0; i < 8; ++i) len[i] = static_cast<std::uint8_t>(bits >> (56 - 8 * i));
    // Append length without affecting total (already counted).
    std::memcpy(c.block + 56, len, 8);
    compress(c, c.block);
    std::array<std::uint8_t, 32> out{};
    for (int i = 0; i < 8; ++i) {
        out[4 * i] = static_cast<std::uint8_t>(c.h[i] >> 24);
        out[4 * i + 1] = static_cast<std::uint8_t>(c.h[i] >> 16);
        out[4 * i + 2] = static_cast<std::uint8_t>(c.h[i] >> 8);
        out[4 * i + 3] = static_cast<std::uint8_t>(c.h[i]);
    }
    return out;
}

void put_u16le(std::vector<std::uint8_t>& out, std::uint16_t v) {
    out.push_back(static_cast<std::uint8_t>(v));
    out.push_back(static_cast<std::uint8_t>(v >> 8));
}
void put_u32le(std::vector<std::uint8_t>& out, std::uint32_t v) {
    for (int i = 0; i < 4; ++i) out.push_back(static_cast<std::uint8_t>(v >> (8 * i)));
}
void put_u64le(std::vector<std::uint8_t>& out, std::uint64_t v) {
    for (int i = 0; i < 8; ++i) out.push_back(static_cast<std::uint8_t>(v >> (8 * i)));
}

bool all_zero(std::span<const std::uint8_t> s) {
    for (auto b : s)
        if (b != 0) return false;
    return true;
}
}  // namespace

bool NodeTimeChallenge::valid_shape() const {
    if (version != kVersion) return false;
    if (!node_id.is_set() || !incarnation.is_set()) return false;
    if (boot_counter == 0) return false;
    if (all_zero(nonce)) return false;
    if (device_public_key[0] != 4) return false;
    return true;
}

std::vector<std::uint8_t> challenge_signing_bytes(const NodeTimeChallenge& c) {
    std::vector<std::uint8_t> out;
    const std::string_view domain = kChallengeDomain;
    out.insert(out.end(), domain.begin(), domain.end());
    put_u16le(out, c.version);
    out.insert(out.end(), c.node_id.bytes.begin(), c.node_id.bytes.end());
    out.insert(out.end(), c.incarnation.bytes.begin(), c.incarnation.bytes.end());
    put_u32le(out, c.boot_counter);
    put_u64le(out, c.challenge_uptime_ms);
    out.insert(out.end(), c.nonce.begin(), c.nonce.end());
    out.insert(out.end(), c.device_public_key.begin(), c.device_public_key.end());
    return out;
}

std::vector<std::uint8_t> acceptance_signing_bytes(
    const NodeId& node_id, const IncarnationId& incarnation, std::uint32_t boot_counter,
    std::span<const std::uint8_t, 32> nonce_digest,
    std::span<const std::uint8_t, 32> proof_digest,
    std::uint64_t accepted_uptime_ms, const NodeId& anchor_id) {
    std::vector<std::uint8_t> out;
    const std::string_view domain = kAcceptDomain;
    out.insert(out.end(), domain.begin(), domain.end());
    put_u16le(out, kVersion);
    out.insert(out.end(), node_id.bytes.begin(), node_id.bytes.end());
    out.insert(out.end(), incarnation.bytes.begin(), incarnation.bytes.end());
    put_u32le(out, boot_counter);
    out.insert(out.end(), nonce_digest.begin(), nonce_digest.end());
    out.insert(out.end(), proof_digest.begin(), proof_digest.end());
    put_u64le(out, accepted_uptime_ms);
    out.insert(out.end(), anchor_id.bytes.begin(), anchor_id.bytes.end());
    return out;
}

std::array<std::uint8_t, 32> sha256(std::span<const std::uint8_t> input) {
    Sha256Ctx ctx;
    if (!input.empty()) update(ctx, input.data(), input.size());
    return finish(ctx);
}

std::string base64url_encode(std::span<const std::uint8_t> input) {
    static constexpr char kAlphabet[] =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
    std::string out;
    unsigned accumulator = 0;
    int bits = 0;
    for (auto b : input) {
        accumulator = (accumulator << 8) | b;
        bits += 8;
        while (bits >= 6) {
            bits -= 6;
            out += kAlphabet[(accumulator >> bits) & 63];
        }
    }
    if (bits) out += kAlphabet[(accumulator << (6 - bits)) & 63];
    return out;
}

bool base64url_decode(std::string_view text, std::vector<std::uint8_t>& out) {
    out.clear();
    if (text.empty() || text.size() % 4 == 1) return false;
    unsigned accumulator = 0;
    int bits = 0;
    for (char ch : text) {
        int v = (ch >= 'A' && ch <= 'Z') ? ch - 'A'
                : (ch >= 'a' && ch <= 'z') ? ch - 'a' + 26
                : (ch >= '0' && ch <= '9') ? ch - '0' + 52
                : ch == '-'                 ? 62
                : ch == '_'                 ? 63
                                            : -1;
        if (v < 0) return false;
        accumulator = (accumulator << 6) | static_cast<unsigned>(v);
        bits += 6;
        if (bits >= 8) {
            bits -= 8;
            out.push_back(static_cast<std::uint8_t>(accumulator >> bits));
        }
    }
    if (bits) {
        if ((accumulator & ((1U << bits) - 1U)) != 0) return false;
    }
    return true;
}

bool base64url_decode_fixed(std::string_view text, std::span<std::uint8_t> out) {
    std::vector<std::uint8_t> tmp;
    if (!base64url_decode(text, tmp) || tmp.size() != out.size()) return false;
    std::copy(tmp.begin(), tmp.end(), out.begin());
    return true;
}

bool is_canonical_base64url(std::string_view text, std::size_t expected_bytes) {
    if (text.size() != (expected_bytes * 8 + 5) / 6) return false;
    std::vector<std::uint8_t> decoded;
    if (!base64url_decode(text, decoded) || decoded.size() != expected_bytes) return false;
    return base64url_encode(decoded) == text;
}

namespace {
// Portable checked arithmetic (no compiler-specific 128-bit integer type:
// MSVC and RV32-GCC lack it).
bool to_i64(std::uint64_t value, std::int64_t& out) {
    if (value > static_cast<std::uint64_t>(std::numeric_limits<std::int64_t>::max()))
        return false;
    out = static_cast<std::int64_t>(value);
    return true;
}
bool checked_add(std::int64_t a, std::int64_t b, std::int64_t& out) {
    if (b > 0 && a > std::numeric_limits<std::int64_t>::max() - b) return false;
    if (b < 0 && a < std::numeric_limits<std::int64_t>::min() - b) return false;
    out = a + b;
    return true;
}
bool checked_sub(std::int64_t a, std::int64_t b, std::int64_t& out) {
    if (b > 0 && a < std::numeric_limits<std::int64_t>::min() + b) return false;
    if (b < 0 && a > std::numeric_limits<std::int64_t>::max() + b) return false;
    out = a - b;
    return true;
}
}  // namespace


bool compute_acceptance_interval(std::int64_t server_utc_ms, std::int64_t server_error_ms,
                                 std::uint64_t challenge_uptime_ms,
                                 std::uint64_t accepted_uptime_ms,
                                 std::uint64_t sampling_ms,
                                 AcceptanceInterval& out) {
    out = {};
    if (server_utc_ms <= 0 || server_error_ms < 0) return false;
    if (accepted_uptime_ms < challenge_uptime_ms) return false;
    const std::uint64_t elapsed = accepted_uptime_ms - challenge_uptime_ms;
    // D is bounded by the caller (challenge lifetime); reject absurd delays.
    if (elapsed > kChallengeLifetimeMs) return false;
    // Checked: lower = S - E - sampling, upper = S + E + D + sampling.
    std::int64_t elapsed_i64 = 0, sampling_i64 = 0;
    if (!to_i64(elapsed, elapsed_i64) || !to_i64(sampling_ms, sampling_i64)) return false;
    std::int64_t lower = 0, upper = 0, tmp = 0;
    if (!checked_sub(server_utc_ms, server_error_ms, tmp)) return false;
    if (!checked_sub(tmp, sampling_i64, lower)) return false;
    if (!checked_add(server_utc_ms, server_error_ms, tmp)) return false;
    if (!checked_add(tmp, elapsed_i64, upper)) return false;
    if (!checked_add(upper, sampling_i64, upper)) return false;
    if (lower > upper) return false;
    out.lower_ms = lower;
    out.upper_ms = upper;
    out.server_utc_ms = server_utc_ms;
    out.challenge_uptime_ms = challenge_uptime_ms;
    out.accepted_uptime_ms = accepted_uptime_ms;
    return true;
}

bool advance_interval(const AcceptanceInterval& anchor, std::uint64_t now_uptime_ms,
                      std::uint32_t drift_ppm, std::uint64_t max_age_ms,
                      std::int64_t& lower_ms, std::int64_t& upper_ms) {
    lower_ms = 0; upper_ms = 0;
    if (anchor.upper_ms < anchor.lower_ms) return false;
    if (now_uptime_ms < anchor.accepted_uptime_ms) return false;  // rollback
    const std::uint64_t elapsed = now_uptime_ms - anchor.accepted_uptime_ms;
    if (elapsed > max_age_ms) return false;  // finite trust lifetime
    // drift = ceil(elapsed * ppm / 1e6), checked in 64 bit.
    if (drift_ppm != 0 && elapsed > std::numeric_limits<std::uint64_t>::max() / drift_ppm)
        return false;
    const std::uint64_t product = elapsed * drift_ppm;
    if (product > std::numeric_limits<std::uint64_t>::max() - 999999ULL) return false;
    const std::uint64_t drift_u64 = (product + 999999ULL) / 1000000ULL;
    std::int64_t drift = 0, elapsed_i64 = 0;
    if (!to_i64(drift_u64, drift) || !to_i64(elapsed, elapsed_i64)) return false;
    std::int64_t lower = 0, upper = 0, tmp = 0;
    if (!checked_add(anchor.lower_ms, elapsed_i64, tmp)) return false;
    if (!checked_sub(tmp, drift, lower)) return false;
    if (!checked_add(anchor.upper_ms, elapsed_i64, tmp)) return false;
    if (!checked_add(tmp, drift, upper)) return false;
    if (lower > upper) return false;
    lower_ms = lower;
    upper_ms = upper;
    return true;
}

bool credential_covers(std::int64_t iat_seconds, std::int64_t exp_seconds,
                       std::int64_t lower_ms, std::int64_t upper_ms) {
    if (iat_seconds <= 0 || exp_seconds <= 0 || iat_seconds >= exp_seconds) return false;
    if (lower_ms > upper_ms) return false;
    // Seconds->ms conversion overflows deny fail-closed (portable, see above).
    constexpr std::int64_t kMaxSeconds = 9223372036854775LL;  // INT64_MAX/1000
    if (iat_seconds > kMaxSeconds || exp_seconds > kMaxSeconds) return false;
    const std::int64_t iat_ms = iat_seconds * 1000;
    const std::int64_t exp_ms = exp_seconds * 1000;
    if (iat_ms / 1000 != iat_seconds || exp_ms / 1000 != exp_seconds) return false;
    return lower_ms >= iat_ms && upper_ms < exp_ms;
}

std::optional<std::int64_t> nominal_midpoint(std::int64_t lower_ms, std::int64_t upper_ms) {
    if (lower_ms > upper_ms) return std::nullopt;
    // Fail closed on absurd near-limit bounds; valid UTC never approaches them.
    if ((lower_ms > 0 && upper_ms > 0 &&
         lower_ms > std::numeric_limits<std::int64_t>::max() - upper_ms) ||
        (lower_ms < 0 && upper_ms < 0 &&
         lower_ms < std::numeric_limits<std::int64_t>::min() - upper_ms))
        return std::nullopt;
    const std::int64_t mid = lower_ms / 2 + upper_ms / 2 + (lower_ms % 2 + upper_ms % 2) / 2;
    return mid;
}

SecurityClock::SecurityClock(NodeTimeConfig config) : config_(config) {}

void SecurityClock::on_boot(const NodeId& node_id, const IncarnationId& incarnation,
                            std::uint32_t boot_counter) {
    node_id_ = node_id;
    incarnation_ = incarnation;
    boot_counter_ = boot_counter;
    boot_set_ = boot_counter != 0;
    has_pending_ = false;
    pending_ = {};
    has_anchor_ = false;
    anchor_ = {};
    last_seen_valid_ = false;
    last_seen_uptime_ = 0;
    state_ = SecurityTimeState::UNKNOWN;
}

void SecurityClock::invalidate() {
    has_pending_ = false;
    pending_ = {};
    has_anchor_ = false;
    anchor_ = {};
    last_seen_valid_ = false;
    state_ = SecurityTimeState::INVALIDATED;
}

bool SecurityClock::store_pending(const NodeTimeChallenge& challenge) {
    if (!boot_set_) return false;
    if (!challenge.valid_shape()) return false;
    if (!(challenge.node_id == node_id_ && challenge.incarnation == incarnation_ &&
          challenge.boot_counter == boot_counter_))
        return false;
    pending_ = challenge;
    has_pending_ = true;
    // A new challenge supersedes the previous one but never creates trust.
    // Existing bounded trust is retained alongside the new pending challenge;
    // the reported state stays BOUNDED until the new proof activates.
    if (!has_anchor_) state_ = SecurityTimeState::CHALLENGE_PENDING;
    return true;
}

bool SecurityClock::pending_expired(std::uint64_t now_uptime_ms) const {
    if (!has_pending_) return true;
    if (now_uptime_ms < pending_.challenge_uptime_ms) return true;  // rollback
    return now_uptime_ms - pending_.challenge_uptime_ms > config_.challenge_lifetime_ms;
}

bool SecurityClock::take_pending_for_verify(std::uint64_t now_uptime_ms,
                                            NodeTimeChallenge& out) {
    if (!boot_set_ || !has_pending_) {
        has_pending_ = false;
        if (!has_anchor_) state_ = SecurityTimeState::INVALIDATED;
        return false;
    }
    NodeTimeChallenge pending = pending_;
    // Every verification attempt consumes the pending challenge, even when
    // the proof is invalid (single-use nonce).
    has_pending_ = false;
    if (!(pending.node_id == node_id_ && pending.incarnation == incarnation_ &&
          pending.boot_counter == boot_counter_)) {
        if (!has_anchor_) state_ = SecurityTimeState::INVALIDATED;
        return false;
    }
    if (now_uptime_ms < pending.challenge_uptime_ms ||
        now_uptime_ms - pending.challenge_uptime_ms > config_.challenge_lifetime_ms) {
        if (!has_anchor_) state_ = SecurityTimeState::INVALIDATED;
        return false;
    }
    out = pending;
    if (!has_anchor_) state_ = SecurityTimeState::VERIFYING;
    return true;
}

bool SecurityClock::activate(const AcceptanceInterval& interval,
                             std::uint32_t drift_ppm) {
    if (!boot_set_) return false;
    if (interval.upper_ms < interval.lower_ms) return false;
    if (interval.server_utc_ms <= 0) return false;
    if (interval.accepted_uptime_ms < interval.challenge_uptime_ms) return false;
    if (interval.accepted_uptime_ms - interval.challenge_uptime_ms >
        config_.challenge_lifetime_ms)
        return false;
    anchor_ = interval;
    anchor_drift_ppm_ = drift_ppm;
    has_anchor_ = true;
    has_pending_ = false;
    last_seen_uptime_ = interval.accepted_uptime_ms;
    last_seen_valid_ = true;
    state_ = SecurityTimeState::BOUNDED;
    return true;
}

bool SecurityClock::current_interval(std::uint64_t now_uptime_ms, bool monotonic_valid,
                                     std::int64_t& lower_ms, std::int64_t& upper_ms) {
    lower_ms = 0; upper_ms = 0;
    if (!boot_set_ || !has_anchor_) return false;
    if (!monotonic_valid) {
        invalidate();
        return false;
    }
    // Monotonic timer discontinuity (including Deep Sleep reset) invalidates.
    if (last_seen_valid_ && now_uptime_ms < last_seen_uptime_) {
        invalidate();
        return false;
    }
    std::int64_t lower = 0, upper = 0;
    if (!advance_interval(anchor_, now_uptime_ms, anchor_drift_ppm_,
                          config_.max_trust_age_ms, lower, upper)) {
        // Expiry/rollback/overflow downgrades trust; never manufacture time.
        has_anchor_ = false;
        state_ = SecurityTimeState::INVALIDATED;
        return false;
    }
    last_seen_uptime_ = now_uptime_ms;
    last_seen_valid_ = true;
    state_ = SecurityTimeState::BOUNDED;
    lower_ms = lower;
    upper_ms = upper;
    return true;
}

bool SecurityClock::credential_valid(std::int64_t iat_seconds, std::int64_t exp_seconds,
                                     std::uint64_t now_uptime_ms,
                                     bool monotonic_valid) {
    std::int64_t lower = 0, upper = 0;
    if (!current_interval(now_uptime_ms, monotonic_valid, lower, upper)) return false;
    return credential_covers(iat_seconds, exp_seconds, lower, upper);
}

// --- NodeTimeBootstrapManager --------------------------------------------
namespace {
bool byte_pubkey_equal(const std::array<std::byte, 65>& a,
                       const std::array<std::uint8_t, 65>& b) {
    for (std::size_t i = 0; i < 65; ++i)
        if (static_cast<std::uint8_t>(a[i]) != b[i]) return false;
    return true;
}
void copy_pubkey(const std::array<std::byte, 65>& from, std::array<std::uint8_t, 65>& to) {
    for (std::size_t i = 0; i < 65; ++i) to[i] = static_cast<std::uint8_t>(from[i]);
}
bool private_nonzero(const std::array<std::byte, 32>& key) {
    for (auto b : key)
        if (b != std::byte{0}) return true;
    return false;
}
}  // namespace

NodeTimeBootstrapManager::NodeTimeBootstrapManager(
    Clock& clock, RandomSource& random, NodeCrypto& crypto, TimeEvidenceStore& evidence,
    VerifierFn verifier, std::span<const unsigned char, 65> issuer_key, NodeTimeConfig config)
    : clock_(&clock),
      random_(&random),
      crypto_(&crypto),
      evidence_(&evidence),
      verifier_(verifier),
      config_(config),
      security_(config) {
    issuer_pinned_ = issuer_key.size() == 65 && issuer_key[0] == 4;
    if (issuer_pinned_) std::copy(issuer_key.begin(), issuer_key.end(), issuer_key_.begin());
}

bool NodeTimeBootstrapManager::on_boot(const NodeId& node_id,
                                       const IncarnationId& incarnation,
                                       std::uint32_t boot_counter) {
    last_acceptance_json_.clear();
    last_proof_.clear();
    stored_anchor_.reset();
    last_anchor_ = nullptr;
    if (!node_id.is_set() || !incarnation.is_set() || boot_counter == 0) {
        security_.invalidate();
        return false;
    }
    security_.on_boot(node_id, incarnation, boot_counter);
    return true;
}

bool NodeTimeBootstrapManager::create_challenge(
    const std::array<std::byte, 65>& device_public_key,
    const std::array<std::byte, 32>& device_private_key, NodeTimeChallenge& out) {
    out = {};
    if (!clock_ || !random_ || !crypto_) return false;
    if (device_public_key[0] != std::byte{4} || !private_nonzero(device_private_key))
        return false;
    if (!clock_->monotonic_valid()) return false;
    const std::uint64_t now = clock_->monotonic_ms();
    NodeTimeChallenge candidate;
    candidate.node_id = security_.node_id();
    candidate.incarnation = security_.incarnation();
    candidate.boot_counter = security_.boot_counter();
    if (!candidate.node_id.is_set() || !candidate.incarnation.is_set() ||
        candidate.boot_counter == 0)
        return false;
    std::array<std::uint8_t, 32> nonce{};
    random_->fill_random(std::span<std::uint8_t>(nonce.data(), nonce.size()));
    bool nonzero = false;
    for (auto b : nonce)
        if (b != 0) nonzero = true;
    if (!nonzero) return false;
    candidate.nonce = nonce;
    candidate.challenge_uptime_ms = now;
    copy_pubkey(device_public_key, candidate.device_public_key);
    {
        std::array<std::uint8_t, 32> digest = sha256(std::span<const std::uint8_t>(
            candidate.device_public_key.data(), candidate.device_public_key.size()));
        candidate.fingerprint = digest;
    }
    candidate.version = kVersion;
    // Device possession proof over the canonical domain-separated bytes.
    const auto signing = challenge_signing_bytes(candidate);
    std::array<std::byte, 64> signature{};
    const std::span<const std::byte> message(
        reinterpret_cast<const std::byte*>(signing.data()), signing.size());
    if (!crypto_->sign(std::span<const std::byte, 32>(device_private_key.data(), 32),
                       message,
                       std::span<std::byte, 64>(signature.data(), 64)))
        return false;
    for (std::size_t i = 0; i < 64; ++i)
        candidate.signature[i] = static_cast<std::uint8_t>(signature[i]);
    if (!security_.store_pending(candidate)) return false;
    out = candidate;
    return true;
}

std::string NodeTimeBootstrapManager::challenge_to_json(const NodeTimeChallenge& c) {
    std::string json = "{\"nodeId\":\"" + c.node_id.to_string() + "\",\"incarnation\":\"" +
                       c.incarnation.to_string() + "\",\"bootCounter\":" +
                       std::to_string(c.boot_counter) + ",\"challengeNonce\":\"" +
                       base64url_encode(std::span<const std::uint8_t>(c.nonce.data(), 32)) +
                       "\",\"challengeUptimeMs\":" +
                       std::to_string(c.challenge_uptime_ms) + ",\"devicePublicKey\":\"" +
                       base64url_encode(std::span<const std::uint8_t>(
                           c.device_public_key.data(), 65)) +
                       "\",\"devicePublicKeyFingerprint\":\"" +
                       base64url_encode(std::span<const std::uint8_t>(
                           c.fingerprint.data(), 32)) +
                       "\",\"deviceChallengeSignature\":\"" +
                       base64url_encode(std::span<const std::uint8_t>(
                           c.signature.data(), 64)) +
                       "\",\"version\":1}";
    return json;
}

bool NodeTimeBootstrapManager::submit_proof(
    std::string_view jws, const std::array<std::byte, 65>& device_public_key,
    const std::array<std::byte, 32>& device_private_key, std::string& acceptance_json_out) {
    acceptance_json_out.clear();
    if (!clock_ || !crypto_ || !evidence_ || !verifier_ || !issuer_pinned_) return false;
    if (jws.empty() || jws.size() > kMaxProofBytes) return false;
    if (device_public_key[0] != std::byte{4} || !private_nonzero(device_private_key))
        return false;
    if (!clock_->monotonic_valid()) {
        security_.invalidate();
        return false;
    }
    const std::uint64_t now = clock_->monotonic_ms();
    NodeTimeChallenge pending;
    // Single-use: consumes pending even when the proof is invalid.
    if (!security_.take_pending_for_verify(now, pending)) return false;
    if (!byte_pubkey_equal(device_public_key, pending.device_public_key)) {
        security_.invalidate();
        return false;
    }
    TimeProofClaims claims;
    std::span<const unsigned char, 65> issuer(issuer_key_.data(), 65);
    if (!verifier_(jws, issuer, claims)) {
        security_.invalidate();
        return false;
    }
    // Binding to the consumed challenge (all fields, canonical encodings).
    if (claims.version != 1 || claims.boot != pending.boot_counter) {
        security_.invalidate();
        return false;
    }
    if (claims.node_id != pending.node_id.to_string() ||
        claims.incarnation != pending.incarnation.to_string()) {
        security_.invalidate();
        return false;
    }
    if (claims.nonce_b64u !=
            base64url_encode(std::span<const std::uint8_t>(pending.nonce.data(), 32)) ||
        claims.dpf_b64u !=
            base64url_encode(
                std::span<const std::uint8_t>(pending.fingerprint.data(), 32))) {
        security_.invalidate();
        return false;
    }
    if (claims.utc_ms <= 0 || claims.jti.empty()) {
        security_.invalidate();
        return false;
    }
    AcceptanceInterval interval;
    if (!compute_acceptance_interval(claims.utc_ms, config_.server_error_bound_ms,
                                     pending.challenge_uptime_ms, now,
                                     config_.sampling_uncertainty_ms, interval)) {
        security_.invalidate();
        return false;
    }
    // Asymmetric uncertainty for evidence: minus = E + sampling,
    // plus = E + D + sampling (checked, fail closed on overflow).
    const std::uint64_t elapsed = now - pending.challenge_uptime_ms;
    // Portable checked sums (no compiler-specific 128-bit integer type).
    if (config_.server_error_bound_ms < 0) {
        security_.invalidate();
        return false;
    }
    {
        const auto bound =
            static_cast<std::uint64_t>(config_.server_error_bound_ms);
        const auto sampling = config_.sampling_uncertainty_ms;
        if (bound > std::numeric_limits<std::uint64_t>::max() - sampling) {
            security_.invalidate();
            return false;
        }
        if (elapsed > std::numeric_limits<std::uint64_t>::max() - (bound + sampling)) {
            security_.invalidate();
            return false;
        }
    }
    const std::uint64_t minus =
        static_cast<std::uint64_t>(config_.server_error_bound_ms) + config_.sampling_uncertainty_ms;
    const std::uint64_t plus =
        static_cast<std::uint64_t>(config_.server_error_bound_ms) + elapsed + config_.sampling_uncertainty_ms;
    // Fresh anchor identity (UUIDv4, stable idempotency for retries).
    std::array<std::uint8_t, 16> anchor_raw{};
    random_->fill_random(std::span<std::uint8_t>(anchor_raw.data(), anchor_raw.size()));
    apply_uuidv4_variant(anchor_raw);
    NodeId anchor_id;
    std::copy(anchor_raw.begin(), anchor_raw.end(), anchor_id.bytes.begin());
    if (!anchor_id.is_set()) {
        security_.invalidate();
        return false;
    }
    // Device-signed acceptance over the exact received proof.
    std::array<std::uint8_t, 32> nonce_digest = sha256(
        std::span<const std::uint8_t>(pending.nonce.data(), pending.nonce.size()));
    std::vector<std::uint8_t> proof_bytes(jws.begin(), jws.end());
    std::array<std::uint8_t, 32> proof_digest =
        sha256(std::span<const std::uint8_t>(proof_bytes.data(), proof_bytes.size()));
    std::span<const std::uint8_t, 32> nonce_span(nonce_digest.data(), 32);
    std::span<const std::uint8_t, 32> proof_span(proof_digest.data(), 32);
    const auto accept_bytes = acceptance_signing_bytes(
        pending.node_id, pending.incarnation, pending.boot_counter, nonce_span, proof_span,
        now, anchor_id);
    std::array<std::byte, 64> accept_sig{};
    const std::span<const std::byte> accept_msg(
        reinterpret_cast<const std::byte*>(accept_bytes.data()), accept_bytes.size());
    if (!crypto_->sign(std::span<const std::byte, 32>(device_private_key.data(), 32),
                       accept_msg,
                       std::span<std::byte, 64>(accept_sig.data(), 64))) {
        security_.invalidate();
        return false;
    }
    std::array<std::uint8_t, 64> accept_sig_u8{};
    for (std::size_t i = 0; i < 64; ++i)
        accept_sig_u8[i] = static_cast<std::uint8_t>(accept_sig[i]);
    const std::string proof_digest_b64 =
        base64url_encode(std::span<const std::uint8_t>(proof_digest.data(), 32));
    const std::string nonce_digest_b64 =
        base64url_encode(std::span<const std::uint8_t>(nonce_digest.data(), 32));
    const std::string accept_sig_b64 =
        base64url_encode(std::span<const std::uint8_t>(accept_sig_u8.data(), 64));
    std::string acceptance = "{\"nodeId\":\"" + pending.node_id.to_string() +
                             "\",\"incarnation\":\"" + pending.incarnation.to_string() +
                             "\",\"bootCounter\":" + std::to_string(pending.boot_counter) +
                             ",\"proofDigest\":\"" + proof_digest_b64 +
                             "\",\"challengeNonceDigest\":\"" + nonce_digest_b64 +
                             "\",\"acceptedUptimeMs\":" + std::to_string(now) +
                             ",\"anchorId\":\"" + anchor_id.to_string() +
                             "\",\"signature\":\"" + accept_sig_b64 + "\",\"version\":1}";
    if (acceptance.size() > 512) {
        security_.invalidate();
        return false;
    }
    TimeAnchorV1 anchor;
    anchor.anchor_id = anchor_id;
    anchor.node_id = pending.node_id;
    anchor.incarnation = pending.incarnation;
    anchor.boot_counter = pending.boot_counter;
    anchor.uptime_ms = now;
    anchor.utc_ms = claims.utc_ms;
    anchor.source = AnchorSource::BACKEND_SIGNED;
    anchor.verified_security_time = true;
    anchor.uncertainty_minus_ms = minus;
    anchor.uncertainty_plus_ms = plus;
    anchor.drift_ppm_bound = config_.drift_ppm_bound;
    anchor.proof_id_or_digest = claims.jti;
    anchor.proof.assign(jws.begin(), jws.end());
    anchor.acceptance = acceptance;
    if (!anchor.valid_verified()) {
        security_.invalidate();
        return false;
    }
    // Durably commit before activating the security clock (fail closed on
    // ambiguous persistence outcome).
    const PutResult stored = evidence_->append_verified(VerifiedAnchor(std::move(anchor)));
    if (stored != PutResult::OK) {
        security_.invalidate();
        return false;
    }
    // Reload the committed anchor for transport (byte-exact, incl. NUL).
    const auto anchors = evidence_->anchors();
    if (anchors.empty()) {
        security_.invalidate();
        return false;
    }
    const TimeAnchorV1& committed = anchors.back();
    if (!security_.activate(interval, config_.drift_ppm_bound)) {
        security_.invalidate();
        return false;
    }
    stored_anchor_ = committed;
    last_anchor_ = &*stored_anchor_;
    last_proof_.assign(jws.begin(), jws.end());
    last_acceptance_json_ = committed.acceptance;
    acceptance_json_out = last_acceptance_json_;
    return true;
}

SecurityTimeState NodeTimeBootstrapManager::state() const { return security_.state(); }

bool NodeTimeBootstrapManager::current_interval(std::int64_t& lower_ms,
                                               std::int64_t& upper_ms) {
    if (!clock_) return false;
    return security_.current_interval(clock_->monotonic_ms(), clock_->monotonic_valid(),
                                      lower_ms, upper_ms);
}

bool NodeTimeBootstrapManager::credential_valid(std::int64_t iat_seconds,
                                               std::int64_t exp_seconds) {
    if (!clock_) return false;
    return security_.credential_valid(iat_seconds, exp_seconds, clock_->monotonic_ms(),
                                      clock_->monotonic_valid());
}

const TimeAnchorV1* NodeTimeBootstrapManager::last_anchor() const {
    return last_anchor_;
}

const NodeTimeChallenge* NodeTimeBootstrapManager::pending_challenge() const {
    return security_.has_pending() ? &security_.pending() : nullptr;
}

}  // namespace miezmerker::node_time
