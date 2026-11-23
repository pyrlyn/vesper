---
status: in progress
priority: P0
complexity: 4
readiness: 100%
agent: Claude Code / claude-fable-5-2
---

# T6. Grid and scrollback

`vesper-grid`: a small fixed cell with interned clusters and styles, rare data out of line, O(1) blank and uniform lines, and one power-of-two ring of lazily allocated rows shared by the screen and scrollback. Rows carry a stable id and a generation. Done when scrolling is an offset change and memory per scrollback row is recorded in the benchmark.

## Execution plan

The Rust tree at 69d9be4 already solved this (its `grid` crate, after foot's and Alacritty's ring, `docs/research/`). The port keeps its layout, caps and tests. It is original scull code, so no upstream notice applies. Resize and reflow (`grid/resize.rs`, `reflow.rs`) stay for T11.

1. **T6.1: cells, interning and rows.**
   - `Cell` is an 8-byte value class over a `Long`: code point or cluster id, style id, layout flags. A row is a `LongArray`.
   - `StyleTable` (65 536 styles) and `ClusterTable` (65 536 clusters of up to 32 code points) share one capped interner with mark-and-sweep reclaim.
   - `Row` has a stable `RowId`, a generation, a fill cell standing for every unwritten column (blank and uniform rows hold no array), wide-character fix-ups, and link spans kept out of line.
2. **T6.2: the ring and scrollback.**
   - `Grid` is one power-of-two ring of lazily created rows, shared by the screen and scrollback, with a hard cap of 131 072 rows.
   - Scrolling moves `zero` and recycles one row. Region scrolls rotate in place.
   - The viewport is an offset into history. A full table is swept when the sweep pays off.
3. **Tests:**
   - every cap;
   - scrolling as an offset change, with no row copied;
   - ids and generations;
   - wide-character fix-ups and links;
   - seeded random scroll sequences that keep the ring consistent.
4. **Bench:** `vesper-bench grid` reports bytes per scrollback row, measured from the Kotlin/Native heap over a full 131 072-slot ring, and scroll timings on the Rust bench's 10 000-row fixture. Record them in `docs/benchmarks/grid.md`.
5. **Verify:** `./gradlew check` on macOS and on linuxX64 under Colima. Both slices land in one PR, so no T6.x folders are needed.

Acceptance:
- [x] The work described above is implemented in Kotlin/Native (platform UI stays Swift/C# where this task says so).
- [x] Tests and the `./gradlew check` gate (plus the platform tests this task names) pass.
