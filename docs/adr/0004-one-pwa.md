# 0004 — One PWA for collector and management
Status: Accepted.

There is exactly one frontend project in app. Collector #8 and management #11
are feature boundaries within this application, not separate builds/deployments.
Routes share the shell; collector-only platform adapters and persistence remain
isolated from management. This avoids two identities/clients/offline stores and
preserves a coherent phone/desktop experience.
