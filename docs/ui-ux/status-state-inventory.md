# #65 status-state inventory (Round 2 design gate)

Foundation: [approved Round 2 mockup](round-2-approved-mockup.webp),
[UX and asset contract](approved-round-2.md), and #64 tokens, Card, Button,
Graphic and centralized assets. These variants retain the quiet rounded cards,
semantic registry graphics, short German copy and visible next action from Round 2.
This inventory defines the variants before implementation; no new screen flow is added.

| Variant | Graphic / example title | Meaning and action |
| --- | --- | --- |
| Loading / progress | sync / „Napf wird verbunden …“ | Indeterminate unless a real total is known. Explain phase; after a long wait show help. Existing cancel remains available; never invent a percentage or a timeout result. |
| Empty | cat / „Noch keine Katzen oder Chips vorhanden“ | Confirmed empty result, not unavailable data. Existing list/navigation actions remain. |
| Empty sites / unassigned | feedingSite / „Keine Futterstellen vorhanden“ | No invented assignment, history or photo. Setup action only through existing handlers. |
| Error | error / „Verbindung unterbrochen“ | Concrete recovery and retry for retryable problems; permission/session problems direct to settings/sign-in. |
| Bluetooth selection | bluetooth / „Geräteauswahl beendet“ | Browser NotFoundError cannot prove cancellation versus an empty chooser. Explain both; do not report hardware failure or a discovered empty device list. Explicit cancellation is its own neutral state. |
| Bluetooth unavailable | bluetooth / „Bluetooth nicht verfügbar“ | Enable Bluetooth where the reported error supports this; unsupported browser, denied permission and unreachable device have separate messages. |
| Offline | offline / „Upload wartet auf Verbindung“ | Offline BLE still requires valid offline authorization. No server-success claim. |
| Local success | syncSuccess / „Napf ausgelesen“ | Only existing `fertig` (durable local records and completed ACK). Backend state stays independent. |
| Upload pending / partial | offline / „Upload noch ausstehend“ | Show existing pending/confirmed counts separately. A failed upload does not undo the local copy. |
| Backend success | syncSuccess / „Daten an Server übertragen“ | Only existing complete upload state with zero pending records and confirmed uploaded records. Empty outbox with no uploaded records says „Keine ausstehenden Uploads“. |
| Credentials / session | error / „Bitte erneut anmelden“ | Invalid, missing or expired offline credentials are not transport failures; preserve the existing gate. |
| Claim / incomplete setup | info / „Einrichtung noch offen“ | UI only; existing role restrictions, claim receipts and provisioning handlers remain authoritative. |
| Unknown clock / unavailable data | info / „Keine verlässliche Sichtungszeit“ | Clearly explains UNKNOWN or invalid clock. Existing activity API aggregates both and does not expose the individual clock code. Never replace observation time with receipt time. Load failure must not appear as an empty list. |

Accessibility: polite live regions for ordinary updates, assertive for
errors; actions outside the announcement, native buttons/links, visible focus,
text alongside decorative graphics, progressbar with an accessible name,
no auto-focus on updates, and no movement with reduced motion. Loading help is
informational and does not cancel or complete operations.

Extension contract for #76: presentation accepts optional scope labels, real
completed/total progress and independent state instances. A future caller can
render aggregate/partial and per-node outcomes without changing transport or
adding orchestration here. #67 result context, multi-node scheduling and new
business state are explicitly outside #65.
