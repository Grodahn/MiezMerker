# BLE sync v1 — messages & characteristics

All integers **little-endian**. Strings are `[len u16 LE][UTF-8 bytes]`
unless noted. `u64` epoch/times are milliseconds. Version byte `ver = 1`.

## Frame

Every control/batch/ack/time payload on the wire is a frame:

```text
ver u8 (=1) | opcode u8 | len u16 LE | payload[len]
```

`len` is the payload length only. Receivers must check `ver == negotiated`
first, then `opcode`, then exact `len`. Trailing bytes → `INVALID_FRAME`.
`ver != 1` → `Error(UNKNOWN_VERSION)` with no protected data.

Opcodes (v1):

| Op | Name | Direction | Payload |
| --- | --- | --- | --- |
| `0x01` | HelloRequest | C→S | `client_ver u8, caps u16` |
| `0x02` | HelloPublic | S→C | `server_ver u8, caps u16, node_id 16 B, incarnation 16 B, fw_len u8 + fw[fw_len], claim u8, clock u8` |
| `0x03` | OwnerRequest | C→S | empty (read trigger) |
| `0x04` | OwnerResponse | S→C | `node_id 16 B, claim u8, org_id str, org_slug str, org_name str, contact str` |
| `0x05` | ChallengeRequest | C→S | empty |
| `0x06` | ChallengeResponse | S→C | `nonce 32 B` |
| `0x07` | AuthRequest | C→S | `cred_len u16 + cred[cred_len] ASCII JWT, proof 64 B raw r\|\|s` |
| `0x08` | AuthResponse | S→C | `ok u8 (0/1), expires_s u64 LE (0 on failure), error u8` |
| `0x09` | NodeProofRequest | C→S | `pwa_nonce 32 B` |
| `0x0A` | NodeProofResponse | S→C | `sig 64 B` or `Error` |
| `0x0B` | BatchRequest | C→S | `from_seq u64 LE, max_records u16 LE (1..64)` |
| `0x0C` | BatchResponse | S→C | `from_seq u64, count u16, more u8 (0/1), next_cursor u64, records[count]` |
| `0x0D` | AckRequest | C→S | `watermark u64 LE` |
| `0x0E` | AckResponse | S→C | `new_watermark u64, error u8` |
| `0x0F` | TimeCorrectRequest | C→S | `epoch_ms u64 LE` |
| `0x10` | TimeCorrectResponse | S→C | `ok u8, error u8, applied_epoch u64` |
| `0x11` | StatusRequest | C→S | empty |
| `0x12` | StatusResponse | S→C | `pending u32 LE, ack u64, store u8, clock u8, epoch u64 (0 if unknown), next_seq u64` |
| `0x13` | CompactRequest | C→S | empty |
| `0x14` | CompactResponse | S→C | `freed u32 LE, remaining u32 LE, ack u64, error u8` |
| `0x15` | ClaimAdvertisementRequest | C→S | empty (read trigger, physical claim mode only) |
| `0x16` | ClaimAdvertisementResponse | S→C | `node_id 16 B, public_key 65 B (0x04\|x\|y), timestamp_ms u64 LE, signature 64 B` |
| `0x17` | ClaimReceiptRequest | C→S | `receipt str` (nonempty, <=4096 B) |
| `0x18` | ClaimReceiptResponse | S→C | `error u8` (`0` only after atomic claim commit and capture-state refresh) |
| `0xFF` | Error | S→C | `code u8, msg str` |

Enums: `claim`: `0 UNCLAIMED, 1 CLAIMED`. `clock`: `0 UNKNOWN, 1 RTC_ONLY,
2 SYNCED`. `store`: `0 OK, 1 NEARLY_FULL, 2 FULL`.

## Record encoding

One `RawObservation` on the wire (`protocol_view` JSON is the lossless
reference; this is the GATT binary):

```text
node_id 16 B | incarnation 16 B | sequence u64 LE (>=1)
| chip_len u16 LE (1..64) + chip[chip_len] UTF-8, opaque reader identifier
| clock u8 (0/1/2) | epoch u64 LE (0 when clock==UNKNOWN)
| monotonic u64 LE | boot u32 LE
```

Max 127 B/record. Validation: `sequence != 0`, `chip_len` in range,
`clock` known value, `epoch != 0` iff `clock != UNKNOWN`, `valid()` per
`types.hpp`. Invalid stored records produce `INVALID_RECORD`, never a torn
frame. Retransmission preserves every byte and `(node_id, incarnation,
sequence)` identity.

`chip_id` is the unchanged canonical reader string; reader normalization
belongs to the adapter and must be specified before capture comparisons.
Collectors reject malformed UTF-8 and preserve a leading BOM as identifier
data. A zero RTC reading is captured as `UNKNOWN` before persistence, so
known-clock records always have a nonzero epoch and round-trip through v1.

## GATT mapping (NimBLE, ESP32-C3)

Service UUID `6f4a2c1e-8b3d-4e5f-9a0c-1d2e3f4a5b6c`.

| Char | UUID | Props | Frame |
| --- | --- | --- | --- |
| Info | `6f4a2c1e-8b3d-4e5f-9a0c-1d2e3f4a5b01` | read (public, unencrypted) | `HelloPublic` |
| Owner | `…5b02` | read (public) | `OwnerResponse` |
| Challenge | `…5b03` | read (public) | `ChallengeResponse` |
| Auth | `…5b04` | write + read (public write) | `AuthRequest` → `AuthResponse` |
| NodeProof | `…5b05` | write + read (authorized) | `NodeProofRequest` → `NodeProofResponse` |
| Status | `…5b06` | read (authorized) | `StatusResponse` |
| Batch | `…5b07` | write + notify/read (authorized) | `BatchRequest` → `BatchResponse` (notify pages) |
| Ack | `…5b08` | write + read (authorized) | `AckRequest` → `AckResponse`; `CompactRequest` → `CompactResponse` multiplexed by opcode |
| Time | `…5b09` | write + read (authorized) | `TimeCorrectRequest` → `TimeCorrectResponse` |
| ClaimAdvertisement | `…5b0a` | read (physical claim mode) | `ClaimAdvertisementResponse` or `Error` |
| ClaimReceipt | `…5b0b` | write + read | `ClaimReceiptRequest` → `ClaimReceiptResponse` or `Error` |

The two provisioning characteristics extend v1 without changing existing sync
frames or capability bits. Older nodes may omit them and still synchronize;
claiming requires firmware exposing both. The board supplies the same persistent
`NodeIdentityManager` used by Core through `GattRouter::set_node_identity` and
routes the new characteristic reads/writes to these opcodes. Never substitute a
peer-supplied timestamp or public key for the manager's signed advertisement.
Receipt verification uses the existing pinned issuer and `apply_claim`: initial
claim requires physical mode, same-owner redelivery is idempotent, and a different
owner is rejected. On reconnect, configure the authorizer from the newly persisted
organization/issuer context. Do not retain an unclaimed-session authorizer after
provisioning. Public Owner strings come from that same committed identity.

Claim receipts use the fragment envelope below with first marker `0x4e` instead
of `0x4d`, total length 7..4102, and final opcode ClaimReceiptRequest only. The
second marker remains `0x4d`. Auth and receipt assemblies are separate and both
are cleared on disconnect. Shims accept receipt envelopes only on ClaimReceipt.
Incomplete fragments produce no application response; retain the final response
for the subsequent characteristic read. The collector stores the backend receipt
before its first write and verifies matching CLAIMED Hello/Owner after delivery.

Security properties: unencrypted (no OS pairing requirement), authorization
is application-layer via #17. Unauthorized reads of authorized chars return
`UNAUTHORIZED` (ATT application error mapped from `Error`), never data.

## MTU / chunking (ESP32-C3)

- Default ATT MTU 23 → 20 B payload; negotiate up to 517 via MTU exchange.
  v1 requires handling at least 185 B notifications (ESP32-C3 NimBLE default
  after negotiation with Chrome/Android).
- `BatchResponse` pages are sized by the server to fit the negotiated MTU:
  `max_records` is clamped to `min(client max, server max 16, mtu_fit)`.
  Larger logs use `more=1` + `next_cursor` pagination; the client repeats
  `BatchRequest{next_cursor}` until `more=0`.
- Web Bluetooth caps each attribute write at 512 bytes and does not expose
  L2CAP CoC. `AuthRequest` uses an Auth-characteristic transport envelope:
  `0x4d 0x4d | envelope_ver u8 (=1) | start u8 (0/1) | total u16 LE |
  offset u16 LE | original_frame_bytes[1..12]`. Each write fits even MTU 23.
  Total is 70..2118 bytes (credential <=2048 plus frame/proof overhead).
  Start=1 resets any incomplete assembly and requires offset=0. Later offsets
  must be contiguous with identical total; malformed input clears assembly.
  Disconnect also clears assembly. Only a complete AuthRequest is dispatched
  to authorization; original codec bytes and challenge/proof semantics remain
  unchanged. Small/direct frames remain accepted by host transports.
  Board shims route Auth writes through `GattRouter::handle_frame`, accept an
  empty response as an incomplete fragment, and retain the final AuthResponse
  for the next Auth read. Reject envelope writes on other characteristics.
  The PWA uses read responses and requests one record per BatchRequest, so it
  does not depend on notification timing or unimplemented MTU page clamping.
- Timeouts: GATT op 10 s, full sync bounded only by pending count; very large
  backlogs stream in pages (tested with 5000 records in simulator/PWA).

## Error codes (`Error.code` / `AuthResponse.error`)

`0 OK`, `1 UNKNOWN_VERSION`, `2 UNAUTHORIZED`, `3 FORBIDDEN_FOREIGN`,
`4 INVALID_FRAME`, `5 INVALID_SEQUENCE`, `6 INVALID_RECORD`,
`7 NOT_FOUND`, `8 STORE_FULL`, `9 CLOCK_UNAVAILABLE`,
`10 AUTH_EXPIRED`, `11 REPLAY`, `12 INVALID_CREDENTIAL`,
`13 INVALID_PROOF`, `14 INVALID_STATE`, `15 INTERNAL`.

`UNKNOWN_VERSION` is returned for any `ver != 1` without touching protected
state. `INVALID_SEQUENCE` covers `from_seq == 0`, `from_seq` beyond
`last+1`, and `watermark > last`. `INVALID_STATE` covers sync ops on
`UNCLAIMED` nodes and ops before authorization.

## Advertisement bytes

Service data (AD type `0x16`, 128-bit UUID + data):

```text
proto_ver u8 (=1) | flags u8 | reserved u16 (=0)
flags bit0 CLAIMED, bit1 CLAIM_MODE_ACTIVE
```

No identity, org, pending, chip or time fields. See `README.md`.
