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

## Second review

- **P1: Concurrent issuance/re-registration could undo device revocation.**
  Hold a pessimistic device-row lock in all three transactions. A regression holds the
  revocation lock while concurrent issuance and registration requests wait; both must see
  the committed revocation and return 403, with the device still revoked.
- **P1: Logout cleanup waited behind network renewal and late responses could restore credentials.**
  Start cleanup immediately. A shared IndexedDB generation invalidates in-flight renewals
  across tabs, including later organizations in the same renewal batch. Queued work also
  checks that its captured session is still current before making requests.
- **P2: One failed organization prevented all later organizations from renewing.**
  Continue the batch, cache successful credentials and report aggregate failure afterward.
- **P2: Role changes did not trigger renewal when organization IDs were unchanged.**
  Include membership details in the session-change comparison.
- **P2: Invalid issuer key configuration failed only after issuing unusable credentials.**
  Reject missing halves, non-P-256 keys and mismatched private/public keys at startup.
  Correct the bootstrap command to encode DER PKCS#8 rather than PEM text. A restart test
  confirms a new issuer instance verifies a credential issued with the same stored pair.

Second-pass validation: 31 app tests, TypeScript compilation and production build;
backend credential, concurrent revocation, issuer configuration, interop and node-claim
regressions (28 tests). ESP32 board/GATT limitations from the first review still apply.
