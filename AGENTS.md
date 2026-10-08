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

Keep all test expectations and coverage. New backend test classes must receive
appropriate JUnit tags; new CTests should receive relevant labels. Never treat
an unexpectedly empty test selection as a successful test run.
