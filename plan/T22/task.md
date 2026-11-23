---
status: in progress
priority: P2
complexity: 1
readiness: 100%
agent: Claude Code / claude-opus-5-5
---

# T22. No-op link clears are generation-neutral

Clearing a row link that is already empty must not bump the row generation. Otherwise a no-op clear looks like damage and forces a repaint. Done when a test shows that a second clear of an empty link leaves the generation unchanged, and that a real link change still bumps it.

## Findings

- T6 ported the fix of the Rust tree (b65fa68): `Row.clearLinks` bumps only when `dropLinks` removed coverage, and `RowTest.clearingLinksWhereNoneExistIsGenerationNeutral` checks a clear on a row that never had a link.
- Not yet tested: a second clear after the link is gone, a clear next to a link that does not overlap it, and an empty or out-of-row range.
- The other half of the Rust fix, a row narrowed so that only links fall off its tail, has no Kotlin counterpart: rows do not change width before T11 (resize and reflow), which must carry it.
- Nothing outside `scull-grid` calls `clearLinks` or `setLink` yet; OSC 8 arrives with T13.

## Execution plan

1. `scull-grid/src/commonTest/kotlin/scull/grid/RowTest.kt`: extend the test to the cases above, and check that a full clear of a real link and a new link still bump.
2. `scull-grid/src/commonMain/kotlin/scull/grid/Row.kt`: make the comment in `clearLinks` state the reason that holds in this port.
3. Verify: `./gradlew check` on macOS, the linuxX64 `scull-grid` test binary in a Colima container.

## Acceptance

- [x] The grid's link-clear path is generation-neutral for no-ops, with tests in the Kotlin/Native core.
- [x] `./gradlew check` passes.
