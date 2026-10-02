# Local bootstrap and test-phase setup

There is no public self-registration and no mail sending in the MVP (#16, #19 deferred).
The first organization and its admin are created by the backend `BootstrapRunner` when
the database contains no users yet.

## Environment variables

| Variable | Required | Meaning |
| --- | --- | --- |
| `BOOTSTRAP_ADMIN_EMAIL` | yes | Admin email (normalized to lowercase) |
| `BOOTSTRAP_ADMIN_PASSWORD` | yes | Initial password, min 12 characters, max 72 UTF-8 bytes (BCrypt limit) |
| `BOOTSTRAP_ORG_SLUG` | no | Organization slug (default `versuch`) |
| `BOOTSTRAP_ORG_NAME` | no | Display name (default `Versuchsorganisation`) |
| `BOOTSTRAP_ORG_CONTACT` | no | Optional public contact for the owner hint |

The password is hashed with BCrypt at creation time and never stored or logged in
plaintext. Bootstrap secrets belong in your shell environment or a local `.env` file —
never in git.

## Local start

```sh
docker compose up -d --wait postgres

# Terminal 1 — backend with bootstrap
cd backend
BOOTSTRAP_ADMIN_EMAIL=admin@example.org \
BOOTSTRAP_ADMIN_PASSWORD=supersecret-password-1 \
COOKIE_SECURE=false \
./mvnw spring-boot:run

# Terminal 2 — PWA
cd app
npm ci
npm run dev
```

Open `http://127.0.0.1:5173/` and log in with the bootstrap credentials. The Vite dev
server proxies `/api` to `http://127.0.0.1:8080` (same-origin cookies, no CORS).
Cookies are Secure by default. Use `COOKIE_SECURE=false` only for local HTTP;
HTTPS deployments retain the default. The PWA provides login, logout and an active
organization selector; users with multiple ACTIVE memberships explicitly choose one.

## Adding further test users

An ACTIVE ADMIN creates additional members through the API (test-phase mechanism, no
mail):

```sh
# 1. Obtain CSRF cookie + token
curl -c cookies.txt http://127.0.0.1:8080/api/v1/auth/csrf
# 2. Login
curl -b cookies.txt -c cookies.txt -H "Content-Type: application/json" \
  -H "X-XSRF-TOKEN: <token>" -d '{"email":"admin@example.org","password":"supersecret-password-1"}' \
  http://127.0.0.1:8080/api/v1/auth/login
# 3. Create a member (replace <org-id> with the id from GET /api/v1/organizations)
# Refresh the CSRF token after login; authentication rotates it.
curl -b cookies.txt -c cookies.txt http://127.0.0.1:8080/api/v1/auth/csrf
curl -b cookies.txt -c cookies.txt -H "Content-Type: application/json" \
  -H "X-XSRF-TOKEN: <fresh-token>" \
  -d '{"email":"member@example.org","password":"supersecret-password-2","role":"MEMBER"}' \
  http://127.0.0.1:8080/api/v1/organizations/<org-id>/members
```

## Credential issuer key

By default the backend generates an ephemeral ES256 issuer key at startup (logged as a
warning). Offline credentials verify within that backend instance. For stable trust across
restarts (and later for nodes provisioned against it), configure a persistent key:

```sh
# Generate a P-256 key pair once (e.g. with openssl)
openssl ecparam -name prime256v1 -genkey -noout -out issuer.pem
openssl pkcs8 -topk8 -nocrypt -in issuer.pem -outform DER -out issuer.pkcs8.der

ISSUER_PRIVATE_PKCS8_B64=$(base64 -w0 issuer.pkcs8.der)
ISSUER_PUBLIC_SPKI_B64=$(openssl ec -in issuer.pem -pubout -outform DER | base64 -w0)
```

Set `ISSUER_PRIVATE_PKCS8_B64` and `ISSUER_PUBLIC_SPKI_B64` as base64 (standard, with
padding) of the PKCS#8 private key and SubjectPublicKeyInfo public key. The public key is
published at `GET /api/v1/credentials/issuer` for node trust anchors.

## Tests

Backend tests use H2 by default; the same tests run against PostgreSQL in CI:

```sh
cd backend
./mvnw verify
TEST_DB_URL=jdbc:postgresql://localhost:5432/miezmerker \
TEST_DB_USER=miezmerker TEST_DB_PASSWORD=miezmerker ./mvnw test
```

Bootstrap behavior is covered by `BootstrapTest` (idempotent, hashed password, strong
password enforcement).
