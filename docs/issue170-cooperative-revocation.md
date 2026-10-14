# #170: cooperative PWA account and organization revocation

The backend remains authoritative. An authenticated `GET /api/v1/auth/session`
returns `401` with `X-MM-Auth-Status: ACCOUNT_DISABLED` and the authenticated
`X-MM-User-Id` only for a disabled account. Missing/expired sessions and failed
logins remain generic authentication failures. The session response is not cached.
Existing fresh account, membership, organization, admin and tenant gates remain
in force for backend operations, including credential issuance and uploads.

The PWA reconciles on startup, reconnection and business API status refreshes.
Successful business exchanges coalesce status refreshes to once per minute;
401/403 business denials trigger a status exchange immediately, never direct
revocation. Credential renewal is attempted only under a verified session.
Network loss, timeouts, 5xx, incomplete responses and generic 401 preserve valid
offline access and do not display a deactivation message.

Explicit account revocation removes local authorization before waiting for
credential cleanup. A durable account fence invalidates every credential of
that account. A confirmed membership list removes only credentials belonging
to unavailable organizations, including organizations filtered out by the
backend because they are inactive. Other active organizations retain their
credentials. The existing credential database uses account and organization
invalidation generations to serialize cleanup against issuance. Durable fence
generations also reject older credentials if a deletion temporarily fails.
Reactivation requires a new successful backend session/login; it does not make
pre-revocation credentials usable again.

Status commits and authentication changes use Web Locks, with an IndexedDB
transaction fallback. Shared epochs reject responses started before another
tab's status/authentication change. Storage events only reduce permissions;
they never authenticate another tab. Existing BLE operation and upload context
subscriptions cancel revoked operations. An already sent BLE/HTTP request cannot
be recalled; subsequent authorization, reads, writes, ACKs and uploads are blocked.

No revocation path deletes the collector database. Observations, raw upload
envelopes, sync metadata, boot evidence and time evidence remain stored. New
observations and evidence carry a local `ownerUserId`, which is never a backend
or BLE authority field. Production collector stores require the original user
and a current active membership to read their outbox. Another account cannot
adopt or overwrite these records even with membership in the same organization.
The original user can resume processing after renewed backend authorization.

The collector v4 migration preserves known ownership from the original offline
session snapshot before revocation clears that snapshot. Legacy records with
unknown ownership stay quarantined for explicit recovery; they are never assigned
to the next account logging in. This is a deliberate preservation boundary.

## Integration boundaries and residual FIELD_ALPHA risks

The only change shared with #169/#171 evidence storage is local account
provenance and filtering. Signed evidence bytes, time anchors, reconstruction,
clock policy, JWT/issuer trust, device identity, proof of possession, credential
expiration and BLE challenge-response semantics remain unchanged. No firmware
change or physical protocol change is part of #170. #134's comprehensive final
acceptance remains a separate issue.

A disconnected installation cannot learn revocation. A modified client can
bypass cooperative UI logic, and already issued credentials remain signed and
valid at the Node until their existing expiration/time policy rejects them.
This is neither remote wiping nor guaranteed remote credential revocation.
Cross-tab propagation depends on browser storage facilities; installations do
not receive revocation while disconnected. Persistently unavailable/corrupted
storage requires recovery and must not be represented as successful data cleanup.

## Verification

Focused auth/credential/storage regressions cover explicit account deactivation,
org removal/disable/inactivity, generic 401, backend failures, incomplete replies,
reload, reactivation, status/account-switch races, credential issuance races,
BLE cancellation, durable record preservation, ownership and legacy migration.
Browser acceptance exercises actual shared tabs with a stale ACTIVE response,
offline reload, session timeout and offline credential expiry. Backend auth-domain
regression verifies current server-side account and tenant enforcement.
