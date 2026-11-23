---
status: todo
priority: P2
complexity: 2
readiness: 0%
agent:
---

# T900. Dependency advisory updates

The creator authorized dependency updates across the audit. Update only vulnerable dependencies and the resolver-required graph without weakening existing guards or advisory policy.

Execution plan: reproduce the advisory audit on the Gradle/Kotlin dependency graph; apply compatible package-manager updates; review the lockfile diff; run advisory regression checks and the project test/lint/format gates; publish a PR and merge only after required checks pass. No custom dependency implementation is needed. Rollback is a revert of the dependency-update commit.

Acceptance:
- [ ] The advisory audit runs on the Kotlin/Gradle graph and known vulnerabilities are addressed or waived in writing.
- [ ] `./gradlew check` passes after the updates.
