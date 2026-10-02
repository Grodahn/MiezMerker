# 0012 — Offline BLE credentials: JWS/ES256 with per-AppDevice key binding
Status: Accepted (implements #17).

## Context

#17 requires backend-signed, time-limited, organization-bound offline credentials that a
Node can verify completely offline, without a shared organization secret. The issue names
CWT/COSE as the preferred candidate (compact CBOR) and allows JWS/JWT when the overall
implementation across Spring Boot, Web Crypto and ESP32-C3 is demonstrably simpler.

## Decision

**JWS compact (JWT) with ES256 (ECDSA P-256 + SHA-256).** No proprietary crypto.

Reasons:
- Spring Boot: Nimbus JOSE provides ES256 sign/verify out of the box; no CBOR stack needed.
- Web Crypto: `subtle.generateKey({name:'ECDSA', namedCurve:'P-256'})` and
  `subtle.sign` return raw 64-byte `r||s` for P-256 — exactly the IEEE P1363 form used on
  the wire. JWS/ES256 uses the same raw form internally.
- ESP32-C3: mbed TLS supports ECDSA P-256/SHA-256 and JWS compact is a flat
  `header.payload.signature` string — trivial to parse on constrained devices, whereas
  COSE/CBOR requires a CBOR decoder.
- JWT claims are self-describing; the Node needs only the issuer public key.

CWT/COSE remains a valid future optimization if CBOR size on GATT becomes critical.

## Wire format

- Public key coordinates `x`/`y`: 32-byte big-endian, base64url without padding (43 chars).
- Key fingerprint: `base64url(SHA-256(0x04 || x || y))` (43 chars).
- Challenge signature: ECDSA P-256/SHA-256 over the raw 32-byte challenge, raw 64-byte
  `r||s`, base64url. Java JCA emits DER; `EcKeyUtils.rawToDer`/`derToRaw` convert at the
  boundary. Web Crypto and ESP32 mbed TLS emit raw natively.
- Credential claims: `iss, sub=userId, org, org_slug, dev=deviceId, dpk_x, dpk_y, dpf,
  role, scope=["node:sync"], iat, exp, jti, ver=1, kind="offline"`.
- Claim receipt claims: `sub=nodeId, org, org_slug, org_name, contact, ndpk_x/y, ndpf,
  ipk_x, ipk_y, iat, exp (10y), kind="claim"`.

## Proof-of-possession

The credential embeds the AppDevice public key. The Node issues a fresh 32-byte challenge
per BLE connection; the PWA signs it with the private key. A copied credential without the
private key cannot produce a valid signature. Replay fails because the signature binds the
exact challenge bytes.

## Revocation boundary

A DISABLED membership or revoked AppDevice blocks new credential issuance immediately.
Already-issued offline credentials remain usable until `exp` — there is no immediate
offline revocation in the MVP. Credential TTL is configurable
(`miezmerker.credential.ttl-hours`, default 168h/7d) and should be renewed online.

## Interop vectors

`protocol/fixtures/offline-credential-v1.json` contains fixed keys, a fixed credential
JWT, a fixed challenge + signature and a fixed claim advertisement. ECDSA signatures are
single valid examples (random nonces); verification is deterministic.
