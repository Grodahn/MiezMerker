# Issue #2 acceptance review

Contract: [current issue #2](https://github.com/Grodahn/MiezMerker/issues/2), read
before implementation on 2026-10-01. Every acceptance criterion was reviewed.

| Criterion | Evidence |
| --- | --- |
| Fresh environment builds/tests according to README | Clean tracked-source export rebuilt using Maven wrapper, npm ci, PWA tests/build, CMake/CTest; no copied target/dist/node_modules. Prerequisites and Windows commands documented. |
| Structure/responsibilities; exactly one app for #8/#11 | All eight directories exist; one package.json frontend under app; shared collector/management routes, root CMake composition and architecture responsibility table. |
| Domain model including tenant/user/membership/device and historical deployment | architecture.md domain table; separate membership role/status; half-open deployment intervals; observation-time attribution and unresolved clock policy. |
| Trust boundaries and separate authentication/authorization | architecture.md trust table; ADRs 0007–0009; HTTP sessions distinct from per-device offline BLE authority. No distributed employee-wide secret. |
| No visit aggregation in firmware/PWA | Source review: only boot scaffolding, shell, storage/transport ports and contract plumbing. Derivation is explicitly backend-owned. |
| Simulator builds against firmware-core | Root CMake links the real core library into both simulator and ESP32 composition target; all three CTests pass. |
| Hardware-free CI for core/simulator/backend/PWA | ci.yml uses the documented CMake, wrapper, npm and Playwright commands; backend tests additionally run against PostgreSQL; no board/reader dependency. |
| Versioned backend OpenAPI | Real server test exports /api/v1/openapi, checks version v1 and operation/schema presence; committed canonical backend/openapi/v1.json. |
| Reproducible TypeScript client from OpenAPI | Clean backend reexport passes byte-for-byte api:check; api:generate followed by repeated api:check produces identical spec/types; openapi-fetch consumes generated paths. |
| Local same-origin/dev-proxy startup | One shared /api proxy config for Vite dev/preview; Chromium reaches real backend version endpoint through PWA origin without CORS setup. |
| Offline collector and independent Dexie outbox | Chromium reloads /sync offline after worker activation and reads persisted upload bytes. Unit tests reopen Dexie and partition queues; management shell does not mount collector storage. |
| README data flow and local start | README shows RFID → Node → BLE → PWA/outbox → backend → derived visits, PostgreSQL/backend/PWA startup, generation, tests and offline verification. |

Local verification: Windows, Java 25 compiling for Java 21, Node 24.19,
Clang/CMake/Ninja, test Chromium. Successful counts: 3 CTests, 2 backend
integration tests, 5 PWA/contract tests and 2 browser integration tests. Builds
and contract checks also passed from a clean tracked-source export. Dependency
caches and installed toolchains were reused, as on an ordinary fresh checkout.

Docker was not running locally, so local backend checks used the **test-only H2**
fallback. The CI runs the same tests and packaged application against PostgreSQL
18.6 on a fresh Linux checkout. Its actual result is reported with the PR.

Intentional boundaries: no flashable board image/ESP-IDF drivers, RFID capture,
crash-safe Node log, GATT codec/real transfer, HTTP login, tenant business endpoints,
credential/claim/reset flows, real backend upload, management features or visit
derivation. These remain in #5–#19. The BLE logical record schema/fixture establishes
a shared representation; GATT bytes and cryptographic details belong to their
feature tickets. No confirmed outstanding issue within #2 scope remained after
the local acceptance review.
