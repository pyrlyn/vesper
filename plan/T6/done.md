# T6. Grid and scrollback — done

## What was done

- `vesper-grid` ports the Rust crate from 69d9be4 to Kotlin/Native. Resize and reflow stay for T11.
  - `Cell` is an 8-byte value class over a `Long`: a code point or cluster id, a style id and layout flags. A row of cells is a `LongArray`, with no object per cell.
  - `StyleTable` and `ClusterTable` (65 536 entries each, clusters of up to 32 code points) share one capped `Interner` with mark-and-sweep reclaim.
  - `Row` has a stable `RowId`, a generation, a fill cell that stands for every unwritten column, wide-character fix-ups and link spans kept out of line. A blank or uniformly erased row holds no cell array.
  - `Grid` is one power-of-two ring of lazily created rows shared by the screen and scrollback, capped at 131 072 rows. A scroll moves `zero` and recycles one row. Region scrolls rotate in place. The viewport is an offset into history.
- `vesper-bench grid` prints bytes per scrollback row and the cost of a scrolled line. The numbers and how they are measured are in `docs/benchmarks/grid.md`:
  - A scrollback row at 120 columns costs 78 B blank, 675 B with 72 cells of plain text and 1062 B with 120 styled cells.
  - A scroll into a full history takes 27.5–27.7 ns.
  - Writing a line cell by cell is 3 to 7 times slower than in Rust. The time is in the per-cell call, so T7 should write a run of cells in one call.
- `Row.compact` no longer copies an array that already fits its content.
- No library: Kotlin/Native has no maintained terminal grid or scrollback library.

About 2040 hand-written lines (library, tests and bench), over the 500-line budget. The cell, the tables, the row and the ring are one storage design and were ported together.

## How it was checked

- `./gradlew check` on macOS (M3 Max) passes with 175 tests, 56 of them in `vesper-grid`:
  - every cap (styles, clusters, cluster length, columns, ring rows);
  - scrolling as an offset change, with no row copied;
  - row ids and generations;
  - wide-character fix-ups and link spans;
  - seeded random scroll sequences that keep the ring consistent.
- The same 56 tests pass on linuxX64 in Ubuntu 24.04 (amd64) on Colima.
- `vesper-bench grid` gave the same memory table in three runs on macOS and in the linuxX64 build.
- mingwX64 is not covered: `ci.yml` has a mingwX64 job, but no workflow run has started in pyrlyn/vesper yet.
