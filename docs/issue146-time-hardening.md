# Issue #146: bounded time/claim hardening

This supplements ADR 0017 and the existing claim receipt contract. No wire
format, trust pin, signature algorithm, JWT grace period or BLE limit changes.

## Persisted anchor activation

`submit_proof` retains the locally validated complete `TimeAnchorV1`, commits
through `append_verified`, then finds exactly one matching `anchor_id` in the
reloaded store. Defaulted record equality compares every field, including format,
Node/incarnation/boot, acceptance uptime, UTC, source, verified flag, uncertainty,
drift, proof identifier and exact proof/acceptance/challenge JSON and signed bytes.
Missing, duplicate or different records deny activation and acceptance export.
Store order is irrelevant. A previous independently verified live interval may
continue until its existing expiry; a failed new proof never replaces it.
Reboot still starts UNKNOWN; persisted evidence alone cannot restore trust.

## Normative claim receipt byte budget

Backend issuance and ESP32 verification both require compact JWS <=4096 ASCII
bytes, decoded header <=512 bytes, decoded JSON payload <=3072 UTF-8 bytes and
an ES256/P1363 signature of exactly 64 bytes. Actual serialized JSON counts,
including escaping; field character limits alone do not guarantee a usable receipt.

For unpadded base64url, B(n)=ceil(4*n/3). Compact size is
`B(headerBytes) + B(payloadBytes) + 2 + B(64)`.
The current exact issuer header is 44 bytes / 59 base64url characters; the
signature is 86 characters and separators take 2. Thus current receipt overhead
is 147 bytes, leaving at most 3949 base64url payload characters. The largest
representable payload is **2961 bytes**, yielding a **4095-byte JWS**. Adding one
JSON byte yields 2962 bytes / 3950 characters and a **4097-byte JWS**, rejected.
A 4096-byte JWS is not representable with this fixed header/signature shape.
Both independent caps remain checked for future serializer/header changes.
The existing 4096-byte BLE Claim assembly cap and collector delivery remain
unchanged: the token gets a 2-byte string length plus the 4-byte BLE frame
header (4102 bytes at the 4096 token cap), within the existing fragment
assembly limit. Fragment headers do not enlarge the assembled token budget.

Organization name/contact character limits remain 255/500 under existing backend
validation. ASCII, umlauts and supplementary Unicode use different UTF-8 budgets;
quotes/backslashes use JSON escapes and control characters can require six bytes.
For example, the maximum accepted Java UTF-16 lengths can contain 127/250 emoji
(and one additional single-unit name character); arbitrary maximum three-byte or
escaped text can exceed the byte budget. Such metadata is not claim-compatible:
the issuer refuses it and HTTP claim returns 422 **inside the ownership transaction**.
No truncated or unsigned replacement metadata is issued. Existing optional display
name omission is retained when required to fit; mandatory owner metadata is retained.
NUL escapes (including literal escaped text rejected by the current cJSON parser)
are refused too, preserving its fail-closed C-string rule. This budget predicate
is for locally signed output and is not a signature/authentication verifier.

`claim-receipt-budget-v1.json` is a backend-signed test-only fixture at the last
usable byte plus a receipt with maximum-length umlaut/emoji text. Java verifies
the committed signature and metadata; the exact ESP32 component verifies both.
Generated random signatures are written only to backend/target, never over the fixture.

## Claim timestamp uncertainty

The v1 advertisement signs a single UTC millisecond value and cannot transport
bounds without a separate protocol change. Only positive ordered bounds with
**full interval width <=5000 ms** may produce a claim timestamp. This is 1/60 of
the unchanged five-minute backend freshness window, limiting midpoint error to
2500 ms against either endpoint. At 5001 ms it fails closed. The threshold is a
conservative availability/security policy, not a measured physical accuracy claim.
Delayed proofs and accumulated configured drift can prevent signing even while
an interval still safely covers some credentials. Bootstrap again when required.

No point fallback is used for receipt/offline JWT validation: the complete
interval must satisfy L>=iat and U<exp. Monotonic rollback, timer discontinuity,
trust age and boot reset retain their existing invalidation. PHONE_ESTIMATE
observation evidence cannot initialize or modify SecurityClock. The pre-existing
explicit FIELD_ALPHA_PHONE_CLOCK build policy remains separate and unchanged.

## Integrated code/security review

Reviewed the full issue diff against #130/PR #142, ADR 0017, TimeAnchorV1 equality,
persistence adapters, NodeIdentityManager signing, BoardClock authorization,
claim transaction/issuer and exact cJSON/JWS verification. The review confirmed
that serialized NUL escapes must also be refused by the backend for compatibility
with the existing firmware parser; added a regression without relaxing that parser.
Tests cover every persisted field, missing/duplicate/reordered/stale records,
persist-before-trust, UTC width boundaries/drift/rollback/reboot/delay, UTF-8,
escaping, one-byte payload overflow, signed fixtures and claim transaction rollback.
This is a code/security self-review, not an independent third-party assessment.

Physical Android/ESP32 validation: **UNVERIFIED**. Server error/drift configuration
still needs operational calibration; point-only claims retain at most 2.5 seconds
of modeled error. Hardware E2E remains #134. Final ESP32-C3 development/production
images, release guard and dev-symbol checks are verified by the existing PR CI.
