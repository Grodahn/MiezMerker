# BLE sync v1 — example transcript

Complete sync of 3 pending observations, then RTC correction. All frames are
`ver(1) opcode(1) len(2 LE) payload`. Multi-byte integers LE. See
`messages.md` and `../fixtures/ble-sync-v1.json` for golden hex.

```text
--- advertising (no identity/chip/pending/org) ---
ADV service_uuid=6f4a2c1e-…5b6c service_data=01 01 0000
  (ver=1, flags CLAIMED, reserved)

--- GATT connect ---

C → Info.read
S → HelloPublic{server_ver=1, caps=0x001F,
    node_id=44444444-4444-4444-8444-444444444444,
    incarnation=55555555-5555-4555-9555-555555555555,
    fw="miezmerker-esp32c3-1.0.0", claim=CLAIMED, clock=SYNCED}

C → Owner.read
S → OwnerResponse{node_id=4444…,
    org_id=22222222-2222-4222-8222-222222222222,
    slug="vector-org", name="Vector Org", contact="help@vector.example"}
  (foreign orgs stop here and display the hint)

C → Challenge.read
S → nonce=000102…1f (32 B)

C → Auth.write{credential=<offline JWT, org=2222…, dev=3333…, scope=[node:sync]>,
               proof=sign_AppDevice(nonce)}   // raw 64 B r||s
S → AuthResponse{ok=1, expires=1917129600}

C → NodeProof.write{pwa_nonce=b0b1…cf}
S → NodeProofResponse{sig=sign_Node(MM-NODE-SESSION-v1\n4444…\nbase64url(pwa_nonce))}
C verifies against backend-pinned node key (30 s window) → ok

C → Status.read
S → StatusResponse{pending=3, ack=100, store=OK, clock=SYNCED,
                   epoch=1790899200000, next_seq=104}

C → Batch.write{from_seq=101, max_records=16}
S → notify BatchResponse{from_seq=101, count=3, more=0, next_cursor=104,
    records=[
      {seq=101, chip="276098106000001", epoch=1790899200000, clock=SYNCED, mono=1000, boot=7},
      {seq=102, chip="276098106000002", epoch=1790899201000, clock=SYNCED, mono=4000, boot=7},
      {seq=103, chip="276098106000003", epoch=null,          clock=UNKNOWN, mono=7000, boot=7}]}

--- PWA: Dexie transaction commits all 3 keyed by (node, incarnation, seq) ---
--- PWA computes contiguous watermark: base=100, S={101,102,103} → w=103 ---

C → Ack.write{watermark=103}
S → AckResponse{new_watermark=103}   // durable; unacked data never deleted here

C → Compact.write{}                  // explicit, after ACK
S → CompactResponse{freed=3, remaining=0, ack=103}

C → Status.read
S → StatusResponse{pending=0, ack=103, …}

--- RTC correction (protected, future reads only) ---
C → Time.write{epoch_ms=1790900000000}
S → TimeCorrectResponse{ok=1, applied=1790900000000}
  // stored observations 101..103 keep their original epoch/clock;
  // next RFID read uses the corrected RTC.

--- disconnect (session cleared) ---
```

## Gap example

Stored locally `{101, 103}`, missing `102` → PWA sends `Ack{101}` only.
After `102` arrives durably → `Ack{103}`. Node rejects `Ack{104}` while
`last=103` with `INVALID_SEQUENCE`.

## Foreign-org example

Credential `org=AAAAAAAA-…` at node org `2222…` → `AuthResponse{ok=0,
FORBIDDEN_FOREIGN}`. Subsequent `Status.read` → `Error(FORBIDDEN_FOREIGN)`,
`Batch` → same. `Owner.read` still returns the public hint above.

## Error examples

- Frame `ver=2` → `Error(UNKNOWN_VERSION)` with no state change.
- `Batch{from_seq=0}` or `Ack{999999}` beyond last → `INVALID_SEQUENCE`.
- Corrupt record on flash → `Batch` returns `INVALID_RECORD`, cursor unchanged.
- Expired credential / future `iat` / bad proof / replayed nonce →
  `AuthResponse{ok=0}` + appropriate code, challenge consumed.
