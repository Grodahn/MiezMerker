# Development and testing

Follow the three stages in [docs/testing.md](docs/testing.md).

- **A — Implementation loop:** Run the smallest affected class, method, file,
  or CTest selection. After a fix, rerun the failing test first. Use the compact
  runner and inspect full logs only when diagnostics require them. Do not run
  the whole repository matrix after every edit.
- **B — Local completion:** Once implementation stabilizes, run the affected
  domain regression once. Add typechecks/build/API consistency checks when
  interfaces change. Investigate and fix confirmed in-scope failures.
- **C — GitHub CI:** Existing CI runs the broader regression matrix, including
  PostgreSQL and end-to-end tests. Do not repeatedly duplicate it locally
  without a concrete risk or failure. For failed CI jobs, inspect failed tests
  and relevant logs; do not download every successful job log.

## Preferred test commands

From the repository root, prefer the compact runner for local and agent-driven
test loops (Python 3.10+; use `python3` instead of `python` if required):

```text
python scripts/test.py backend --test NodeDisplayNameTest
python scripts/test.py backend --domain node
python scripts/test.py backend --domain node auth
python scripts/test.py frontend src/collector
python scripts/test.py frontend src/management
python scripts/test.py firmware --label ble
```

Configure and build firmware before CTest; the runner does not build it.
Use direct Maven/Vitest/CTest commands when a diagnostic or verification step
requires options the runner does not support. Do not bypass the compact runner
just to print successful logs. Full output and test reports are retained under
`test-results/compact/`; inspect them only as needed for failures.

Keep all test expectations and coverage. New backend test classes must receive
appropriate JUnit tags; new CTests should receive relevant labels. Never treat
an unexpectedly empty test selection as a successful test run. Preserve native
failure exit codes, and do not mask failures with shell pipelines or log filters.

## Field-PWA visual assets and UX

For MiezMerker field-PWA UI changes (#63 and subtickets), follow
[the approved Round-2 design and asset contract](docs/ui-ux/approved-round-2.md).
Keep the four-area mobile Bottom Navigation and context-free Home; show Node,
FeedingSite and last-seen Cat context only after a confirmed read.

**Every replaceable graphic** (brand/logo, illustrations, icons, avatars,
FeedingSite/Node photos where available, placeholders and status imagery)
must be referenced via the shared centralized asset registry/adapter from
#64, not hardcoded as image paths, data URIs or inline brand SVGs across
components. Changing a graphic must require only changing an asset file or
central mapping, never screen business logic. Use reliable placeholders
instead of inventing animal photos or backend image fields. Preserve offline,
auth/tenant, BLE/ACK and server-upload status truth.
