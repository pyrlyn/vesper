---
status: todo
priority: P1
complexity: 2
readiness: 0%
agent:
---

# T29. Green CI on main

CI on `main` must stay green: `./gradlew check` on every target (macOS, Linux and Windows), which covers the core and the Kotlin/Native hosts. Required checks on `main` are named and documented. A failure on one platform must not be hidden by skipping the job.

Acceptance:
- [ ] The required GitHub checks are listed in `PLAN.md` and stay green on `main`.
- [ ] macOS, Linux and Windows jobs all run, with an explicit skip only when `SKIP_CI_TESTS` or `MACOS_CI_DISABLED` is set.
