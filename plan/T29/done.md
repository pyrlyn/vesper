# T29. Done

Merged through https://github.com/pyrlyn/vesper/pull/50.

- **Ruleset.** The ruleset `main: required checks (T29)` (id 28291576) is active on the default
  branch, with no bypass actors. It requires five checks from GitHub Actions (integration 15368):
  - `macos-14 (macosArm64)`
  - `ubuntu-24.04 (linuxX64)`
  - `windows-2025 (mingwX64)`
  - `jactionlint`
  - `fuzz and Unicode (linuxX64)`

  A branch does not have to be up to date with `main` to merge.
- **Every commit on `main` gets a run.** In `ci.yml`, a push now gets a concurrency group per
  commit. Before, the single `main` group let each newly queued run cancel the one queued before
  it: of seven merges on 26 November, six never ran CI.
- **Skips show in the run.** The `MACOS_CI_DISABLED` no-op now leaves a notice, as `SKIP_CI_TESTS`
  already did. Those two remain the only explicit switches.
- **`PLAN.md` §6:**
  - lists the required checks and says that renaming a job means changing the ruleset with it;
  - describes how a draft, a docs-only change and each switch skip;
  - drops the stale `pipeline.yml` line.
- **jactionlint baseline:** its last entry, for the removed `pipeline.yml`, is gone.

Reused: the existing `SKIP_CI_TESTS` notice step is the pattern for the macOS one. No new tool or
dependency.

How it was checked:
- `mise x -- jactionlint` passes with an empty baseline.
- `gh api repos/pyrlyn/vesper/rules/branches/main` lists the five checks from ruleset 28291576.
- PR #50 showed `BLOCKED` while its required checks were still running, then passed CI.
