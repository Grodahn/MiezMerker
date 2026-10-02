# 0003 — Versioned record transfer independent of HTTP
Status: Accepted boundary; GATT/frame encoding deferred to BLE work.

BLE sync transfers raw records with version negotiation, bounded batches,
resumable cursors and durable acknowledgements. The PWA acknowledges only after
local commit and may do so without Internet. The protocol directory owns GATT
identifiers, frame definitions, codecs and shared byte fixtures once finalized.
The logical raw-observation v1 schema and JSON fixture already live in protocol;
integer identities/times remain lossless decimal strings across JavaScript boundaries.
OpenAPI never specifies BLE frames; backend receipt is a separate state machine.
