# 0009 — Stable Node identity, explicit claim and reset lifecycle
Status: Accepted boundary; provisioning and lifecycle implementation belong to #18.

Distinguish hardware identity, Organization ownership/claim and reset incarnation.
Claims and factory reset must bind or invalidate credentials and avoid reusing
observation identities. Keep historical deployments and prior raw records tied
to their original Organization; a new claim cannot grant access to old tenant
data. Factory provisioning/trust anchors, transfer rules and reset ceremony are
specified before lifecycle adapters are implemented. No claiming UI in #2.
