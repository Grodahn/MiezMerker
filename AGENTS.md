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

## Agent execution and token efficiency

Minimize redundant context, tool output, and agent round trips **without weakening
required contracts, security reviews, tests, or physical validation**. Use these
defaults for implementation, review-and-repair, and CI investigations.

- **One deliberate source pass:** Identify relevant files and contracts early,
  verify paths with scoped `rg --files`/`git ls-files`, and read the necessary
  normative material completely when correctness demands it. Do not reread an
  unchanged large file in full merely to regain context. Maintain a brief working
  map of symbols, files, interfaces, and assumptions; afterward prefer `rg -n`,
  bounded line ranges, and `git diff -- <path>`. Reread when the file changed,
  a contract question remains unresolved, or correctness requires wider context.
- **Bound displayed command output:** Normally keep each tool/terminal response
  within about **8 KiB or 100–150 lines**. Redirect verbose commands to local
  files, then show status, summary, focused excerpts, or a small tail. Narrow
  queries *before* retrieving large outputs. Expand the bounds only for a
  specific diagnostic or required evidence; never hide a relevant error.
- **Build, firmware, hardware, and test logs:** Retain the complete logs and
  exit codes locally. Start with the compact test runner, build summary, failing
  assertion, exception, or relevant serial-log window. For a failure, locate the
  first actionable error and nearby context instead of dumping/truncating an
  entire log. Do not use pipes/filters that turn failed commands into success.
- **Compact GitHub/CI inspection:** Request only check name, state, conclusion,
  and needed identifiers for polling. Avoid verbose job details and successful
  job logs. Check running CI at sensible intervals (normally **at least 60
  seconds apart**) rather than busy-polling; inspect only failed steps and
  focused log sections unless wider evidence is genuinely needed.
- **Scoped reviews, one integrated closeout:** During implementation, review
  the changed files, their direct dependencies, and relevant tests. Once the
  behavior stabilizes, perform **one integrated issue-contract/security review**
  and the required broader verification. Fix confirmed in-scope findings and
  rerun affected checks; do not automatically restart a full repository-wide
  review after each small fix. Revisit the full scope if a fix changes contracts,
  security boundaries, or other high-risk behavior.
- **Avoid avoidable exploration:** Check real paths before running commands,
  reuse previously established paths/results, and batch independent focused
  inspections when useful. Treat already-injected `AGENTS.md` as read; open it
  again only to edit it, resolve a conflict, or verify a materially changed copy.
- **Lightweight efficiency feedback:** At task completion, briefly note any
  unusual repeated full-file reads, oversized tool outputs, redundant test/CI
  loops, or incorrect-path commands and how to avoid them next time. Do not
  invent measured token counts or let this report become another long narrative.

These are **output and repetition budgets, not evidence or test-coverage
budgets**. Owning issue acceptance criteria, security/authentication gates,
firmware-on-device proof, and required regressions always take precedence.

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
