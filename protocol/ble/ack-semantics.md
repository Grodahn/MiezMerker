# BLE sync v1 — ACK semantics

## Rule

`ACK N` is legal **iff** the PWA has durably persisted **every** record of
the expected sequence prefix up to `N`. Gaps must never be skipped.

Example from #6: locally stored `{100, 101, 103}`, missing `102` → at most
`ACK 101`. Only after `102` is durably stored may the watermark advance past
it. Lost ACKs and duplicate batches are normal and idempotent.

## PWA computation

Let `base` be the last ACKed watermark the PWA believes the node holds
(`0` when nothing ACKed, else the node's `ack` from `StatusResponse`), and
`S` the set of durably stored sequences for `(node_id, incarnation)` with
`seq > base`. The contiguous watermark is:

```text
w = base
while (w+1) in S: w += 1
ACK w  (only if w > base; else send no ACK)
```

Notes:

- Computation runs **after** the IndexedDB/Dexie transaction commits, never
  before. Backend upload success is irrelevant and never gates the node ACK.
- Store key is `(nodeId, incarnation, sequence)`; retransmitted bytes must
  match exactly, conflicting content is rejected without overwriting.
- `w` is a `BigInt`/u64; JavaScript `Number` must not be used for sequences
  (fixtures include `9007199254740993 > MAX_SAFE_INTEGER`).
- Different `(node_id, incarnation)` lifetimes never share a watermark; a
  factory reset starts a new lifetime at `1`.

## Node enforcement

On `AckRequest{watermark}` the node checks, in order, with trusted time:

1. Authorized (`can_sync`); else `UNAUTHORIZED`.
2. Claimed; else `INVALID_STATE`.
3. `watermark >= current ack` (monotonic, equal is idempotent success);
   decreasing → `INVALID_SEQUENCE`, cursor unchanged.
4. Every sequence between `current ack + 1` and `watermark` exists durably
   for this `node_id`; a gap or future value → `INVALID_SEQUENCE`, cursor unchanged.
5. Persist via `ObservationStore::set_ack_watermark` atomically; failure →
   `INTERNAL`, cursor unchanged.
6. Return `AckResponse{new_watermark}`. Deletion is **not** implied.

The node can reject gaps in its own log, but cannot inspect the PWA's storage.
The collector must compute `w` only from committed local records. It verifies
the node's private-key proof against a cached trusted backend identity before
using its status as the shared ACK baseline.

## Compaction (freeing acknowledged data)

- `Compact` removes the acked prefix (`sequence <= ack_watermark`), never
  unacked records. It is explicit and separate from `Ack` so a lost `Ack`
  response cannot cause premature deletion on retry.
- Recommended flow: `Batch* → persist → Ack → Compact → Status` (verify
  `remaining`). `Compact` is idempotent; repeating it frees `0`.
- Implementation is atomic from the sync client's view: either the full
  prefix is gone or nothing changed; the watermark never moves during
  compaction. Reboot between `Ack` and `Compact` is safe (watermark is
  durable; next session compacts).
- Store-full recovery: when `FULL`, the node keeps serving old data and
  rejects new reads; the collector must sync + ACK + compact to reclaim
  space. Silent deletion of unacked data is forbidden.

## Retry / idempotency matrix

| Failure | Node state | PWA action | Result |
| --- | --- | --- | --- |
| Abort before first batch | watermark unchanged | resume at `ack+1` | no loss, no dup |
| Abort mid-batch | watermark unchanged | discard partial page, resume at `ack+1` | same bytes re-served |
| Abort after persist, before ACK | watermark unchanged | reload durable store, recompute `w`, re-ACK | no dup (store dedups by identity) |
| ACK lost | watermark advanced on node, PWA unaware | re-send same `w` | idempotent success |
| Duplicate batch delivery | unchanged | Dexie `put` idempotent on identity | no fachliche Duplikate |
| Node restart mid-sync | log + watermark durable, session fresh | new challenge, resume at `ack+1` | no loss |
| App restart mid-sync | Dexie durable, watermark recomputed | resume at persisted `w` | no loss |
| Many pending (5000+) | paginated `more/next_cursor` | stream until `more=0` | bounded memory |

Sequence gaps from crash reserve-then-append block both client and server
watermarks. The PWA retains the later records and reports incomplete sync.
Those records stay unacknowledged on the node even after compaction. V1 has no
authenticated tombstone/range mechanism to resolve a permanently missing
sequence; adding one requires an explicit protocol extension.
The backend independently deduplicates by `(node_id, sequence)`.
