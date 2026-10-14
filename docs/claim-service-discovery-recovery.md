# Claim blocked by service discovery

## Observed ESP32-C3 report

After the #131 merge, a freshly flashed node established a BLE connection, but
the PWA reported `Service-Suche zeitüberschritten`. This timeout is in
`getPrimaryService`, before public identity, signed time bootstrap, claim, or
organization assignment. The reported NimBLE unsupported-remote-feature log
alone does not establish the cause. #131 did not change the GATT service or
characteristic registration. After closing the PWA, toggling phone Bluetooth,
and restarting the node, the user reported successful backend registration,
followed by `Characteristic-Suche (info)` failing. This confirms progress beyond
service discovery, but does not yet prove completed firmware ownership.

## Bounded PWA recovery

On a service discovery timeout, close the connection and wait for the existing
bounded disconnect handling, then reconnect the same user-permitted device
once. A second timeout ends the operation. Cancellation during discovery or
teardown prevents a reconnect. No additional chooser, scanning, or claim is
initiated. Identity verification, signed time, authorization, and explicit
claim confirmation still run normally after discovery succeeds.

The UI identifies a service-discovery failure and suggests closing the PWA,
toggling phone Bluetooth, restarting the node, and retrying. Backend time
service failures are separately classified; they must not suggest that the
node is unreachable.

## Post-receipt ownership confirmation

The firmware refreshes public Hello/Owner from the atomically committed identity
before acknowledging ClaimReceipt. Read these on the existing connection rather
than disconnecting and immediately rediscovering all characteristics. Confirm
node ID, incarnation, CLAIMED state, and organization before updating the local
claim state. Final cleanup disconnects; later protected synchronization still
starts a fresh session with the persisted owner context.

## Browsers without saved-device listing

The user subsequently reported missing `getDevices()` on Fairphone 3+ / Chrome
155.0.8059.79. Without saved-device listing, retain explicitly selected browser
device objects for this mounted PWA session so normal sync and failed-node retry
can reuse them. These objects are not trusted node identity and are not persisted.
When listing is available, refresh it normally, including permission revocation.
Before any manual selection in an unsupported browser, disable the empty batch
button and explain the explicit selection action. Do not open a chooser on mount
or automatically during batch sync.

An absent chooser entry and firmware keepalives alone do not establish GATT
connection state. The ESP32 configuration allows one connection and advertising
resumes in the disconnect handler. Confirm `advertising` after restarting the
node with all other PWA/browser sessions closed; note any unexpected connection
before starting the sole browser session.

## Verification

Automated coverage includes recovery on the first service timeout, bounded
failure on the second, cancellation, backend time-service failure classification,
and the guided browser registration after a simulated discovery timeout. The
browser fixture rejects characteristic rediscovery after receipt delivery;
registration must still verify ownership through the retained connection.
Mismatched owner, incarnation, or claim status must retain the saved receipt
without marking local registration confirmed.
Mocks do not prove ESP32-C3/phone interoperability.

On the affected phone and node, retry after closing the PWA, toggling Bluetooth,
and restarting the node. Preserve NVS and keys; no factory reset is needed for
this check. Record whether discovery succeeds, the exact next error if any,
and whether explicit registration leaves both firmware and backend claimed in
the expected organization. If discovery still times out, retain the firmware
startup log and phone/browser versions for further GATT diagnosis.
