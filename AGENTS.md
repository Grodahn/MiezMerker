# Development and testing

Follow the ordered **0 → A → R → B → C** process in
[docs/testing.md](docs/testing.md):

- **0 — Contract and risk map (before code):** Identify the issue contract,
  changed interfaces and direct consumers; map security, concurrency, offline
  persistence, cross-tenant and data-loss risks where relevant. Identify focused
  tests and the affected-domain completion selection. Check for parallel PRs
  touching the same boundaries before planning integration.
- **A — Focused implementation:** Implement and run the smallest useful tests;
  after a failure, rerun its test first, then the directly affected group.
  Typecheck early when modifying TypeScript interfaces. Do not run broad
  regressions after each edit.
- **R — Integrated review and repair:** **Before** the completion regression,
  review the entire issue diff, direct dependencies, acceptance criteria and
  relevant security/race conditions. Consolidate confirmed in-scope fixes into
  one repair phase. Rerun tests impacted by repairs, not every broad suite.
- **B — Local completion:** After review fixes stabilize, run the affected
  domain regression, required build/typecheck/API checks and relevant browser
  tests. Revalidate when subsequent changes invalidate those results.
- **C — GitHub CI and closeout:** Confirm the existing full CI on the **final
  PR head commit**, including PostgreSQL/end-to-end coverage. Diagnose failed
  checks narrowly; never mask or skip required gates. Once green, finish without
  redundant reruns, reviews or idle post-green polling.

These stages are an **execution order, not a test-coverage reduction**.

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
- **Compact GitHub/CI inspection:** Request only check name, state, conclusion
  and identifiers. Prefer a supported watch mechanism (for example,
  `gh pr checks --watch`) over repetitive fixed `sleep 60` polling; if
  watching is unavailable, poll at sensible bounded intervals while avoiding
  unnecessary idle waiting. Inspect failed jobs and focused log sections, not
  all successful job logs. A green final-head CI requires no additional
  arbitrary waiting or another full verification solely for reassurance.
- **Early risks, one integrated review:** Investigate security/concurrency,
  permissions, immutable data, time/boot boundaries and recovery paths *before*
  they become late review surprises. After implementing with focused tests,
  conduct **one substantive issue-contract/security review before B**, repair
  confirmed findings, then complete the affected-domain regression. Reopen
  wider review only if a fix changes contracts, security boundaries, or other
  high-risk behavior; otherwise retest the affected slice.
- **Parallel changes and integration:** Keep independent issues on separate
  branches. Before B, incorporate relevant already-merged upstream changes
  once and check the actual integration diff. If main advances later, assess
  whether it affects the PR before rebasing or repeating regressions. Never
  claim green CI from a different head SHA.
- **Compact agent prompts:** Task prompts should primarily specify the GitHub
  issue, task-specific boundaries/dependencies, review-and-repair expectation,
  merge policy and final evidence. Follow this document and the short template
  in `docs/testing.md`; do not restate the full test/review instructions in
  each prompt. If AGENTS.md was injected, treat it as already read.
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
