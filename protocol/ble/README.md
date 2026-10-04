# BLE sync protocol v1 — NapfNode ↔ PWA collector

Implements [issue #6](https://github.com/Grodahn/MiezMerker/issues/6).
Target hardware: **ESP32-C3** (NimBLE). No OS Bluetooth pairing required.

This directory is the versioned BLE/GATT contract, independent of concrete
browser/Web-Bluetooth/ESP32 classes. HTTP/OpenAPI never describes this wire
format; `protocol/raw-observation-v1.schema.json` is the logical record model,
this directory owns the GATT bytes.

- [`messages.md`](messages.md) — service/characteristic UUIDs, frame layout,
  opcodes, record encoding, error codes, MTU/fragmentation, versioning.
- [`state-diagram.md`](state-diagram.md) — connection/session state machine.
- [`ack-semantics.md`](ack-semantics.md) — contiguous high-watermark, durable
  persistence before ACK, compaction, retry/idempotency.
- [`security.md`](security.md) — offline authorization reuse (#17/#18),
  challenge/response, credential claims/byte format, foreign-org behavior,
  public owner metadata, advertisement privacy.
- [`example-transcript.md`](example-transcript.md) — full sync transcript.

Golden bytes: [`../fixtures/ble-sync-v1.json`](../fixtures/ble-sync-v1.json).
Logical record JSON: [`../raw-observation-v1.schema.json`](../raw-observation-v1.schema.json).

## Layering (binding)

1. **Domain state machine** — `firmware-core/include/miezmerker/ble_sync.hpp`
   (portable `SyncServer`, no BLE/crypto includes) and
   `app/src/collector/sync-engine.ts`. Protected ops require an authorized
   session; the app ACKs only durably persisted prefixes; the node frees only
   after a valid ACK via explicit compaction.
2. **Transport-independent codec** — `firmware-core/ble_codec.*` and
   `app/src/collector/ble-codec.ts`. Pure `[ver][opcode][len][payload]`
   encode/decode with golden fixtures. No NimBLE/Web-Bluetooth imports.
3. **GATT transport** — `firmware-esp32/components/ble-sync/` (NimBLE
   characteristics, advertisement builder, per-connection
   `OfflineAuthSession` + `SyncServer` routing) and PWA `SyncChannel`
   (Web Bluetooth behind an interface; management pages never import it).
4. **Board wiring (ESP32-C3, #4)** — CSPRNG for challenges, trusted RTC/clock
   for expiry, flash/NVS atomic stores, BOOT-button claim mode. Host tests use
   fakes; this directory documents the required behavior.

Security semantics from #17/#18 are reused unchanged (ADR-0012, ADR-0013,
ADR-0015). No shared organization secret, no custom crypto, no second
credential format.

## Versioning strategy

- Wire `ver` byte. Current: `1`. Client sends its max supported version in
  `HelloRequest`; server answers `min(client, server)` or `UNKNOWN_VERSION`.
- v1 server rejects any frame with `ver != 1` with `Error(UNKNOWN_VERSION)`
  and emits no protected data. Unknown opcodes in v1 likewise return
  `INVALID_FRAME`.
- Capability bits (`caps u16`, HelloPublic): `0x0001 BATCH`, `0x0002 ACK`,
  `0x0004 TIME_CORRECT`, `0x0008 NODE_PROOF`, `0x0010 COMPACT`. v1 sets
  `0x001F`. Clients must ignore unknown bits; servers must ignore unknown
  request fields only if the version negotiates them (v1: reject).
- Additive opcodes/fields → minor (backward compatible, old clients skip via
  `len`); incompatible framing or changed ACK meaning → major (new `ver`).
- Firmware version string (e.g. `miezmerker-esp32c3-1.0.0`) is informational
  only and never gates authorization.

## Advertisement (privacy)

BLE advertisement contains **technical discovery only**:

- Flags + 128-bit service UUID + service data
  `[proto_ver u8 = 1][flags u8][reserved u16 = 0]`.
- Flags: `bit0 CLAIMED`, `bit1 CLAIM_MODE_ACTIVE`, bits 2–7 reserved 0.
- No chip IDs, no `node_id`, no org data, no pending counts, no timestamps.

Rationale: chip numbers must never be broadcast unencrypted; owner data is
never broadcast; pending counts would leak activity to foreign orgs. After
GATT connection the client reads public `Info`/`Owner` characteristics; chip
and pending data require authorization (see `security.md`).

ESP32-C3 NimBLE example: connectable/scannable undirected advertising,
interval ~100–200 ms, TX power 0 dBm default, short name `MiezMerker`
(optional, no identity in the name). Claim-mode windowing is a board concern
(#4); the flag only reflects the `ClaimMode` port.

## GATT summary

Service `6f4a2c1e-8b3d-4e5f-9a0c-1d2e3f4a5b6c`, characteristics:

| Name | UUID suffix | Access | Auth | Purpose |
| --- | --- | --- | --- | --- |
| Info | `…5b01` | read | public | `HelloPublic` (version/caps/node/incarnation/fw/claim/clock) |
| Owner | `…5b02` | read | public | claimed owner hint or unclaimed marker |
| Challenge | `…5b03` | read | public | fresh 32 B nonce per connection (`begin`) |
| Auth | `…5b04` | write + read | public write, gated effect | credential JWT + 64 B proof → result |
| NodeProof | `…5b05` | write + read | authorized* | PWA 32 B nonce → node 64 B ES256 signature (#18) |
| Status | `…5b06` | read | authorized | pending/ack/store/clock/next-seq |
| Batch | `…5b07` | write + notify/read | authorized | `from_seq/max_records` → record pages |
| Ack | `…5b08` | write + read | authorized | high-watermark → persisted cursor |
| Time | `…5b09` | write + read | authorized | UTC ms → RTC discipline (future reads only) |
| Compact | multiplexed on Ack | write + read | authorized | free acked prefix (explicit, after ACK) |

`*` NodeProof requires an authorized session in v1 (prevents oracle use by
unauthorized scanners); the PWA verifies it against the backend-pinned node
key, never against peer-supplied bytes.

Full byte layouts in `messages.md`. State machine in `state-diagram.md`.
