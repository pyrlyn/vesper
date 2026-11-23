---
status: in progress
priority: P2
complexity: 1
readiness: 100%
agent: Claude Code / claude-opus-5-5
---

# T23. Guard the interner marks/sweep contract against interleaved interns

The cluster and style interner must not reclaim a live id if a caller interns between taking marks and sweeping. `sweep` must skip ids at or beyond the marks' length (or the lengths are asserted), so a missed mark cannot free a still-used style or cluster.

## Findings

- T6 ported the fix of the Rust tree (c7bc167): `Marks` carries the table's insert count, and `Interner.sweep` frees nothing when an intern came after the marks were taken. `InternerTest.internsBetweenMarksAndSweepSurvive` covers an appended id and a reused one.
- The guard is stronger than the length check this card asks for: a freed id handed out again lies inside the marks' length, so a length check alone would still free it.
- Not yet covered: the public tables (`StyleTable`, `ClusterTable`), which the terminal layer and the C ABI call, have no test of the contract, and the public `Marks` type does not state it.

## Execution plan

1. `scull-grid/src/commonMain/kotlin/scull/grid/Interner.kt`: state the contract on `Marks` and say in the stamp comment why a length check would not do.
2. `scull-grid/src/commonTest/kotlin/scull/grid/TablesTest.kt`: an interleaved intern between marks and sweep through `StyleTable` (appended id) and `ClusterTable` (reused id); fresh marks still sweep.
3. Verify: the tests fail with the stamp check removed; `./gradlew check` on macOS; the linuxX64 `scull-grid` test binary in a Colima container.

## Acceptance

- [x] The interner contract is documented and tested in `scull-grid`, including an interleaved intern between marks and sweep.
- [x] `./gradlew check` passes.
