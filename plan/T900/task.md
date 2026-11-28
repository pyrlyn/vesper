---
status: in progress
priority: P2
complexity: 2
readiness: 100%
agent: Claude Code / claude-opus-5-5
---

# T900. Dependency advisory updates

The creator authorized dependency updates across the audit. Update only vulnerable dependencies and the resolver-required graph without weakening existing guards or advisory policy.

Execution plan: reproduce the advisory audit on the Gradle/Kotlin dependency graph; apply compatible package-manager updates; review the lockfile diff; run advisory regression checks and the project test/lint/format gates; publish a PR and merge only after required checks pass. No custom dependency implementation is needed. Rollback is a revert of the dependency-update commit.

Acceptance:
- [ ] The advisory audit runs on the Kotlin/Gradle graph and known vulnerabilities are addressed or waived in writing.
- [ ] `./gradlew check` passes after the updates.

## Execution plan

GitHub's dependency graph is off and Dependabot shows no alerts, so the audit runs here.

1. List every artifact the build resolves: `./gradlew --write-verification-metadata sha256 --dry-run help`, without committing the file.
2. Query OSV (https://api.osv.dev/v1/querybatch) for each coordinate.
3. For each hit, find the path that pulls it in and whether a newer parent release fixes it.
4. Fix with the narrowest change, a catalog version plus a constraint where the parent has no fixed release. Waive in writing what never runs.
5. Record the audit and how to rerun it in `toolchain.md`.
6. Verify:
   - the gate with `--rerun-tasks`;
   - ktlint still reports a planted violation;
   - the audit rerun leaves only the waiver.
