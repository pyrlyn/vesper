# T26. Done

Branch `t26-grid-test-gaps`, stacked on `t24-harness-wrap`.

## What was done

- `scull-grid` `GridTest.aLeadingSpacerAtTheRowEndSurvivesTheMoveIntoHistory`: a wrapped row with a LEADING_SPACER at its last column scrolls into history and is compacted. The row keeps the cell, its flag, its emptiness, its text and the wrap flag, and the spacer stays the last stored cell.
- `scull-term` `TerminalTest`: the three checks of the Rust tests (622f9db) that T7 had not ported. The leading spacer is an empty cell and the screen reads `abcd.` / `中...`. A wide character from the pending wrap leaves no LEADING_SPACER and wraps the row. With autowrap off the row holds no LEADING_SPACER and is not wrapped.
- `scull-bench` `GridBench`: the scrollback-memory table is the median of five passes in one process, with the range beside it. Each pass keeps its three grids alive until its last reading, as before, and the next pass takes its own first reading after they are dropped.
- `docs/benchmarks/grid.md`: the five-pass figures. Blank 75 B (75–76), plain 675 B (675–676), SGR-heavy 1062 B (1061–1062), the same on macOS and linuxX64. The blank row of T6 (77–78 B) carried about 260 KB allocated once per process. The first pass still charges it to the first reading, and the median leaves it out.

The median stub benchmark of the Rust fix was already ported by T3 (`Baseline.kt`, `STUB_RUNS = 9`).

## How it was checked

- Failing-then-passing, `scull-grid`: with `Row.storedLength` trimming trailing cells that are empty in the fill's style, instead of equal to the fill's bits, only the new grid test failed. Restored, it passes.
- Failing-then-passing, `scull-term`: with the `set` of the LEADING_SPACER in `Print.kt` removed, `aWideCharacterPastTheLastColumnLeavesALeadingSpacerAndWraps` failed. Restored, it passes.
- `./gradlew ktlintFormat check` on macOS: BUILD SUCCESSFUL.
- linuxX64 test binaries in Colima (Ubuntu 24.04, amd64): `scull-grid` 61 passed, `scull-term` 50 passed.
- mingwX64: `scull-grid`, `scull-term` and `scull-bench` compile.
- `scull-bench grid`, release build: three runs on macOS and two on linuxX64 gave the figures above. With a temporary print, each pass read blank 74–76 B, with the high figure in the first pass. The old single-pass bench read 77 B for the blank row on the same tree.
