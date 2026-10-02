# 0011 — Backend OpenAPI is the authoritative HTTP contract
Status: Accepted.

Backend owns DTOs and publishes versioned OpenAPI at /api/v1/openapi. Export from
the real running backend in integration tests, canonicalize to backend/openapi/v1.json,
and generate TypeScript paths/schemas with pinned openapi-typescript. The runtime
client is openapi-fetch. Frontend DTO duplication is forbidden. CI checks freshly
exported spec and generated types against committed artifacts and repeats the
check. Breaking changes require a deliberate API version/migration decision.
BLE/GATT remains a separate protocol directory. Relative URLs and dev proxy
preserve same-origin cookies and CSRF expectations.
