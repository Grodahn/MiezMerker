# Issue #129 independent security and code review

Date: 2026-10-11. A distinct review agent/context reviewed the complete backend
change, issuer reuse, existing security/tenant/claiming dependencies, ADR 0017,
tests, independently generated fixture bytes, API artifacts and deployment docs.
The reviewer did not implement changes or run the implementation agent's tests.
This is an independent agent review, not a third-party certification or hardware
validation. No unresolved known in-scope BLOCKER or MAJOR findings remain.

## Confirmed findings and repairs

| Severity | Finding | Repair and regression |
| --- | --- | --- |
| MAJOR | Shared Node/organization budgets were charged before tenant/state/key authorization, permitting authenticated foreign identity attempts to deny legitimate issuance. | Charge shared budgets only after all membership, possession, ownership/state and registered-key checks. Keep malformed/invalid attempts under pre-parse user limits. `unauthorizedIdentityAttemptsCannotExhaustVictimNodeOrOrganizationBudget` checks foreign organization, no membership, invalid signature and self-consistent substituted key, followed by successful owner issuance. |
| MAJOR | Acceptance verification did not authenticate the original challenge uptime. A PWA could increase it to acceptance uptime, understating the latency/uncertainty bound. | Verify the original challenge signature against the challenge key after its fingerprint matches the independently trusted device key. `acceptanceRequiresExactProofKeyAndAllInstallationBindings` rejects the golden proof/acceptance with altered challenge uptime. |
| MAJOR | Springdoc exported BigInteger uptime as string, inconsistent with strict numeric JSON parsing; its uint64 maximum was rounded by JavaScript contract generation. | Force numeric schema implementation, retain exact uint64 bound in its description and runtime validation, regenerate OpenAPI/types. `openApiMatchesNumericLosslessUptimeAndStrictRequestContract` asserts live integer schema, uint64 format, exact bound description and strict required fields. |

The implementation agent fixed every confirmed finding and the independent
reviewer reviewed the corrections. Focused repair tests passed; final node/auth
domain regression and GitHub CI evidence are reported in the PR.

## Integrated scope and security checks

- No general signing endpoint, caller time/alg/key/purpose, shared organization
  secret, production private key fixture or secret-bearing application log.
- Existing ES256 issuer infrastructure reused with fixed time-proof kind and
  exact payload, stable configured key required, independent firmware pin required.
  Time proofs have no credential/claim authorization fields or validity interval.
- ACTIVE user/organization/membership enforced; pre-claim ADMIN gate and registered
  key binding preserved; SYSADMIN does not bypass membership. Foreign identity
  denials return no owning organization/key metadata.
- Shared database claim lock protects absent-row and registered-row issuance;
  concurrent foreign claiming regression verifies committed ownership is rechecked.
  Bootstrap writes no Node/Deployment/Cat/observation and grants no ownership or observation access (#154 removes the physical registration gate).
- Strict JSON fields, exact P-256 point/fingerprint, canonical base64url/P1363,
  binary UUID and little-endian fixed-width byte contracts; uint64 uptime preserved.
- Proof verification restricts issuer, key ID, ES256, version and kind; acceptance
  binds trusted device key, signed original challenge, exact JWS digest, nonce,
  boot/incarnation, acceptance uptime and anchor ID.
- Bounded request read and synchronized monotonic counter map, no unbounded replay
  cache. Retries reauthorize and create a new proof ID; firmware single-use/deadline
  and cross-boot rejection remain #130 responsibilities.
- Injected UTC Clock and integer milliseconds; operational health defaults false,
  no fabricated NTP signal or cryptographic physical-time-accuracy claim.

## Remaining integration boundaries

Production needs trusted live clock-health monitoring and adequate clock
synchronization, plus gateway aggregate limits, timeouts and concurrency guards
across replicas. The public issuer endpoint/PWA/claim receipt cannot install a new
trust pin. Firmware trust clock (#130), BLE/PWA forwarding (#131) and authorized
anchor persistence/reconstruction (#132) remain separate work. Hardware E2E is
UNVERIFIED and belongs to #134.
