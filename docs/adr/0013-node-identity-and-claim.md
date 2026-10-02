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
- Factory reset: `clear()` + new identity + new sequence lifetime. The old `node_id` is
  never reused with a reset sequence, so observation event identities cannot collide.
- Sequence is allocated by the core (`next_sequence()`) and persisted before use.

**Claim mode** is the `ClaimMode` port. The core never claims without `active() == true`.
Tests use `FakeClaimMode`; the real ESP32-C3 button handling belongs to #4. The backend
additionally requires a node-signed claim advertisement (canonical message + ECDSA
signature + 5-minute freshness), so a PWA flag alone cannot claim a node that is not
physically in claim mode.

**Claim flow** (backend, transactional):
1. Verify ACTIVE ADMIN membership for the target organization (server-side; client org id
   is never authority).
2. Verify the node's claim advertisement signature and freshness.
3. Atomically create/adopt the node row as `CLAIMED` with the organization, or return a
   fresh receipt for an idempotent retry of the same organization + key.
4. Reject (`409`) a `node_id` already claimed by another organization, or the same
   `node_id` with a different device key (factory reset required).
5. Issue a signed claim receipt (JWS/ES256, see ADR-0012) carrying organization id, owner
   display name, optional public contact and the issuer trust anchor.

No half-claimed state: the node row is created directly in `CLAIMED` state; retries are
idempotent.

## Foreign-organization owner hint

`GET /api/v1/nodes/{nodeId}/owner` (public, no auth) returns only: `nodeId`, `state`,
`organizationId`, `organizationSlug`, `organizationName`, `publicContact`. No
observations, chip IDs or pending counts before successful authorization. Owner data is
never broadcast in BLE advertisements.

## Cross-organization reuse

Not supported in the MVP. A node owned by organization A cannot be reassigned to B;
B's admin gets `409`. Reuse requires a factory reset (new identity). Historical data of
the old identity stays bound to the old organization.
