# 0013 — Node identity, claim mode and provisioning lifecycle
Status: Accepted (implements #18).

## Context

#18 requires a persistent node identity that survives reboot/power loss/firmware update,
a physical claim-mode gate, atomic claim with deterministic retry, and public owner
metadata for foreign organizations.

## Decision

**Node identity = random UUIDv4 `node_id` + opaque P-256 device key pair + monotonic
sequence**, persisted via the `NodeIdentityStore` port. Never BLE MAC, ESP MAC, database
PK or FeedingSite ID.

- Reboot/power loss/firmware update: identity and sequence are reloaded unchanged.
- Factory reset: atomic replacement with a new identity + new sequence lifetime. The old `node_id` is
  never reused with a reset sequence, so observation event identities cannot collide.
- Sequence is allocated by the core (`next_sequence()`) and persisted before use.
  Failed writes return the reserved failure value `0` and disable use until reload;
  exhaustion also fails without wrapping. Store errors are distinct from first boot.
- `NodeCrypto` is a required adapter for P-256 key generation/signing and pinned-key
  claim verification. The core does not invent unrelated random public/private bytes.
  `NodeIdentityStore` serializes the full record and replaces it atomically; the C++
  object contains strings and must not be stored using raw `memcpy`.

**Claim mode** is the `ClaimMode` port. The core never claims without `active() == true`.
Tests use `FakeClaimMode`; the real ESP32-C3 button handling belongs to #4. The backend
additionally requires a node-signed claim advertisement (canonical message + ECDSA
signature + 5-minute freshness), so a PWA flag alone cannot claim a node that is not
physically in claim mode.

The signing API returns a `ClaimAdvertisement` with a timestamp sampled from the
node's trusted `ClaimClock`; the BLE caller cannot choose that timestamp. Otherwise
a peer could stockpile future-dated signatures during one physical window and submit
them long after the window closed. An unavailable clock (`0`) fails closed. The clock
adapter must obtain current epoch time independently of untrusted peer input.

**Claim flow** (backend, transactional):
1. Verify ACTIVE ADMIN membership for the target organization (server-side; client org id
   is never authority).
2. Verify the node's claim advertisement signature and freshness. For a retry of an
   already committed same-owner/same-key claim, still verify the signature but allow
   the original proof after its freshness window, enabling recovery after lost delivery.
3. Atomically create/adopt the node row as `CLAIMED` with the organization, or return a
   fresh receipt for an idempotent retry of the same organization + key.
4. Reject (`409`) a `node_id` already claimed by another organization, or the same
   `node_id` with a different device key (factory reset required).
5. Issue a signed claim receipt (JWS/ES256, see ADR-0012) carrying organization id, owner
   display name, optional public contact and the issuer trust anchor.

The backend reserves ownership in its transaction. A fixed database lock row serializes
lookup and creation/adoption across backend instances, including initially absent nodes.
Concurrent claims have one winner; a loser cannot overwrite ownership.

The node calls `apply_claim(receipt)`. The crypto adapter verifies ES256 with a backend
key pinned independently of the incoming receipt, plus issuer/kind/version/expiry.
The core checks node ID and public-key binding, requires physical claim mode for the
first commit, and atomically stores organization, owner metadata and the signed trust
context together with the identity. Same-owner receipt delivery is idempotent after
commit; another organization is rejected. If transport fails between backend reservation
and node commit, repeat receipt retrieval and delivery (reactivate physical claim mode
if necessary). There is no distributed atomic transaction across backend and BLE.

## Later node authenticity

`sign_session_challenge` signs `MM-NODE-SESSION-v1\n{node_id}\n{base64url_nonce}` with
the persisted device private key, only after claim. Session domain separation prevents
the session endpoint being used to sign a claim proof. The PWA's
`beginNodeAuthentication` uses a random 32-byte nonce, a 30-second validity window and
a single-use verifier. Expiry uses a monotonic clock and is checked before and after
signature verification, so wall-clock rollback cannot extend the window.
`loadTrustedNodeIdentity` obtains the persisted key from the tenant-authorized node
record, checks the identity/state and fails on denied or incomplete responses.
The public owner endpoint continues to expose only the permitted owner metadata.
Its public key must come from the authenticated backend or an
already verified receipt, never from the peer under test. Tests use real Web Crypto
P-256 signatures and reject another key, copied IDs, stale signatures and replay.

ESP32 crypto/flash/button adapters (#4) and GATT wiring (#6) remain required before
hardware use. Domain tests use explicitly fake adapters and do not establish hardware
cryptographic correctness or end-to-end protected GATT operation authorization.

## Foreign-organization owner hint

`GET /api/v1/nodes/{nodeId}/owner` (public, no auth) returns only: `nodeId`, `state`,
`organizationId`, `organizationSlug`, `organizationName`, `publicContact`. No
observations, chip IDs or pending counts before successful authorization. Owner data is
never broadcast in BLE advertisements.

## Cross-organization reuse

Not supported in the MVP. A node owned by organization A cannot be reassigned to B;
B's admin gets `409`. Reuse requires a factory reset (new identity). Historical data of
the old identity stays bound to the old organization.
