# PR #25 review against issues #6 and #9

Reviewed the changes from `dcbcf36` to `3342dda`, including the BLE additions
that are outside the PR title's backend-only description. Fixes are applied
in the working tree on `feat/issue9-ingest-domain`.

## Findings fixed

| Priority | Finding | Fix |
| --- | --- | --- |
| P1 | Collector imported nonexistent codec functions and omitted `encodeFrame`. | Correct imports and null handling; TypeScript build passes. |
| P1 | Collector trusted a peer without checking its node private-key proof. | Require cached trusted backend identity and verify a fresh, single-use ES256 session proof before status or transfer. |
| P1 | Collector used node ID as incarnation, preventing records from advancing ACK. Lost ACKs and other collectors' ACKs were not reconciled. | Decode hello identity, validate every record against it, and start from the authenticated node's durable ACK. |
| P1 | Node accepted numeric ACKs across absent sequences. An unauthenticated guessed ACK could receive a watermark-bearing response. | Reject gaps in the node log and return only an authorization error before exposing ACK state. |
| P2 | Malformed pagination could loop indefinitely; mismatched ACKs and failed compaction could be reported as success. | Validate version, identity, page ordering/progress and ACK/compaction results; retain data on failure. |
| P2 | Clock status/time reflected the last observation rather than the current RTC. | Read the live clock, including after correction and with an empty store; stored observations remain immutable. |
| P1 | New ingest, observation lookup/filter, deployment creation and node update responses distinguished foreign IDs from absent ones. | Apply identical visibility responses. Node metadata updates also lock the node row to serialize with ingest/contact writes. |
| P2 | Deleting a referenced deployment produced a foreign-key failure instead of a documented API error. | Return 409 and preserve the deployment and frozen observation attribution. |
| P2 | Chip normalization could expand beyond the DB limit and break a batch. | Validate normalized length, reject only the invalid item, and use consistent normalization for filters. |
| P2 | Simulator sync commands bypassed the sync server; batch/status were placeholders. | Invoke the portable server, round-trip batch bytes through the codec, assert actual responses, and cover pruning failure/power loss. |
| P2 | Committed TypeScript API client drifted from the exported contract. | Regenerate with the repository generator; repeated contract checks pass. |

## Validation

- Frontend: 79 tests pass; TypeScript and Vite production build pass.
- Backend: complete 79-test suites pass on H2 and PostgreSQL 18.6.
- Firmware/simulator: all 40 CTest cases pass across the suite and the retry of
  the GATT case after a transient Windows runtime DLL loading failure.
- After the final node update fix: 27 affected tests pass on H2, and all 25
  issue #9 tests pass again on PostgreSQL.
- OpenAPI/client drift check passes twice. `git diff --check` passes.

Tests were first started after the initial fixes and regressions were written.
Subsequent runs validate test-discovered fixes and the final node update change.

## Remaining protocol limitation

A permanently missing reserved sequence blocks numeric ACK progress in v1.
Later records remain durably stored on both collector and node, and the collector
reports incomplete sync. Resolving such gaps requires an authenticated
tombstone/range protocol extension; the review does not silently acknowledge
missing records. Simulator authorization/signing ports remain deterministic
fakes; they are not production cryptographic implementations.

## Second review

The remote PR still points to `3342dda`; both review passes apply fixes locally.

| Priority | Additional finding | Fix |
| --- | --- | --- |
| P2 | Collector replaced malformed UTF-8 and stripped a leading BOM from chip IDs, then could ACK the altered identifier. | Reject malformed UTF-8 and preserve BOM bytes as identifier data. |
| P2 | A known-clock zero epoch passed core validation and encoding, but both v1 decoders rejected it, blocking sync. | Capture zero RTC readings as UNKNOWN and reject known-clock zero epochs in raw-record validation. |
| P2 | HTTP raw integer fields were JavaScript numbers; the protocol fixture's sequence `9007199254740993` rounded to `9007199254740992`. | Use decimal strings for raw 64-bit request/response fields and regenerate OpenAPI/client types; numeric input remains compatible. |
| P2 | Timestamp validation admitted values outside PostgreSQL's finite timestamp range, which could abort deployment lookup and the remaining batch. | Validate the finite range per item before lookup, including rejecting the zero epoch reserved by BLE v1; continue valid siblings. |

Regressions cover malformed/BOM identifiers, zero-RTC capture and codec
round-trips, precise large integers with an identical retry, and mixed batches
containing timestamps at and outside the PostgreSQL boundary.

Second-pass validation, after all source edits and API regeneration:

- 80 frontend tests pass; TypeScript and Vite production build pass.
- Full backend suite: 81 tests pass on H2.
- Affected backend ingest/API suites: 29 tests pass on PostgreSQL 18.6,
  including the last finite timestamp and first out-of-range millisecond.
- All 40 firmware/simulator CTest cases pass across the suite and the retry of
  the GATT case after the Windows runtime DLL loading failure. Its direct run
  also passes all 64 checks.
- API drift checks and `git diff --check` pass.
- The temporary PostgreSQL database and contract-export server are removed.

## Final review before merge

Two additional validation details are fixed: non-finite feeding-site coordinates
now return 400 before persistence, and the ingest-result schema permits a signed
decimal string when echoing a rejected negative sequence. Regressions cover
create/update rejection without changing a site and the INVALID sequence echo.

The permanent sequence-gap limitation above still applies. BLE v1 is a portable
protocol/server foundation; board GATT wiring and gap-recovery extensions remain
follow-up work. This PR completes the backend scope of #9, not all acceptance
criteria of #6.

Final local checks after edits and API regeneration: 30 affected backend/API
tests pass on H2, 80 frontend tests pass, TypeScript/Vite production build and
API drift checks pass, and both Playwright checks pass (offline IndexedDB
persistence and the real backend through the same-origin proxy). All 40
firmware/simulator CTest cases pass across the run and the GATT retry after
the Windows runtime DLL loading failure. The GitHub firmware failure's missing
`<memory>` include and application failure's generated-client drift are fixed.
Fresh CI on the pushed revision is required before merging.
