---
status: in progress
priority: P2
complexity: 2
readiness: 100%
agent: Claude Code / claude-opus-5-5
---

# T26. Close grid-layer test gaps

Cover the end-of-row wide-wrap path for a leading spacer cell, and average more than a single pass in the scrollback-memory benchmark so baseline numbers are not noisy.

## Findings

- The Rust fix (622f9db) added terminal tests for the LEADING_SPACER cell and a median stub benchmark. T3 already ported the median stub (`Baseline.kt`, `STUB_RUNS = 9`); T7 ported the terminal tests, but without three of their checks: the spacer cell is empty, the pending-wrap row is wrapped, and the autowrap-off row is not wrapped and holds no LEADING_SPACER.
- `vesper-grid` has no test with a LEADING_SPACER cell. The grid's part of the path is storage: the terminal stores the spacer with `Row.set`, and `Row.compact` trims trailing cells equal to the fill when the row enters history. The spacer differs from the fill only by its flag, so a trim that compared content instead of bits would drop it.
- The scrollback-memory table in `GridBench.kt` is one pass. `docs/benchmarks/grid.md` notes that a grid dropped between two readings skewed a plain row from 675 B to 619 B, so passes must not drop a grid between the readings of one pass.

## Execution plan

1. `vesper-grid` tests: a wrapped row with a LEADING_SPACER at its last column keeps the cell, its flag, its emptiness and the wrap flag after it scrolls into history and is compacted. Prove it fails against a trim that compares content (`Cell.isEmpty`) instead of bits.
2. `vesper-term` `TerminalTest`: add the three missing checks. Prove the spacer test fails without the `set` in `Print.kt`.
3. `vesper-bench` `GridBench.kt`: run the memory sample in several passes in one process, each pass keeping its three grids alive until its last reading, and report the median with the range. Update `docs/benchmarks/grid.md` with a fresh run.
4. Verify: `./gradlew ktlintFormat check` on macOS; the linuxX64 test binaries of `vesper-grid` and `vesper-term` in a Colima container; the bench on macOS.

## Acceptance

- [x] The wide-wrap spacer path has a failing-then-passing test in `vesper-grid`.
- [x] The scrollback-memory benchmark reports a stable average, not a single pass.
- [x] `./gradlew check` passes.
