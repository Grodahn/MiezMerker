// Portable security-clock and challenge-lifecycle tests (#130, ADR 0017).
//
// No crypto library: device signing and JWS verification are faked with
// deterministic byte patterns. Real ES256/JWS interoperability lives in
// firmware-esp32/tests/node_time_verify_test. This file proves the
// uncertainty model, strict credential guard and single-use lifecycle.
#include <cassert>
#include <iostream>
#include <functional>
#include <limits>

#include "miezmerker/memory_stores.hpp"
#include "miezmerker/node_time.hpp"
#include "miezmerker/time_evidence.hpp"

using namespace miezmerker;
using namespace miezmerker::node_time;

namespace {
std::string g_nonce_b64, g_dpf_b64;
std::uint32_t g_boot = 42;
struct Storage : miezmerker::Storage {
    bool open() override { return true; }
};
struct FakeCrypto : NodeCrypto {
    bool sign(std::span<const std::byte, 32>, std::span<const std::byte> message,
              std::span<std::byte, 64> sig) override {
        // Deterministic stand-in: first byte encodes message length.
        std::fill(sig.begin(), sig.end(), std::byte{0xAA});
        if (!message.empty()) sig[0] = static_cast<std::byte>(message.size() & 0xFF);
        return true;
    }
    bool generate_keypair(std::span<std::byte, 65>, std::span<std::byte, 32>) override {
        return false;
    }
    bool verify_claim(const std::string&, VerifiedClaim&) override { return false; }
};
struct FakeEvidence : TimeEvidenceStore {
    bool open() override { return true; }
    PutResult append_boot(const BootEpochV1&) override { return PutResult::OK; }
    PutResult append_anchor(const TimeAnchorV1&) override { return PutResult::IO_ERROR; }
    PutResult append_verified(const VerifiedAnchor& v) override {
        if (fail_verified) return PutResult::IO_ERROR;
        if (during_append) during_append();
        saved.push_back(v.anchor());
        if (after_append) after_append(saved);
        return PutResult::OK;
    }
    std::vector<BootEpochV1> boots() const override { return {}; }
    std::vector<TimeAnchorV1> anchors() const override { return saved; }
    bool clear_for_factory_reset() override {
        saved.clear();
        return true;
    }
    std::vector<TimeAnchorV1> saved;
    bool fail_verified{false};
    std::function<void()> during_append;
    std::function<void(std::vector<TimeAnchorV1>&)> after_append;
};
bool fake_verify(std::string_view proof, std::span<const unsigned char, 65>,
                 TimeProofClaims& out) {
    if (proof == "valid-proof") {
        out.node_id = "44444444-4444-4444-8444-444444444444";
        out.incarnation = "55555555-5555-4555-8555-555555555555";
        out.boot = g_boot;
        out.nonce_b64u = g_nonce_b64;
        out.dpf_b64u = g_dpf_b64;
        out.jti = "77777777-7777-4777-8777-777777777777";
        out.utc_ms = 1791748800123LL;
        out.version = 1;
        return true;
    }
    return false;
}

NodeId test_node() {
    auto id = NodeId::parse("44444444-4444-4444-8444-444444444444");
    assert(id);
    return *id;
}
IncarnationId test_incarn() {
    auto id = IncarnationId::parse("55555555-5555-4555-8555-555555555555");
    assert(id);
    return *id;
}
std::array<std::byte, 65> test_pubkey() {
    std::array<std::byte, 65> key{};
    key[0] = std::byte{4};
    for (std::size_t i = 1; i < 65; ++i) key[i] = static_cast<std::byte>(i);
    return key;
}
std::array<std::byte, 32> test_privkey() {
    std::array<std::byte, 32> key{};
    for (std::size_t i = 0; i < 32; ++i) key[i] = static_cast<std::byte>(i + 1);
    return key;
}
std::array<unsigned char, 65> test_issuer() {
    std::array<unsigned char, 65> key{};
    key[0] = 4;
    for (std::size_t i = 1; i < 65; ++i) key[i] = static_cast<unsigned char>(i * 3);
    return key;
}

void interval_tv5() {
    // ADR TV5: S=2026-10-11T20:00:00.000Z, E=100ms, U_challenge=1000,
    // U_accept=1300, D=300ms -> [19:59:59.900, 20:00:00.400].
    AcceptanceInterval interval;
    assert(compute_acceptance_interval(1791748800000LL, 100, 1000, 1300, 0, interval));
    assert(interval.lower_ms == 1791748799900LL);
    assert(interval.upper_ms == 1791748800400LL);
    // Credential with exp exactly at signed UTC is rejected (U >= exp).
    assert(!credential_covers(1791748799LL, 1791748800LL, interval.lower_ms,
                             interval.upper_ms));
    // Wholly inside with margin is accepted.
    assert(credential_covers(1791748799LL, 1791748801LL, interval.lower_ms,
                            interval.upper_ms));
    // Sampling uncertainty widens both sides.
    AcceptanceInterval wide;
    assert(compute_acceptance_interval(1791748800000LL, 100, 1000, 1300, 2, wide));
    assert(wide.lower_ms == 1791748799898LL && wide.upper_ms == 1791748800402LL);
    // Overflow fails closed.
    assert(!compute_acceptance_interval(std::numeric_limits<std::int64_t>::max(), 1,
                                        0, 0, 0, interval));
    assert(!compute_acceptance_interval(1000, 0, 2000, 1000, 0, interval));
    assert(!compute_acceptance_interval(1000, -1, 1000, 1000, 0, interval));
    assert(!compute_acceptance_interval(0, 100, 1000, 1300, 0, interval));
    // The delay bound honors configuration instead of the default 30 s.
    AcceptanceInterval custom;
    assert(!compute_acceptance_interval(1791748800000LL, 100, 1000, 11000, 0, custom,
                                        5000));
    assert(compute_acceptance_interval(1791748800000LL, 100, 1000, 5000, 0, custom,
                                       5000));
    assert(compute_acceptance_interval(1791748800000LL, 100, 1000, 11000, 0, custom));
}

void clock_advancement_and_drift() {
    AcceptanceInterval anchor;
    assert(compute_acceptance_interval(1791748800000LL, 100, 1000, 1300, 0, anchor));
    // Zero drift: exact elapsed translation.
    std::int64_t lower = 0, upper = 0;
    assert(advance_interval(anchor, 2300, 0, 86400000LL, lower, upper));
    assert(lower == 1791748799900LL + 1000 && upper == 1791748800400LL + 1000);
    // Drift widens asymmetrically: ceil(1000ms * 5000ppm / 1e6) = 5ms.
    assert(advance_interval(anchor, 2300, 5000, 86400000LL, lower, upper));
    assert(lower == 1791748799900LL + 1000 - 5 && upper == 1791748800400LL + 1000 + 5);
    // Rollback fails.
    assert(!advance_interval(anchor, 1299, 0, 86400000LL, lower, upper));
    // Expiry fails.
    assert(!advance_interval(anchor, 1300 + 86400000LL + 1, 0, 86400000LL, lower, upper));
    // Overflow fails closed.
    AcceptanceInterval huge{std::numeric_limits<std::int64_t>::max() - 10,
                            std::numeric_limits<std::int64_t>::max() - 5, 1000, 0, 0};
    assert(!advance_interval(huge, 100, 0, 86400000LL, lower, upper));
}

void credential_boundaries() {
    // Interval [1000000, 1000500] ms -> [1000, 1000] s with 500ms uncertainty.
    const std::int64_t lower = 1000000, upper = 1000500;
    assert(credential_covers(999, 1001, lower, upper));
    // Expiry inside uncertainty: U=1000500 >= exp*1000=1000500? exp=1001s
    // -> 1001000ms, U < exp holds; use exp=1000s -> 1000000ms, U >= exp denies.
    assert(!credential_covers(999, 1000, lower, upper));
    // iat inside uncertainty: iat=1000s -> 1000000ms, L >= iat holds equality;
    // iat=1001s -> 1001000ms, L < iat denies.
    assert(credential_covers(1000, 1002, lower, upper));
    assert(!credential_covers(1001, 1002, lower, upper));
    // Expired beyond upper: exp=1000s, U=1000500 >= 1000000 denies (above).
    // Not yet valid at lower: iat=1002s denies (above).
    assert(!credential_covers(0, 1001, lower, upper));
    assert(!credential_covers(999, 999, lower, upper));
    assert(!credential_covers(1001, 1000, lower, upper));
    // Overflow denies.
    assert(!credential_covers(9223372036854775LL, 9223372036854776LL, lower, upper));
}

void challenge_lifecycle() {
    g_boot = 42;
    ManualClock clock;
    FixedRandomSource random{7};
    FakeCrypto crypto;
    FakeEvidence evidence;
    auto issuer = test_issuer();
    NodeTimeBootstrapManager manager(clock, random, crypto, evidence, fake_verify,
                                     std::span<const unsigned char, 65>(issuer));
    assert(manager.on_boot(test_node(), test_incarn(), 42));
    assert(manager.state() == SecurityTimeState::UNKNOWN);
    // No trust before bootstrap: credential checks fail closed.
    assert(!manager.credential_valid(1791748799LL, 1791748801LL));
    NodeTimeChallenge first;
    assert(manager.create_challenge(test_pubkey(), test_privkey(), first));
    assert(manager.state() == SecurityTimeState::CHALLENGE_PENDING);
    // Challenge shape: version, boot, nonzero nonce, 0x04 pubkey.
    assert(first.version == 1 && first.boot_counter == 42 && first.node_id == test_node());
    std::vector<std::uint8_t> nonce(first.nonce.begin(), first.nonce.end());
    bool nonzero = false;
    for (auto b : nonce)
        if (b) nonzero = true;
    assert(nonzero);
    assert(first.device_public_key[0] == 4);
    // Fingerprint binds to the same pubkey bytes.
    auto digest = sha256(std::span<const std::uint8_t>(first.device_public_key.data(), 65));
    assert(digest == first.fingerprint);
    // Canonical signing bytes start with the normative domain.
    const auto signing = challenge_signing_bytes(first);
    const std::string domain(kChallengeDomain);
    assert(signing.size() == domain.size() + 2 + 16 + 16 + 4 + 8 + 32 + 65);
    assert(std::equal(domain.begin(), domain.end(), signing.begin()));
    // Pre-auth disclosure: JSON contains only time-bootstrap fields.
    const auto json = NodeTimeBootstrapManager::challenge_to_json(first);
    assert(json.find("nodeId") != std::string::npos);
    assert(json.find("chip") == std::string::npos);
    assert(json.find("contact") == std::string::npos);
    assert(json.find("organization") == std::string::npos);
    // New challenge supersedes the previous one: a proof bound to the stale
    // first nonce must fail even though its claims are otherwise valid.
    g_nonce_b64 = base64url_encode(
        std::span<const std::uint8_t>(first.nonce.data(), 32));
    g_dpf_b64 = base64url_encode(
        std::span<const std::uint8_t>(first.fingerprint.data(), 32));
    NodeTimeChallenge second;
    assert(manager.create_challenge(test_pubkey(), test_privkey(), second));
    assert(!(second.nonce == first.nonce));
    std::string stale_acceptance;
    assert(!manager.submit_proof("valid-proof", test_pubkey(), test_privkey(),
                                 stale_acceptance));
    assert(manager.state() == SecurityTimeState::INVALIDATED);
}

void full_lifecycle_with_fake_verifier() {
    g_boot = 42;
    ManualClock clock;
    clock.set_monotonic_ms(1000);
    FixedRandomSource random{11};
    FakeCrypto crypto;
    FakeEvidence evidence;
    auto issuer = test_issuer();
    NodeTimeBootstrapManager manager(clock, random, crypto, evidence, fake_verify,
                                     std::span<const unsigned char, 65>(issuer));
    assert(manager.on_boot(test_node(), test_incarn(), 42));
    NodeTimeChallenge challenge;
    assert(manager.create_challenge(test_pubkey(), test_privkey(), challenge));
    g_nonce_b64 = base64url_encode(
        std::span<const std::uint8_t>(challenge.nonce.data(), 32));
    g_dpf_b64 = base64url_encode(
        std::span<const std::uint8_t>(challenge.fingerprint.data(), 32));
    clock.set_monotonic_ms(1300);
    std::string acceptance;
    assert(manager.submit_proof("valid-proof", test_pubkey(), test_privkey(), acceptance));
    assert(manager.state() == SecurityTimeState::BOUNDED);
    assert(!acceptance.empty() && acceptance.find("anchorId") != std::string::npos);
    assert(evidence.saved.size() == 1);
    assert(evidence.saved.front().verified_security_time);
    assert(evidence.saved.front().source == AnchorSource::BACKEND_SIGNED);
    assert(evidence.saved.front().proof == "valid-proof");
    // Replay of the same proof fails (nonce consumed).
    std::string replay;
    assert(!manager.submit_proof("valid-proof", test_pubkey(), test_privkey(), replay));
    // Credential wholly within bounds is accepted; boundary overlap denies.
    std::int64_t lower = 0, upper = 0;
    assert(manager.current_interval(lower, upper));
    // Interval is [S-E-sampling, S+E+D+sampling] with defaults E=1000, D=300.
    assert(lower == 1791748800123LL - 1000 - 2);
    assert(upper == 1791748800123LL + 1000 + 300 + 2);
    const std::int64_t iat = lower / 1000;
    const std::int64_t exp = upper / 1000 + 2;
    assert(manager.credential_valid(iat, exp));
    // Expiry inside the uncertainty interval denies (U >= exp_ms).
    assert(!manager.credential_valid(iat, upper / 1000));
    // Timeout: new challenge then delayed proof beyond 30 s fails.
    NodeTimeChallenge late;
    clock.set_monotonic_ms(5000);
    assert(manager.create_challenge(test_pubkey(), test_privkey(), late));
    g_nonce_b64 = base64url_encode(
        std::span<const std::uint8_t>(late.nonce.data(), 32));
    g_dpf_b64 = base64url_encode(
        std::span<const std::uint8_t>(late.fingerprint.data(), 32));
    clock.set_monotonic_ms(5000 + 30001);
    std::string late_accept;
    assert(!manager.submit_proof("valid-proof", test_pubkey(), test_privkey(), late_accept));
    // Reboot invalidates pending and trust.
    clock.set_monotonic_ms(6000);
    NodeTimeChallenge before_reboot;
    assert(manager.create_challenge(test_pubkey(), test_privkey(), before_reboot));
    assert(manager.on_boot(test_node(), test_incarn(), 43));
    assert(manager.state() == SecurityTimeState::UNKNOWN);
    assert(manager.pending_challenge() == nullptr);
    assert(!manager.credential_valid(iat, exp));
    // Reboot after acceptance also drops trust (fresh boot starts UNKNOWN).
    assert(manager.on_boot(test_node(), test_incarn(), 43));
    g_boot = 43;
    NodeTimeChallenge after;
    assert(manager.create_challenge(test_pubkey(), test_privkey(), after));
    g_nonce_b64 = base64url_encode(
        std::span<const std::uint8_t>(after.nonce.data(), 32));
    g_dpf_b64 = base64url_encode(
        std::span<const std::uint8_t>(after.fingerprint.data(), 32));
    clock.set_monotonic_ms(6300);
    std::string ok;
    assert(manager.submit_proof("valid-proof", test_pubkey(), test_privkey(), ok));
    assert(manager.state() == SecurityTimeState::BOUNDED);
    assert(manager.on_boot(test_node(), test_incarn(), 44));
    assert(manager.state() == SecurityTimeState::UNKNOWN);
}

void persisted_anchor_integrity() {
    using Mutation = std::function<void(std::vector<TimeAnchorV1>&)>;
    const std::vector<Mutation> corruptions = {
        [](auto& v) { v.clear(); },
        [](auto& v) { v.push_back(v.front()); },
        [](auto& v) { v.front().anchor_id.bytes[0] ^= 1; },
        [](auto& v) { v.front().node_id.bytes[0] ^= 1; },
        [](auto& v) { v.front().incarnation.bytes[0] ^= 1; },
        [](auto& v) { ++v.front().boot_counter; },
        [](auto& v) { ++v.front().uptime_ms; },
        [](auto& v) { ++v.front().utc_ms; },
        [](auto& v) { ++v.front().format_version; },
        [](auto& v) { v.front().source = AnchorSource::PHONE_ESTIMATE; },
        [](auto& v) { v.front().verified_security_time = false; },
        [](auto& v) { ++v.front().uncertainty_minus_ms; },
        [](auto& v) { ++v.front().uncertainty_plus_ms; },
        [](auto& v) { ++v.front().drift_ppm_bound; },
        [](auto& v) { v.front().proof_id_or_digest += "x"; },
        [](auto& v) { v.front().proof += '\0'; },
        [](auto& v) { v.front().acceptance += "x"; },
        [](auto& v) { v.front().challenge += "x"; },
        [](auto& v) { v.front().challenge_bytes += "x"; },
        [](auto& v) { v.front().acceptance_bytes += "x"; },
    };
    auto run = [](const Mutation& mutation, bool expected) {
        g_boot = 42;
        ManualClock clock;
        clock.set_monotonic_ms(1000);
        FixedRandomSource random{21};
        FakeCrypto crypto;
        FakeEvidence evidence;
        auto issuer = test_issuer();
        NodeTimeBootstrapManager manager(clock, random, crypto, evidence, fake_verify, issuer);
        assert(manager.on_boot(test_node(), test_incarn(), 42));
        evidence.during_append = [&]() {
            std::int64_t l, u;
            assert(!manager.current_interval(l, u)); // persist before trust
        };
        evidence.after_append = mutation;
        NodeTimeChallenge challenge;
        assert(manager.create_challenge(test_pubkey(), test_privkey(), challenge));
        g_nonce_b64 = base64url_encode(challenge.nonce);
        g_dpf_b64 = base64url_encode(challenge.fingerprint);
        clock.set_monotonic_ms(1300);
        std::string acceptance;
        assert(manager.submit_proof("valid-proof", test_pubkey(), test_privkey(), acceptance) == expected);
        std::int64_t l, u;
        assert(manager.current_interval(l, u) == expected);
        assert(acceptance.empty() != expected);
        assert((manager.last_anchor() != nullptr) == expected);
    };
    for (const auto& corrupt : corruptions) run(corrupt, false);
    run([](auto&) {}, true);
    run([](auto& v) {
        auto old = v.front();
        old.anchor_id.bytes[0] ^= 1;
        --old.boot_counter;
        old.utc_ms -= 10000;
        v.push_back(old); // current anchor is deliberately not back()
    }, true);
    run([](auto& v) {
        auto concurrent = v.front();
        concurrent.anchor_id.bytes[1] ^= 1;
        ++concurrent.uptime_ms;
        v.insert(v.begin(), concurrent);
    }, true);
}

void claim_uncertainty() {
    constexpr std::int64_t s = 1791748800000LL;
    assert(claim_timestamp(s, s) == static_cast<std::uint64_t>(s));
    assert(claim_timestamp(s, s + 5000) == static_cast<std::uint64_t>(s + 2500));
    assert(!claim_timestamp(s, s + 5001));
    assert(!claim_timestamp(s, s + kClaimFreshnessMs));
    assert(!claim_timestamp(0, 1));
    assert(!claim_timestamp(-1, std::numeric_limits<std::int64_t>::max()));
    assert(!claim_timestamp(s, s - 1));
    SecurityClock clock;
    clock.on_boot(test_node(), test_incarn(), 42);
    AcceptanceInterval anchor;
    assert(compute_acceptance_interval(s, 1000, 1000, 1300, 2, anchor));
    assert(clock.activate(anchor, 5000));
    std::int64_t l, u;
    assert(clock.current_interval(1300, true, l, u) && claim_timestamp(l, u));
    assert(clock.current_interval(301300, true, l, u) && !claim_timestamp(l, u));
    // Broad intervals may still cover a credential: no midpoint fallback.
    assert(!credential_covers(l / 1000 + 1, u / 1000 + 10, l, u));
    assert(!clock.current_interval(301299, true, l, u));
    clock.on_boot(test_node(), test_incarn(), 43);
    assert(!clock.current_interval(1300, true, l, u));
    // Valid but delayed proof is too imprecise for signing a claim.
    assert(compute_acceptance_interval(s, 1000, 1000, 4001, 2, anchor));
    assert(!claim_timestamp(anchor.lower_ms, anchor.upper_ms));
    assert(!compute_acceptance_interval(s, 1000, 1000, 31001, 2, anchor));
}

void security_clock_states() {
    SecurityClock security;
    assert(security.state() == SecurityTimeState::UNKNOWN);
    security.on_boot(test_node(), test_incarn(), 7);
    assert(security.state() == SecurityTimeState::UNKNOWN);
    NodeTimeChallenge pending;
    pending.node_id = test_node();
    pending.incarnation = test_incarn();
    pending.boot_counter = 7;
    pending.nonce.fill(9);
    pending.challenge_uptime_ms = 100;
    pending.device_public_key.fill(0);
    pending.device_public_key[0] = 4;
    pending.version = 1;
    assert(security.store_pending(pending));
    assert(security.state() == SecurityTimeState::CHALLENGE_PENDING);
    NodeTimeChallenge taken;
    assert(security.take_pending_for_verify(200, taken));
    assert(security.state() == SecurityTimeState::VERIFYING);
    AcceptanceInterval interval;
    assert(compute_acceptance_interval(1000000, 100, 100, 200, 0, interval));
    assert(security.activate(interval, 0));
    assert(security.state() == SecurityTimeState::BOUNDED);
    std::int64_t lower = 0, upper = 0;
    assert(security.current_interval(300, true, lower, upper));
    // Monotonic rollback invalidates.
    assert(!security.current_interval(299, true, lower, upper));
    assert(security.state() == SecurityTimeState::INVALIDATED);
    // Timer discontinuity invalidates as well.
    security.on_boot(test_node(), test_incarn(), 8);
    NodeTimeChallenge second = pending;
    second.boot_counter = 8;
    assert(security.store_pending(second));
    NodeTimeChallenge out;
    assert(security.take_pending_for_verify(200, out));
    assert(security.activate(interval, 0));
    assert(!security.current_interval(500, false, lower, upper));
    assert(security.state() == SecurityTimeState::INVALIDATED);
}

void evidence_trust_boundary() {
    // append_anchor still rejects verified anchors; only the verifier-owned
    // append_verified path accepts them.
    TimeAnchorV1 anchor;
    anchor.anchor_id = test_node();
    anchor.node_id = test_node();
    anchor.incarnation = test_incarn();
    anchor.boot_counter = 1;
    anchor.uptime_ms = 100;
    anchor.utc_ms = 1000000;
    anchor.source = AnchorSource::BACKEND_SIGNED;
    anchor.verified_security_time = true;
    anchor.proof_id_or_digest = "proof";
    anchor.proof = "proof-bytes";
    anchor.acceptance = "acceptance-bytes";
    assert(anchor.valid_verified() && !anchor.valid());
    FakeEvidence evidence;
    assert(evidence.append_anchor(anchor) == PutResult::IO_ERROR);
}

void failed_rebootstrap_preserves_trust() {
    // Regression for the re-bootstrap invalidation flaw: a failed proof for
    // a new pending challenge must not destroy a live anchor, nor may a
    // failed persistence attempt.
    g_boot = 42;
    ManualClock clock;
    clock.set_monotonic_ms(1000);
    FixedRandomSource random{21};
    FakeCrypto crypto;
    FakeEvidence evidence;
    auto issuer = test_issuer();
    NodeTimeConfig cfg;  // Zero drift keeps interval assertions exact here.
    cfg.drift_ppm_bound = 0;
    NodeTimeBootstrapManager manager(clock, random, crypto, evidence, fake_verify,
                                     std::span<const unsigned char, 65>(issuer), cfg);
    assert(manager.on_boot(test_node(), test_incarn(), 42));
    NodeTimeChallenge challenge;
    assert(manager.create_challenge(test_pubkey(), test_privkey(), challenge));
    g_nonce_b64 = base64url_encode(
        std::span<const std::uint8_t>(challenge.nonce.data(), 32));
    g_dpf_b64 = base64url_encode(
        std::span<const std::uint8_t>(challenge.fingerprint.data(), 32));
    clock.set_monotonic_ms(1300);
    std::string acceptance;
    assert(manager.submit_proof("valid-proof", test_pubkey(), test_privkey(), acceptance));
    assert(manager.state() == SecurityTimeState::BOUNDED);
    std::int64_t lower_before = 0, upper_before = 0;
    assert(manager.current_interval(lower_before, upper_before));
    // Bogus proof for a fresh challenge: denied, trust and interval intact.
    NodeTimeChallenge retry;
    assert(manager.create_challenge(test_pubkey(), test_privkey(), retry));
    clock.set_monotonic_ms(1400);
    std::string denied;
    assert(!manager.submit_proof("bogus-proof", test_pubkey(), test_privkey(), denied));
    assert(manager.state() == SecurityTimeState::BOUNDED);
    std::int64_t lower_after = 0, upper_after = 0;
    assert(manager.current_interval(lower_after, upper_after));
    assert(lower_after == lower_before + 100 && upper_after == upper_before + 100);
    assert(evidence.saved.size() == 1);
    // Persistence failure likewise preserves the live anchor.
    NodeTimeChallenge persist_retry;
    assert(manager.create_challenge(test_pubkey(), test_privkey(), persist_retry));
    g_nonce_b64 = base64url_encode(
        std::span<const std::uint8_t>(persist_retry.nonce.data(), 32));
    g_dpf_b64 = base64url_encode(
        std::span<const std::uint8_t>(persist_retry.fingerprint.data(), 32));
    evidence.fail_verified = true;
    clock.set_monotonic_ms(1500);
    std::string failed_accept;
    assert(!manager.submit_proof("valid-proof", test_pubkey(), test_privkey(),
                                 failed_accept));
    assert(manager.state() == SecurityTimeState::BOUNDED);
    assert(evidence.saved.size() == 1);
    std::int64_t lower_final = 0, upper_final = 0;
    assert(manager.current_interval(lower_final, upper_final));
}
}  // namespace

int main() {
    persisted_anchor_integrity();
    claim_uncertainty();
    interval_tv5();
    clock_advancement_and_drift();
    credential_boundaries();
    challenge_lifecycle();
    full_lifecycle_with_fake_verifier();
    security_clock_states();
    evidence_trust_boundary();
    failed_rebootstrap_preserves_trust();
    std::cout << "node-time portable security-clock tests passed\n";
    return 0;
}
