# BLE sync v1 — security & provisioning

Reuses #17 (ADR-0012) and #18 (ADR-0013) unchanged. No shared organization
secret, no custom crypto, no OS pairing requirement, no second credential
format.

## Trust recap

- Backend login/session (email/password, cookies, CSRF) and offline BLE
  authorization are separate mechanisms. The session cookie is never sent to
  the node.
- Each PWA installation owns a P-256 AppDevice key pair (Web Crypto,
  non-exportable where supported), registered as `AppDevice` with
  `publicKeyX/Y`. One user may own several devices; each is revocable alone.
- Backend issues a **signed, time-limited, org- and device-bound offline
  credential** (JWS compact, ES256). The node holds the issuer trust anchor
  and verifies fully offline.
- The node owns a persistent P-256 device key + random UUIDv4 `node_id`
  (never MAC/DB/site id). After claim it proves possession per session.

## Credential / signature byte format (ADR-0012)

- JWS compact `header.payload.signature`, `alg=ES256`,
  `kid=miezmerker-issuer-v1`. No `crit`/`b64` extensions.
- `x`/`y`: 32 B big-endian, base64url no padding (43 chars).
- Fingerprint: `base64url(SHA-256(0x04 || x || y))` (43 chars).
- Challenge signature: ECDSA P-256/SHA-256 over the raw 32 B challenge, raw
  64 B `r||s`, base64url on JSON surfaces, raw bytes on GATT.
  Java JCA DER ↔ raw conversion lives at the boundary (`EcKeyUtils`);
  Web Crypto emits raw; the node feeds raw `r`/`s` into
  `mbedtls_ecdsa_verify` (DER helpers only inside mbed-TLS serialization).
- Credential claims: `iss=miezmerker, sub=userId, org, org_slug,
  dev=deviceId, dpk_x, dpk_y, dpf, role=ADMIN|MEMBER, scope=["node:sync"],
  iat, exp, jti, ver=1, kind="offline"`.
- Claim receipt claims: `sub=nodeId, org, org_slug, org_name, contact,
  ndpk_x/y, ndpf, ipk_x/y, iat, exp (10 y), kind="claim"`.
- Interop vectors: `protocol/fixtures/offline-credential-v1.json` (fixed
  keys, JWT, challenge + signature, claim advertisement). ECDSA signatures
  are single valid examples; verification is deterministic.

## Challenge / response (per connection)

```text
PWA                          Node
─── read Challenge ─────────▶  begin(CSPRNG) → 32 B nonce, pending=true, auth cleared
◀── 32 B nonce ─────────────
─── write Auth(JWT, ───────▶  authorize(JWT, proof, trusted_utc):
     sign(challenge))            consume nonce (any outcome) →
                                 verify JWS w/ pinned issuer key →
                                 check kind/ver/iss/org/role/scope/iat/exp →
                                 check org == claimed org →
                                 check device key binding + fingerprint →
                                 verify proof over exact challenge bytes →
                                 set expires/issued, return ok/failed
```

- Fresh 32 B challenge per connection from the board CSPRNG (never
  client-chosen). Every attempt consumes it; retry requires a new read.
- Copied credential without the AppDevice private key fails at proof verify.
- Replay of an old challenge fails (nonce mismatch / already consumed).
- Expiry uses trusted UTC seconds; `now <= 0` (unknown clock), `now >= exp`,
  future `iat`, or `iat >= exp` fail closed. PWA-supplied time is never
  authority. Long connections re-check `can_sync` on every protected op.
- DISABLED membership / revoked device blocks **new** issuance immediately;
  already-issued credentials stay valid until `exp` (default 168 h/7 d,
  configurable `miezmerker.credential.ttl-hours`). No instant offline
  revocation in the MVP — documented in ADR-0012.

## Node authenticity (#18)

After authorization the PWA may challenge the node:

```text
PWA generates 32 B nonce → write NodeProofRequest
Node signs MM-NODE-SESSION-v1\n{node_id}\n{base64url_nonce} with device key
PWA verifies with backend-pinned node key, 30 s single-use window
```

Domain separation prevents session signatures from doubling as claim proofs.
Public key comes from the authenticated backend (`GET /nodes/{id}`) or an
already verified receipt, never from the peer under test. Fake nodes without
the device key fail here.

## Claim state / owner metadata

- `NodeIdentityManager` persists `node_id` + P-256 key + sequence + org +
  owner hint + receipt atomically. `UNCLAIMED` → `CLAIMED` only via
  `apply_claim` (backend receipt verified against the pinned issuer key,
  node-ID/key binding, physical `ClaimMode` for first commit). Same-owner
  redelivery is idempotent; other-org claim is rejected (`409` backend,
  `FORBIDDEN_FOREIGN` on GATT). Reuse across orgs requires factory reset
  (new identity, old data stays with the old org).
- Public `Owner` characteristic (after GATT connect, **before**
  authorization) exposes only: `nodeId, state, organizationId,
  organizationSlug, organizationName, publicContact`. No observations, chip
  IDs or pending counts. Mirrors backend `GET /nodes/{id}/owner` (public).
- `UNCLAIMED` nodes expose empty org fields; sync ops return `INVALID_STATE`.
- Owner data is never broadcast in advertisements.

## Foreign organization behavior

- Credential org ≠ node org → `AuthResponse{ok=0, FORBIDDEN_FOREIGN}`,
  session stays unauthorized. All protected reads/writes continue to return
  `UNAUTHORIZED`/`FORBIDDEN_FOREIGN` with no data.
- PWA displays `Dieser MiezMerker gehört Organisation X` + optional public
  contact from the public owner hint. It must not probe for data presence.
- Tests: same-org MEMBER/ADMIN succeed; foreign credential, expired/forged
  credential, credential without matching private key, replayed challenge and
  fake node all fail closed with no leakage.

## Provisioning summary

Backend claim flow (transactional, serialized by a DB lock row): verify
ACTIVE ADMIN → verify node claim advertisement signature + 5-min freshness
(retry of same-owner/same-key allows the original proof after the window) →
atomically create/adopt `CLAIMED` row → issue signed receipt (org + owner
display + contact + trust anchor). Node `apply_claim` verifies and stores
org/owner/receipt with identity. Abort before commit leaves no half-claimed
state; retry is deterministic. Board claim-mode trigger (BOOT button) is #4;
the core never claims without `ClaimMode::active()`.
