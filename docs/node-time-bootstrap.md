# Node time bootstrap (#129)

ADR 0017 is the normative byte/trust contract. This implements only the authenticated
backend issuer and a reusable acceptance verifier; it does not install time, claim a
Node, persist anchors, or authorize observation access.

## HTTP contract

`POST /api/v1/nodes/time-bootstrap`, JSON, existing session cookie and
`X-XSRF-TOKEN` (obtain from `/api/v1/auth/csrf`). Maximum body: 4096 bytes,
including chunked bodies. Duplicate/unknown fields, trailing JSON, numeric strings,
fractional integers, unsupported versions and noncanonical encodings are rejected.

Request:

```json
{
  "organizationId": "22222222-2222-4222-8222-222222222222",
  "registrationState": "UNCLAIMED",
  "challenge": {
    "nodeId": "44444444-4444-4444-8444-444444444444",
    "incarnation": "55555555-5555-4555-8555-555555555555",
    "bootCounter": 42,
    "challengeNonce": "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8",
    "challengeUptimeMs": 1000,
    "devicePublicKey": "<base64url 65-byte uncompressed P-256 point>",
    "devicePublicKeyFingerprint": "<base64url SHA-256(point)>",
    "deviceChallengeSignature": "<base64url raw 64-byte r||s>",
    "version": 1
  }
}
```

Response: `{proof, tokenType:"Node-Time-ES256-JWS", issuer:"miezmerker",
keyId:"miezmerker-issuer-v1", version:1}`, with `Cache-Control: no-store`.
Retain `proof` as the exact compact JWS string for installation evidence.
Transport metadata is informational; independently pinned trust governs verification.
Signed claims are exactly `iss,kid,kind,ver,node,incarnation,boot,nonce,dpf,utc_ms,jti`.
Protected header is exactly `alg:ES256,kid:miezmerker-issuer-v1`.
`kind=node-time-bootstrap` is the time-proof domain, distinct from `offline` and
`claim`; there are no authorization scopes, roles or credential validity claims.

All UUIDs use canonical lowercase UUIDv4 strings over HTTP; signing uses RFC 4122
binary UUID bytes. All binary strings use unpadded canonical base64url. Boot is
1..4294967295; uptime is an unsigned 64-bit integer, 0..18446744073709551615.
Adapters must preserve unsigned uptime with BigInt/lossless JSON handling, never
silently round values through JavaScript Number. UTC is a positive signed 64-bit
integer in milliseconds, sampled immediately before synchronous signing after
validation and database lock waits. The client cannot supply UTC or signer settings.

Errors: 400 malformed challenge/schema, 401 unauthenticated (valid CSRF required),
403 invalid CSRF, disabled account/tenant, authorization or signature denial,
413 body too large, 429 rate limit, 503 clock/key unavailable. Ownership/key failures
share a generic denial without foreign organization or device key metadata.
Standard security-filter ordering may reject authentication/CSRF before body checks.

## Authorization and claim races

CLAIMED requires ACTIVE user/membership/organization in the actual owning organization
and the exact registered Node key and fingerprint. A submitted organization ID or
global SYSADMIN privilege grants no membership. UNCLAIMED requires ACTIVE ADMIN in
the intended organization and a valid self-signed device challenge. Existing owned
IDs cannot use the UNCLAIMED path, including same-owner retries; a preregistered
UNCLAIMED row must have the matching key. A new ID cannot reuse a registered key.

Ownership lookup and signing hold the existing database claim lock until the
transaction commits. This includes absent IDs and serializes with #18 claiming
across backend instances. If claiming wins first, bootstrap rechecks the committed
owner/state; if bootstrap wins, its proof establishes possession only and reserves
no ownership. Signed physical claim-mode advertisement and all existing #18 gates
remain mandatory. No database migration or Node/Deployment/Cat/observation write
is introduced.

## Freshness, retries and limits

Self-signed unclaimed boot counters and uptime do not prove physical freshness or
ownership. The backend cannot measure challenge age from a phone clock. No backend
nonce cache or second boot authority is introduced. Identical valid retries may
receive a new proof with a new UUIDv4 `jti` and current server UTC, after full
authorization and ownership checks each time. Every attempt consumes rate budget.
The Node must accept only its pending nonce, current boot/incarnation and pinned
key, once, within its short monotonic deadline (#130). Issuance UTC is **not** exact
acceptance UTC; latency and clock uncertainty follow ADR 0017.

Per-process monotonic fixed 60-second windows: user 30 attempts (before body
parsing, including malformed/invalid requests), organization 120, attempted Node
10, successful pre-claim authorization 5 per user (including unhealthy-clock
attempts).
Shared organization/Node budgets are charged only after membership, possession,
ownership/state and registered-key checks, so foreign/invalid identity attempts
cannot exhaust a victim's budget. They still consume the pre-parse user limit.
Counters are synchronized, bounded to 10,000 entries shared across
scopes, saturate without overflow, and reject new identities at capacity until
windows expire. No secrets or challenge/proof bodies are logged. Limits are per
replica and reset at process restart: multi-instance deployments must enforce
aggregate limits and connection/body deadlines at the gateway. No distributed
rate-limit platform is added. Request processing reads at most 4097 bytes; gateway
timeouts/concurrency limits are still required against slow request streams.

## Issuer trust and clock operation

Reuse the existing configured P-256 issuer pair. Time issuance refuses the existing
local/test ephemeral-key fallback. Configure `ISSUER_PRIVATE_PKCS8_B64` and
`ISSUER_PUBLIC_SPKI_B64`, validated as a matching P-256 pair. Firmware must already
trust this exact public key, issuer and key ID independently. An issuer endpoint,
claim receipt or PWA-supplied key must never automatically replace that pin.
Rotation requires coordinated firmware trust updates, as for existing credentials.

`BootstrapTimeSource` uses `Clock.systemUTC()` in production, with injectable
`Clock` for deterministic tests. Issuance defaults disabled:
`miezmerker.time-bootstrap.clock-healthy=false`. Trusted operational monitoring
must enable issuance only while synchronization meets the installation's clock
accuracy budget. `reportHealthy(boolean)` is the narrow in-process integration
point; there is no public health-setting endpoint. A static deployment property
is an operator attestation, **not** live NTP monitoring. Before production enablement,
connect a trusted monitor to revoke health on synchronization failure, or stop/
restart issuance with the property false. No NTP signal is fabricated from successful
clock reads and no external time server is introduced. A signature proves what
the backend asserted; it cannot prove the server's physical clock accuracy.

## Acceptance evidence and downstream dependencies

`NodeTimeProofVerifier.verifyAcceptance(proof, challenge, acceptance, trustedDeviceKey)`
verifies the original device challenge signature to authenticate its uptime, the
exact proof, and ADR 0017 TIME-ACCEPT bytes, including identity,
incarnation, boot, nonce digest, exact compact JWS digest, unsigned acceptance
uptime and UUID anchor. Acceptance uptime must be at least challenge uptime.
Signature uses raw P1363 P-256/SHA-256. The caller MUST pass the registered or
subsequently physically claimed device key, never blindly trust a PWA key. The
primitive verifies evidence only; it does not infer actual device clock accuracy
or authorize anchor storage. The signed proof alone is not installation evidence.

- #130: firmware proof verification, pending single-use nonce, monotonic deadline,
  conservative clock interval and durable anchor/acceptance signature.
- #131: bounded BLE transport and PWA forwarding of exact proof/evidence bytes;
  unsigned integer preservation and separate durable anchor ACK lifecycle.
- #132: registered-key authorization, immutable evidence storage, temporal
  reconstruction and uncertainty policies. No anchor persistence is implemented here.

`protocol/fixtures/node-time-v1.json` contains independent Python-generated
challenge and acceptance canonical hex, P1363 signatures, issuer verification
key, exact JWS, expected UTC `1791748800123`, and signed negative variants.
Java tests verify committed bytes without rewriting them. #130 uses the hex/keys/
signatures directly with mbed TLS; #131 uses Web Crypto verification and transports
the exact JWS. The explicit `generate_node_time.py` utility uses public test scalars
1/2, never production secrets; regenerating ECDSA examples changes signatures and
the corresponding proof digest, so fixture updates require protocol review.
