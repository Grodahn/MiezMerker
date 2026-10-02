# PR 23 review: issue 17

Fixed findings:

- **P1: Device identity and offline credentials did not survive a reload.**
  Persist non-exportable CryptoKey handles and account/organization-bound credentials in
  IndexedDB. Concurrent tabs converge on one identity per account. The PWA renews after
  authenticated session discovery, connectivity restoration, and near expiry while online.
  Logout/account changes remove cached credentials. Issuance responses are checked against
  the captured account, organization, device and public key before caching.
- **P1: No node-side offline authorization implementation existed.**
  Add an ESP-IDF component using Mbed TLS and cJSON. It verifies JWS/ES256, issuer/kid,
  version, organization, role/scope, validity interval, key fingerprint, and device PoP.
  Its connection-local challenge is consumed on every attempt. Unknown trusted time fails
  closed; authorization is checked again at each protected operation and expires in-session.
- **P2: Expired/future credentials and invalid TTL configuration were accepted.**
  Reject at exact expiry, reject future/missing/reversed issuance times, reject fractional
  credential versions, and reject nonpositive/overflowing TTL configuration.
- **P2: Device registration accepted off-curve public keys.**
  Validate the P-256 equation, coordinate range and canonical base64url representation.
- **P2: The interop test rewrote the shared golden fixture without verifying it.**
  Generate candidates only under target/ and verify the committed credential and PoP bytes.

Validation: app tests and production build; backend tests; host compilation of the exact
ESP-IDF verifier using Mbed TLS 3.6.2/cJSON 1.7.19 and the shared golden fixture, including
signed invalid claims, MEMBER/ADMIN, wrong organization, expiry, tampering, copied
credentials, missing scope, future issuance, and repeated/different challenges.

Board/GATT integration remains in #4/#6: construct one OfflineAuthSession per connection
from durably provisioned owner and issuer-public-key data, supply an unpredictable CSPRNG
and trusted UTC time, gate every protected read/write through can_sync(), and disconnect
on link teardown. This review verifies the component on a host; it does not claim a flashed
ESP32 hardware or GATT test.
