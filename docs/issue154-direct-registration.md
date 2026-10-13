# Direct UNCLAIMED registration (#154)

An authenticated active organization ADMIN selects a reachable UNCLAIMED bowl
and explicitly chooses **Napf registrieren**. No button press, power-on gesture,
QR code, system privilege or development switch is required. MEMBER, inactive
accounts/memberships/organizations and unauthenticated callers remain denied.
Factory reset retains its separate two-hold physical confirmation and identity
rotation; registration never resets a device or replaces its keys.

The backend verifies the device-signed UUID/key/timestamp proof, serializes
ownership reservation with the existing database claim lock, and issues a signed
receipt. The historical `claim-mode` message suffix remains for wire compatibility
as domain separation only. New claims retain five-minute freshness; same-owner
same-key receipt recovery verifies the original signature without requiring a
fresh original timestamp. Foreign ownership and replacement keys remain denied.

The PWA persists receipts before BLE delivery. A disconnect preserves recovery
material. After delivery it rereads public state/owner before marking local state
CLAIMED and opening existing setup/synchronization. Expired cached receipts are
renewed once through the same-owner backend path. A conservative monotonic wait,
including the measured complete bootstrap round trip and uncertainty, permits
new receipt issuance time to clear the conservative lower UTC bound; the firmware
still verifies the complete signed receipt and strict [L,U] validity on each attempt.

## Trusted time

Fresh ESP32 boots intentionally start with UNKNOWN security time. Registration
now uses a small pre-auth exchange over the existing ClaimReceipt characteristic:
opcodes 0x19/0x1a deliver a 239-byte binary ADR 0017 challenge; 0x1b/0x1c forward
the exact backend-signed proof using the existing <=4096-byte ordered fragment
envelope, returning success only after durable anchor/acceptance storage. This
does not implement the broader #131 evidence retrieval/ACK transport. Time trust
alone never claims ownership, grants BLE authorization, or reads observations.

The PWA preserves unsigned 64-bit uptime as an exact JSON integer. The backend
validates the self-signed challenge and ACTIVE ADMIN membership, key ownership,
claim races and existing abuse budgets. Reserved same-owner/key ADMINs may use
the UNCLAIMED bootstrap path to recover after a reboot before receipt delivery.
Foreign organizations and MEMBERs cannot use that recovery path.

Firmware retains the independent issuer pin, purpose/identity/boot/incarnation/
nonce binding, monotonic deadline, single-use proof acceptance, durable storage,
uncertainty bounds and expiry checks. No phone time or receipt-carried key becomes
an authority. Boot/reboot clears trust. Existing observation clock semantics and
protected TimeCorrect behavior remain unchanged.

Deployment prerequisites remain a matching configured backend issuer key and
firmware public pin, with backend UTC issuance explicitly enabled only under the
existing clock-health operational policy. Missing/unhealthy time returns a
concrete PWA error; registration never fabricates trust to succeed. Static health
configuration is an operator attestation, not live NTP monitoring. Production
must supply a trusted monitor or disable issuance on clock failure.

Host tests cover direct initial commit, GATT fragmented signed-time delivery,
real pinned proof verification, replay/expiry/boot failures, backend role/tenant/
key/ownership/race checks, PWA durable receipt recovery and uint64 transport.
Host fault injection and production compilation do not establish physical
Android/ESP32 acceptance or measured oscillator accuracy.
