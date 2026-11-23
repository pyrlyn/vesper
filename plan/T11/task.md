---
status: in progress
priority: P1
complexity: 4
readiness: 100%
agent: Claude Code / claude-opus-5-5
---

# T11. Resize and reflow

One-pass reflow with tracking points for cursor, saved cursor, viewport and selection. The alt screen is not rewrapped. Interactive resize pauses the PTY and reflows once at the end. Done when no anchor is lost in the reflow golden tests.

## Findings

- The design is the Rust `scull-grid` T11 at a409a84 (`reflow.rs`, `grid/resize.rs`, the `row.rs` helpers) and the Rust `Terminal::resize` at d9e4e9a (`scull-term/src/resize.rs`), ported. Neither is a port of Alacritty or WezTerm code: the sorted tracking-point pass is an idea from foot (`grid_resize_and_reflow`), alt-screen truncation from kitty and WezTerm, so no third-party notices apply.
- Kotlin T10 lists `create/feed/resize/free` for the C ABI, so `Terminal.resize` belongs here, where the reflow lands; Rust put it in its T10.2. Image re-anchoring (Rust T19.4b) waits for images.
- `Grid.cols`, `screenRows`, `historyLimit` and the ring mask, `Row.cols` and the terminal state's size fields are fixed today and become mutable.

## Execution plan

1. `scull-grid`:
   - `Reflow.kt`: `rewrap(old, cols, spots, ids)` joins soft-wrapped rows into lines and cuts them at the new width in one pass. Wide characters move whole behind a `LEADING_SPACER`. Styles, clusters and links travel with their cells, and sorted points are translated as the copy passes them.
   - `Grid.resize(cols, screenRows, reflow, cursor, points)`: `Reflow.REWRAP` for the primary screen and history, `Reflow.TRUNCATE` for the alt screen. The cursor stays on the screen, a scrolled-back viewport stays on its top line, history is capped, and zero is refused. `TrackPoint(row, col, clamped)`.
   - `Row` helpers: `rebuilt`, `fill`, `fillIsBlank`, `contentLength`, `isBlank`, `sameContent`, `markChanged`, `setCols`.
2. `scull-term`: `Terminal.resize(cols, rows)` rewraps the main screen and truncates the alt one. It tracks the cursor and both saved cursors, resets the margins, keeps the tab stops and extends them, and keeps a pending wrap only across a height change. The KDoc states the T10 contract: pause PTY reads during an interactive resize and resize once at the end.
3. Tests: the Rust golden tests (shrink/grow round trip, wide characters at the wrap edge, a cursor on a wrapped line and past the end, points in scrollback, the history cap, the viewport, alt-screen truncation, ids, links and styles, the erase fill, a one-column grid) and the terminal tests. Two seeded property tests: no point or line is lost across rewrap sequences, and any resize sequence keeps points in the grid and the ring sound. Plus one property test over random streams and resizes.
4. Verify: `./gradlew ktlintFormat check`; the linuxX64 test binaries of `scull-grid` and `scull-term` in Colima; a mutation of the point translation must fail the property test.

## Acceptance

- [x] `Grid.resize` rewraps the primary screen and history in one pass and truncates the alt screen.
- [x] Every tracking point follows its cell or is reported clamped; the golden and property tests hold.
- [x] `Terminal.resize` tracks the cursor and saved cursors and rebuilds margins and tab stops.
- [x] `./gradlew check` passes.
