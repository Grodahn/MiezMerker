# Field-alpha authorization clock (#171)

Authoritative decision: [issue #171](https://github.com/Grodahn/MiezMerker/issues/171).
Physical ESP32-C3 / Android validation: **UNVERIFIED**. Host tests and cross-builds
do not establish on-device success. Final hardware E2E remains tracked by #134.

## Configuration and sequence

The default remains `SIGNED_BACKEND_TIME`. For field alpha, build firmware with
`SDKCONFIG_DEFAULTS=sdkconfig.defaults;sdkconfig.field-alpha` (or explicitly enable
`CONFIG_MM_FIELD_ALPHA_PHONE_CLOCK=y` in a fresh configuration). Set
`VITE_MM_SECURITY_CLOCK_POLICY=FIELD_ALPHA_PHONE_CLOCK` when building/serving the
PWA. Neither value belongs in production defaults. Keep the independently pinned
issuer public key configured. CI builds production, dev and field-alpha separately
and asserts the policy flag is absent from production/dev. Rebuild both clients
to return to signed time; no automatic policy downgrade exists.

After public identity/owner checks, capability `0x0080` selects the versioned
`AlphaPhoneClockRequest` (`0x25`, payload `u8=1, utc_ms:u64le`). The PWA samples
`Date.now()` immediately before sending it. Firmware keeps one provisional UTC
candidate in RAM, anchored to its current boot monotonic timer. It returns only
success/error (`0x26`, payload `u8=0`), never observations or chip IDs. The request
fits the minimum ATT payload and reuses ClaimReceipt's existing transport.

The existing challenge → pinned ES256 credential verification → org/scope/device
binding → P-256 PoP flow then grants authorization. PWA verifies Node authenticity
with its cached backend-pinned Node key before requesting protected data. Every
protected operation rechecks `iat <= candidate + elapsed < exp`. A time sample
alone permits no reads, ACK, COMPACT, Node session signature or claim. There is
one candidate per connection, including across renewed authentication challenges;
replacement is rejected. Timer rollback, invalid continuity and arithmetic
overflow latch denial. Disconnect, endpoint inactivity reset and reboot clear the
candidate and authorization. Fresh connections need new time and authentication.
An unauthenticated peer can spoil only its connection's candidate, not NVS or the
verified security clock. Existing transport inactivity and frame bounds apply.

This is separate from #169's authenticated read-only uptime snapshots and durable
`PHONE_ESTIMATE` observation evidence. Alpha samples never set observation time,
create verified anchors or rewrite raw observations. Signed evidence remains
immutable and transfers through its existing protected outbox/receipt path.
Observation persistence precedes ACK; raw compaction never substitutes for
evidence acknowledgement. #170's cooperative account/organization revocation
fences remain in force before and after asynchronous work.

Workshop registration uses the same candidate before requesting the Node-signed
claim advertisement. It still needs backend connectivity, ACTIVE ADMIN membership,
Node signature verification, server-side ownership checks and an issuer-signed
receipt bound to this Node/key/org. An already claimed Node cannot be taken over.
No separate backend UTC proof is requested. Receipt expiry/iat is checked against
the same alpha candidate; a skewed phone can cause rejection and needs correction
and a fresh connection, rather than an in-session clock reset.

## Accepted risks and compatibility

A former credential holder with the matching private key can deliberately
backdate the phone and bypass the nominal seven-day expiration. This includes
confidential reads and ACK/COMPACT, potentially deleting pending observations.
Per-session monotonic advancement prevents extending that connection by a later
wall-clock rollback; it cannot establish real UTC or prevent backdating a fresh
connection. Without a credential and matching key, time manipulation grants no
protected access. Confirmed backend revocation blocks and clears the cooperative
PWA (#170); a modified offline client cannot guarantee immediate revocation.

The backend still compares claim timestamps against its own five-minute window.
Under alpha, the Node signature proves key possession, not independent proof age
or live presence: future-dated signed advertisements can be stockpiled. The window
therefore limits submitted timestamps only. This reduced freshness guarantee is
accepted for workshop alpha; ADMIN, ownership, signature and receipt checks stay.

Legacy signed-time nodes keep their protocol and require signed bootstrap when
their trusted clock is unavailable. Policy mismatches fail explicitly. Existing
signed evidence is never deleted or relabeled as phone evidence. Re-enabling
signed time, stronger freshness and offline revocation need a later hardening
decision linked from #171/#134; alpha is not production-equivalent security.

## Reproducible physical acceptance (UNVERIFIED)

Use a real ESP32-C3, Android Chrome PWA, stable issuer pin, two organizations and
an ACTIVE ADMIN plus MEMBER. Record firmware/PWA versions, policy, Node UUID,
boot counter, test time and results. Keep synthetic reader use distinct from real
RFID validation. Never record private keys or credential tokens in logs.

1. Register an UNCLAIMED alpha Node online as ADMIN. Confirm there is no
   `/nodes/time-bootstrap` request, backend ownership is reserved, signed receipt
   commits, and public owner reflects it. MEMBER, inactive ADMIN, foreign owner,
   forged advertisement/receipt and duplicate foreign claim must be denied.
2. Cache a valid offline credential, matching installation key and Node public
   key. Capture observations, disconnect power fully, restart, then put Android
   offline while keeping Bluetooth on. Sync, verify Node authenticity, durable
   IndexedDB records and ACK, then repeat sync. No UTC service request is allowed.
   Check rebooted captures remain UNKNOWN on the Node; visit estimates remain
   visibly approximate PHONE_ESTIMATE.
3. Repeat with no credential, corrupted signature, another installation private
   key and a foreign-org credential using a test BLE harness. Try status, batch,
   snapshot, ACK, COMPACT and evidence operations: no protected data or mutation.
   Sending `0x25` alone must not change this.
4. Use an expired credential with honest phone UTC: auth and protected operations
   must fail. With the same credential/key deliberately backdate the phone inside
   its iat/exp interval: access may succeed. Record this as the accepted risk,
   including ability to ACK/COMPACT; never report it as secure expiry enforcement.
5. Leave a session open across expiry and verify the next protected operation
   fails. Attempt a second `0x25`: reject without changing the first candidate.
   Disconnect/reconnect and power-cycle; no old session/PoP may be reused. Use a
   host clock fixture for rollback/overflow since hardware timers are not settable.
6. Interrupt BLE before/after IndexedDB commit and ACK, restart the PWA and Node,
   and retry. Assert immutable event identity, no lost records, no premature ACK,
   idempotent replay and independent signed-evidence outbox recovery.
7. Online, confirm account or membership revocation (#170), then go offline.
   Cooperative PWA sync must remain blocked and cached credentials removed,
   including another tab and an in-flight operation.
8. Flash a clean production configuration and use the default PWA. Unsigned
   `0x25` must fail; a reboot with no trusted time must still require signed UTC.
   Also try default PWA + alpha Node and alpha PWA + production Node: explicit
   policy mismatch, no protected access or silent fallback.

## Review and automated evidence

Stage 0 mapped bootstrap ordering, connection DoS, sample replay, credential
replay, org isolation, monotonic rollback, reboot and persistence/ACK boundaries.
Focused tests exercise the real pinned verifier for honest expiry, deliberate
backdating, wrong key/signature/org and challenge replay; router tests check
pre-auth protection and default rejection. PWA tests cover offline retry,
Node authentication ordering, durable records/ACK, policy mismatch and workshop
claim without the UTC endpoint. Existing #169/#170, claiming, auth and sync
regressions remain part of local completion and final-head CI.


Integrated review (stage R) confirmed that the only new pre-auth write produces
no authority or durable evidence, all protected firmware consumers still enter
OfflineAuthSession validation, and the shared firmware mutex serializes samples,
auth, operations and disconnect. Factory reset closes the BLE session first.
Repairs removed a redundant registration retry branch, renamed board interval
queries to `authorization_interval_ms` to avoid labeling alpha time trusted, and
made policy mismatch errors actionable without asking alpha users for Internet.
Review also identified and documented the reduced stockpiled-claim freshness
contract. No signature, revocation, outbox or immutable-observation gate was
removed. No external independent security audit is claimed.
