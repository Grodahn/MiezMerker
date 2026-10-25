# Stable error codes (#194)

`diagnostics/error-codes.json` is authoritative. Add codes; never renumber, reuse,
or derive them from translated text. Regenerate adapters and this table with
`python scripts/generate-error-codes.py`; CI validates `--check`.
Codes identify cases; they are not unique incident/correlation IDs. No new
correlation identifier or telemetry is introduced. No secrets or resource IDs
belong in codes. HTTP 403/404 remain neutral across tenants. Firmware wire values
are unchanged; `firmwareErrorCode` maps existing SyncError values in the app.
Unknown/unstructured failures use UI_UNKNOWN (presentation) or HTTP_UNKNOWN
(server). Known operation fallbacks are explicitly marked below; refine them
at the source when a new actionable case is identified.

MVP inventory: shared PWA Status, login/account/org, management/photo feedback,
collector claim/preflight/time/identity/credential, persist/ACK/COMPACT, outbox
and backend upload; backend MVC/security/API errors, admin forms and async
admin feedback. Success, empty, cancellation and routine offline waiting states
do not acquire an error code solely because they are informational.

Example from the registration preflight (text fixture, no hardware claim):
`Kein gültiger Identitätsnachweis. Sicherheitszeit-Policy, Handyzeit (Alpha)
bzw. signierten UTC-Bootstrap prüfen. [MM-CLAIM-004]`
Hardware acceptance: **UNVERIFIED**. Automated protocol tests use simulated peers.

| Code | Meaning | Phase | Typical causes | First diagnostic action |
| --- | --- | --- | --- | --- |
| MM-UI-001 | Unclassified operation failure | ui | Unknown or unstructured failure | Retry; record the operation and code |
| MM-AUTH-001 | Session required | auth | Missing or expired session | Sign in online |
| MM-AUTH-002 | Access denied | auth | Inactive membership or denied permission | Check active organization and permissions |
| MM-AUTH-003 | Offline credential unavailable | auth | Missing or expired offline credential | Renew online |
| MM-AUTH-004 | Authorization context changed | auth | Account or organization changed during operation | Restart in current context |
| MM-AUTH-005 | Login rejected | auth | Credentials or session unavailable | Check credentials and connection |
| MM-CLAIM-001 | Claim requires internet | claim | Backend provisioning unavailable offline | Connect and retry the same claim |
| MM-CLAIM-002 | Claim ownership conflict | claim | Claim cannot be granted to this organization | Check organization; keep receipt |
| MM-CLAIM-003 | Claim provisioning not confirmed (operation fallback) | claim | Backend response or receipt invalid | Retry idempotently without deleting receipt |
| MM-CLAIM-004 | Registration identity proof unavailable | claim | Missing or malformed proof after time preflight | Check security time policy and signed bootstrap |
| MM-CLAIM-005 | Registration proof signature invalid | claim | Identity binding or signature invalid | Stop registration; check firmware and pinned identity |
| MM-CLAIM-006 | Claim outcome uncertain | claim | Interrupted receipt transfer or confirmation | Retry with saved receipt |
| MM-CLAIM-007 | Receipt persistence failed | claim | Local database unavailable | Check browser storage; preserve existing receipt |
| MM-CLAIM-008 | Registration identity or state changed | claim | Different device or concurrent registration | Select device again |
| MM-CLOCK-001 | Signed time bootstrap unavailable | clock | Backend offline or unhealthy clock | Check backend clock health and internet |
| MM-CLOCK-002 | Time challenge invalid | clock | Malformed or wrong device challenge | Reconnect to selected device |
| MM-CLOCK-003 | Signed time proof rejected | clock | Authorization or proof rejected | Check active membership and backend time health |
| MM-CLOCK-004 | Node did not confirm signed time | clock | Proof rejected or response lost | Retry bootstrap; check firmware |
| MM-CLOCK-005 | Security time policy not satisfied | clock | No accepted secure or Alpha time | Check firmware policy and configured time source |
| MM-CLOCK-006 | Phone time evidence unavailable | clock | Malformed snapshot or boot discontinuity | Retry after checking device continuity |
| MM-BLE-001 | BLE connection interrupted | ble | Link unavailable or disconnected | Enable Bluetooth; move closer and retry |
| MM-BLE-002 | Malformed or unexpected protocol response | ble | Incompatible frame or peer | Check PWA and firmware versions |
| MM-BLE-003 | Unsupported protocol version | ble | Version or capability mismatch | Update compatible firmware and PWA |
| MM-BLE-004 | Web Bluetooth unavailable | ble | Browser or HTTPS unsupported | Use supported browser on HTTPS |
| MM-BLE-005 | Bluetooth permission denied | ble | Browser permission or security policy | Check browser permissions |
| MM-BLE-006 | BLE operation timed out | ble | Unresponsive link or peer | Move closer and retry |
| MM-BLE-007 | Bluetooth unavailable | ble | Adapter disabled or unavailable | Enable Bluetooth |
| MM-BLE-008 | Node authenticity not confirmed | ble | Pinned identity signature failed | Stop sync; check trusted node record |
| MM-BLE-009 | Trusted node key unavailable offline | ble | No cached backend identity | Synchronize once online |
| MM-SYNC-001 | ACK not confirmed | sync | Missing rejected or mismatched ACK | Retry; persisted records remain local |
| MM-SYNC-002 | Sequence gap remains | sync | Noncontiguous durable records | Retry; inspect sequence continuity |
| MM-SYNC-003 | Node cleanup not confirmed | sync | COMPACT or subsequent status unavailable | Retry next visit; do not repeat committed ACK |
| MM-SYNC-004 | Node metadata update not confirmed | sync | Metadata rejected or response lost | Retry next online visit |
| MM-SYNC-005 | Node storage pressure | sync | Node full or almost full | Synchronize and clean up node |
| MM-SYNC-006 | Node registration required | sync | Unclaimed node | Ask active administrator to register |
| MM-STORAGE-001 | Durable local persistence failed | storage | Database quota or transaction failure | Check browser storage; no ACK before persistence |
| MM-STORAGE-002 | Sync event persistence failed after ACK | storage | Outbox write interrupted | Preserve copied observations; retry event capture |
| MM-UPLOAD-001 | Observation upload not confirmed (operation fallback) | upload | Backend or network failure | Retry upload; local copy remains |
| MM-UPLOAD-002 | Time evidence upload not confirmed | upload | Invalid missing or rejected receipt | Retry; preserve durable evidence |
| MM-UPLOAD-003 | Observation upload conflict | upload | Backend rejected conflicting immutable event | Inspect conflict with authorized diagnostics |
| MM-UPLOAD-004 | Upload waiting for network | upload | No internet | Reconnect; local data remains |
| MM-ADMIN-001 | Form validation failed | admin | Invalid submitted fields | Correct marked fields |
| MM-ADMIN-002 | Concurrent membership change | admin | Stale optimistic version | Reload current permissions before saving |
| MM-ADMIN-003 | Admin operation failed (operation fallback) | admin | Request rejected or unavailable | Retry after checking session and organization |
| MM-ADMIN-004 | Photo operation failed (operation fallback) | admin | Invalid image denied access or network failure | Check image format size and permission |
| MM-BACKEND-400 | Invalid request | backend | Malformed or invalid input | Check documented request contract |
| MM-BACKEND-401 | Authentication required | backend | Missing or expired authentication | Sign in |
| MM-BACKEND-403 | Access denied | backend | Permission or CSRF denied | Check session CSRF and organization |
| MM-BACKEND-404 | Resource unavailable | backend | Missing or hidden resource | Check authorized resource selection |
| MM-BACKEND-405 | Method not allowed | backend | Unsupported HTTP method | Check API contract |
| MM-BACKEND-409 | Request conflict | backend | Concurrent or immutable data conflict | Reload current state before retry |
| MM-BACKEND-413 | Request too large | backend | Upload or request size exceeds limit | Reduce request size |
| MM-BACKEND-415 | Unsupported content type | backend | Unsupported media format | Check API content type |
| MM-BACKEND-429 | Request limit reached | backend | Bounded abuse protection | Wait before retrying |
| MM-BACKEND-503 | Service temporarily unavailable | backend | Capacity or dependency unavailable | Retry later; check service health |
| MM-BACKEND-500 | Server request failed | backend | Unexpected internal error | Report code and operation; no raw details |
| MM-BLE-010 | Firmware sequence rejected | ble | Invalid sequence | Reconnect and retry synchronization |
| MM-BLE-011 | Firmware record rejected | ble | Invalid record | Check firmware and protocol compatibility |
| MM-BLE-012 | Firmware clock unavailable | ble | No trusted security clock | Check time policy and signed bootstrap |
| MM-BLE-013 | Firmware authorization expired | ble | Session or credential expired | Renew credential online |
| MM-BLE-014 | Firmware replay rejected | ble | Stale challenge or proof | Reconnect with a fresh challenge |
| MM-BLE-015 | Firmware credential rejected | ble | Credential invalid | Renew credential online |
| MM-BLE-016 | Firmware proof rejected | ble | Proof invalid | Reconnect; check device keys |
| MM-BLE-017 | Firmware state invalid | ble | Operation not permitted in current state | Reconnect and check claim state |
| MM-BLE-018 | Firmware operation failed | ble | Internal firmware failure | Retry; inspect authorized device diagnostics |
| MM-CLOCK-007 | Alpha phone clock rejected | clock | Phone time rejected or unavailable in opted-in Alpha mode | Check phone UTC and explicit firmware policy |
| MM-CLOCK-008 | Node reboot during phone time evidence | clock | Boot counter or uptime discontinuity | Retry evidence collection on stable boot |
| MM-UPLOAD-005 | Historical pre-claim time evidence not confirmed | upload | Historical continuity cannot be authorized | Preserve evidence; check historical deployment continuity |
| MM-UPLOAD-006 | Collector sync report upload not confirmed | upload | Missing rejected or mismatched report receipt | Retry report queue in the original authorized context |
| MM-API-001 | Node claim: request validation rejected | NodeController#claim | Request validation rejected | Check the request contract |
| MM-API-002 | Node claim: state conflict | NodeController#claim | State conflict | Reload authorized state; preserve original durable data |
| MM-API-003 | Node claim: service unavailable | NodeController#claim | Service unavailable | Check backend dependencies and retry later |
| MM-API-004 | Node display name: request validation rejected | NodeController#update | Request validation rejected | Check the request contract |
| MM-API-005 | Node display name: state conflict | NodeController#update | State conflict | Reload authorized state; preserve original durable data |
| MM-API-006 | Node display name: service unavailable | NodeController#update | Service unavailable | Check backend dependencies and retry later |
| MM-API-007 | Signed time bootstrap: request validation rejected | NodeTimeController#issue | Request validation rejected | Check the request contract |
| MM-API-008 | Signed time bootstrap: state conflict | NodeTimeController#issue | State conflict | Reload authorized state; preserve original durable data |
| MM-API-009 | Signed time bootstrap: service unavailable | NodeTimeController#issue | Service unavailable | Check backend dependencies and retry later |
| MM-API-010 | Observation ingest: request validation rejected | ObservationController#ingest | Request validation rejected | Check the request contract |
| MM-API-011 | Observation ingest: state conflict | ObservationController#ingest | State conflict | Reload authorized state; preserve original durable data |
| MM-API-012 | Observation ingest: service unavailable | ObservationController#ingest | Service unavailable | Check backend dependencies and retry later |
| MM-API-013 | Device registration: request validation rejected | DeviceController#register | Request validation rejected | Check the request contract |
| MM-API-014 | Device registration: state conflict | DeviceController#register | State conflict | Reload authorized state; preserve original durable data |
| MM-API-015 | Device registration: service unavailable | DeviceController#register | Service unavailable | Check backend dependencies and retry later |
| MM-API-016 | Offline credential issuance: request validation rejected | DeviceController#issue | Request validation rejected | Check the request contract |
| MM-API-017 | Offline credential issuance: state conflict | DeviceController#issue | State conflict | Reload authorized state; preserve original durable data |
| MM-API-018 | Offline credential issuance: service unavailable | DeviceController#issue | Service unavailable | Check backend dependencies and retry later |
| MM-API-019 | Time anchor ingest: request validation rejected | TimeEvidenceController#ingest | Request validation rejected | Check the request contract |
| MM-API-020 | Time anchor ingest: state conflict | TimeEvidenceController#ingest | State conflict | Reload authorized state; preserve original durable data |
| MM-API-021 | Time anchor ingest: service unavailable | TimeEvidenceController#ingest | Service unavailable | Check backend dependencies and retry later |
| MM-API-022 | Boot evidence ingest: request validation rejected | TimeEvidenceController#boot | Request validation rejected | Check the request contract |
| MM-API-023 | Boot evidence ingest: state conflict | TimeEvidenceController#boot | State conflict | Reload authorized state; preserve original durable data |
| MM-API-024 | Boot evidence ingest: service unavailable | TimeEvidenceController#boot | Service unavailable | Check backend dependencies and retry later |
| MM-API-025 | Phone evidence ingest: request validation rejected | TimeEvidenceController#phone | Request validation rejected | Check the request contract |
| MM-API-026 | Phone evidence ingest: state conflict | TimeEvidenceController#phone | State conflict | Reload authorized state; preserve original durable data |
| MM-API-027 | Phone evidence ingest: service unavailable | TimeEvidenceController#phone | Service unavailable | Check backend dependencies and retry later |
| MM-API-028 | Collector sync report: request validation rejected | CollectorSyncEventController#report | Request validation rejected | Check the request contract |
| MM-API-029 | Collector sync report: state conflict | CollectorSyncEventController#report | State conflict | Reload authorized state; preserve original durable data |
| MM-API-030 | Collector sync report: service unavailable | CollectorSyncEventController#report | Service unavailable | Check backend dependencies and retry later |
