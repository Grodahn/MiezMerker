# Issue 131: signed time during normal synchronization

The existing sequential collector is used. After public identity/owner discovery
and credential resolution, capability `0x0020` enables the additive ADR 0017
time/evidence extension. Old required v1 capability bits and observation bytes
are unchanged. Existing RegistrationTimeChallenge/Proof operations now serve
both UNCLAIMED ADMIN registration and CLAIMED ACTIVE-member synchronization;
the existing registration wrapper retains its ADMIN gate. Backend and firmware
independently enforce identity, membership, signatures, issuer pinning, fresh
nonce, boot binding and the strict whole-interval credential guard.

An online visit obtains a fresh signed proof and requires explicit firmware
acceptance. The credential is obtained first; the same conservative scheduling
hint as registration lets the lower UTC bound advance past a new credential's
iat, without relaxing firmware checks. Reconnect retries may reuse the accepted
clock only after firmware reports it still usable. Reboot/invalidated time
requires a new challenge. When the time service is unavailable, only an already
usable firmware clock can proceed through ordinary authorization; unavailable
or rejected new time is never reported as accepted. Phone wall time grants no
security trust.

## Durable evidence and receipts

New verifier-owned anchors atomically retain the exact compact JWS, acceptance
JSON, challenge JSON and canonical signed challenge/acceptance bytes, plus
uncertainty metadata. NVS evidence layout v2 reads v1 without inventing absent
historical fields. Current clock activation still follows successful durable
anchor commit. The raw observation payload remains immutable.

Boot and anchor streams have separate bounded indexes and offset validation:
at most 128 boots, 16 anchors, 8192 envelope bytes, 480 data bytes per response.
They use authenticated Batch reads after credential/PoP and pinned NodeProof.
Indexes start at zero on reconnect; immutable identities, not offsets, are the
IndexedDB keys. UTF-8 and uint64 decimal representations are retained exactly.
Dexie v3 adds an organization-bound evidence outbox without changing existing
observation/upload tables. Available evidence is persisted before raw ACK.

Boot evidence and independently verified signed acceptance use the existing
backend APIs. Optional `nodeMetadata` retains firmware uncertainty as exact
collector-reported audit JSON; signatures and the backend's own calibrated
policy remain authoritative. Duplicate metadata must match exactly. Upload
confirmation must match the boot/anchor identity, status and format version.
Interrupted HTTP responses leave the outbox retryable. Logout/org changes abort
requests and prohibit late confirmation or authorization/ACK callbacks.

On a later authenticated visit, only backend-confirmed evidence receives its
own identity-bound receipt (`0x21`/`0x22`), before enumerating the next evidence
snapshot. Removal is atomic and idempotent across lost responses/power loss.
Current boot markers and markers referenced by unreceipted anchors are retained.
Raw ACK/Compact never deletes evidence. Local evidence is also retained after
backend/node receipt. Until backend confirmation, the Node retains its copy.
The unchanged 20,000-byte NVS bound remains fail-closed when downstream delivery
cannot progress; no eviction of unconfirmed evidence or raw records is allowed.

## Existing contract gaps / completion limits

- Firmware v1 anchors did not retain the signed challenge. Their exact remaining
  proof/acceptance is preserved on Node/PWA, but an absent signature cannot be
  reconstructed or safely ingested under the strict #132 evidence contract.
- #132 deliberately rejects proofs issued before `Node.claimedAt`, because the
  existing claim persistence does not retain an incarnation/monotonic receipt
  chain. This also applies to newly complete registration anchors. Their outbox
  remains pending with an honest upload failure; they are not receipted/pruned.
  Normal post-claim anchors upload independently and still support the same boot.
  Resolving the pre-claim continuity contract is outside this transport change;
  the tenant/continuity rejection and its security tests remain unchanged.
- Bounded storage can fill if these legacy/unconfirmed anchors accumulate or
  if uploads remain unavailable. A full store refuses bootstrap/capture according
  to the existing contract. Do not factory-reset or erase data as an automatic
  recovery procedure. Export/resolve evidence before any intentional reset.

Consequently this change must not be described as complete durable backend
synchronization of **all historical/registration evidence**. New normal-sync
post-claim evidence has the complete end-to-end durable path.

## Android / ESP32-C3 manual acceptance — UNVERIFIED

All cases below require a real ESP32-C3 and Android Chrome. Host CTest, Vitest and
Playwright mocks do not prove radio, NVS power-failure behavior or oscillator ppm.
Use a dedicated test node and preserve its existing NVS/partition layout.

1. Use the existing #157 stable issuer setup/UTC attestation. Compare the backend
   public issuer with `CONFIG_MM_ISSUER_PUBLIC_KEY_HEX` (130 hex characters,
   uncompressed P-256) in the node's configuration. Do not deliver a new trust
   key over BLE. In an activated ESP-IDF 6.1 terminal, follow the production or
   explicit GPIO3 development build commands in `firmware-esp32/README.md`,
   including the verified physical USB port. Build/flash this branch without
   `erase_flash`; preserve the partition offsets and existing identity.
2. Open the configured HTTPS PWA in Android Chrome, authenticate as an ACTIVE
   member of the node's organization and explicitly grant the node in the
   browser chooser. For GPIO3 use the documented development build only;
   an UNCLAIMED node must record zero reads. Complete ADMIN claiming first.
3. Power-cycle the CLAIMED node. Inject one documented GPIO3/read event before
   syncing. Confirm raw `clock=UNKNOWN`, boot counter and monotonic timestamp.
   Tap **Näpfe auslesen** online. Verify the UI first reports accepted signed
   UTC, then durable local evidence/raw transfer, then independent backend
   upload. Confirm a time-evidence receipt with matching anchor ID and persisted
   canonical bytes. The original UNKNOWN raw row must remain UNKNOWN/null.
4. Interrupt Bluetooth (a) before proof delivery, (b) after firmware acceptance,
   (c) during evidence fragments and (d) after raw ACK/before compaction. Reconnect
   through previously authorized devices and retry. There must be no early raw
   ACK after a persistence failure, no conflicting/replaced anchor and no lost
   installed proof. Stop the browser during HTTP upload and reopen: a repeated
   upload must return CREATED or DUPLICATE_IDENTICAL for the same exact bytes.
5. After a confirmed backend upload, sync again and inspect independent evidence
   receipts. Power-cut immediately before/after the NVS receipt write; reopen
   and retry. An absent anchor is allowed only after backend-confirmed durable
   delivery. The current boot marker must remain, and raw Compact must not prune
   unreceipted evidence. Repeat across at least 40 visits/boot epochs to prove
   confirmed receipts reclaim space rather than exhausting the anchor count.
6. Disable internet while the current security clock is usable: normal PoP may
   still succeed and upload waits. Then power-cycle offline: protected sync must
   fail with retry guidance while CLAIMED raw capture continues UNKNOWN. Restore
   internet. Repeat with two power losses and reads in the intervening boot:
   upload separate boot markers, preserve uptime ordering and do not invent the
   intervening boot's exact UTC or historical FeedingSite.
7. Set the Android date far backward/forward: it must not authorize an otherwise
   untrusted node. With a controlled test transport, deliver a modified proof,
   a proof from another node/boot, a consumed nonce, an expired challenge and a
   proof signed by another issuer: firmware must reject each without success UI.
   Restore the real signer without weakening expiry/pinning assertions.
8. Switch organization or log out during the backend time request, during evidence
   receipt and during HTTP upload. Verify no subsequent PoP, raw ACK, evidence
   deletion or late confirmation occurs under the old operation. Re-login and
   retry under the original organization; exact pending payloads must survive.
9. Grant two nodes explicitly. Make the first unreachable or reject its proof.
   Start the sequential batch without reopening the chooser. The second authorized
   node must still run; a foreign node must disclose no protected evidence/data.

Record firmware/backend revisions, issuer fingerprint, boot/anchor IDs, Android
and Chrome versions, serial windows, HTTP receipts and before/after durable
counts for every physical case. Mark cases individually PASS/FAIL/UNVERIFIED.
