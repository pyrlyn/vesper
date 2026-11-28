---
status: in progress
priority: P1
complexity: 2
readiness: 60%
agent: Claude Code / claude-opus-5-5
---

# T29. Green CI on main

CI on `main` must stay green: `./gradlew check` on every target (macOS, Linux and Windows), which covers the core and the Kotlin/Native hosts. Required checks on `main` are named and documented. A failure on one platform must not be hidden by skipping the job.

Acceptance:
- [ ] The required GitHub checks are listed in `PLAN.md` and stay green on `main`.
- [ ] macOS, Linux and Windows jobs all run, with an explicit skip only when `SKIP_CI_TESTS` or `MACOS_CI_DISABLED` is set.

## Execution plan

`main` is green (the last four `ci` runs passed) but has no branch protection or ruleset, so no check is required yet.

1. `ci.yml`:
   - On `push`, the concurrency group takes the commit SHA. With one group for `main`, a newer queued run cancelled the queued one before it, so a burst of merges left commits on `main` that never ran CI.
   - When `MACOS_CI_DISABLED` turns the macOS job into a no-op, that job prints a notice, as `SKIP_CI_TESTS` already does, so the skip shows in the run.
   - The header names the required checks.
2. `PLAN.md` §6:
   - Lists the required checks: `macos-14 (macosArm64)`, `ubuntu-24.04 (linuxX64)`, `windows-2025 (mingwX64)`, `jactionlint` and `fuzz and Unicode (linuxX64)`.
   - Says how GitHub counts a job skipped by its `if` (as passing).
   - Drops the stale `pipeline.yml` line: the workflow was removed in 1669905, and SonarCloud runs through its GitHub app.
3. With the creator's OK, a ruleset on `main` requires those checks from GitHub Actions.
4. Verify:
   - jactionlint passes;
   - the PR run is green;
   - the ruleset lists the checks;
   - the first `main` run after the merge is green.
