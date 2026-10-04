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
4. `watermark <= last durable sequence` for this `node_id`; beyond → 
   `INVALID_SEQUENCE`, cursor unchanged (covers reserved-but-unappended
   sequences and future values).
5. Persist via `ObservationStore::set_ack_watermark` atomically; failure →
   `INTERNAL`, cursor unchanged.
6. Return `AckResponse{new_watermark}`. Deletion is **not** implied.

The node cannot verify PWA-side contiguity directly; it trusts the PWA to
compute `w` correctly. PWA unit tests prove the computation never skips gaps.
The node guarantees it never advances past durable data and never moves
backwards, so a buggy client cannot delete unpersisted data by skipping —
the worst case is a rejected ACK.

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

Sequence gaps from crash reserve-then-append are normal; the PWA treats a
missing sequence as a hole that blocks the watermark but never as an error.
The backend independently deduplicates by full event identity.
