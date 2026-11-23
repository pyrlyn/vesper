# T11. Done

## What was done

- `vesper-grid`:
  - `Reflow.kt` rewraps soft-wrapped lines to a new width in one pass.
    - Sorted tracking points are translated as the copy passes their cells.
    - A wide character at the edge moves whole behind a `LEADING_SPACER`.
    - Styles, clusters and hyperlink spans travel with their cells.
    - Row ids live on in the row that starts with the same cell, and a row whose content changed is marked for repaint.
  - `Grid.resize(cols, screenRows, reflow, cursor, points)` takes `Reflow.REWRAP` for the primary screen and its history and `Reflow.TRUNCATE` for the alternate screen.
    - The cursor row stays on the screen and trailing blank rows are dropped.
    - The history cap evicts the oldest rows, and a scrolled-back viewport stays on its top line.
    - It returns every point as `TrackPoint(row, col, clamped)`.
  - `Row` gained `rebuilt`, `setCols` and the content helpers the reflow needs.
- `vesper-term`:
  - `Terminal.resize(cols, rows)` rewraps the main screen and cuts the alternate one.
    - The cursor and both DECSC slots follow their cells, including the hidden main screen's saved cursor under `?1049`.
    - A pending wrap survives only a change of height.
    - The margins reset, and `TabStops.resize` keeps the stops already set and gives new columns the power-on ones.
  - The KDoc states the contract for the C ABI (T10): during an interactive resize, pause PTY reads with `Pty.pauseOutput`, keep drawing the last frame, then resize once with the final size.
- Ported from the Rust design (scull a409a84, d9e4e9a). Reused: `Random.stream()` and the screen helpers in the `vesper-term` test support, and the grid's existing link spans, `breakWideEdges` and `compact`.

## Commits

- f8d9cf7 T11: one-pass reflow with tracking points in the grid
- 6a27eaf T11: Terminal.resize tracks both cursors and rebuilds margins and tab stops

## How it was checked

- `./gradlew ktlintFormat check` passes: 307 macOS tests, 90 in `vesper-grid` and 88 in `vesper-term`.
  - The new tests are 20 grid golden tests, 6 row and reflow unit tests, 8 terminal tests and 3 seeded property tests.
  - The property tests are rewrap sequences, resize sequences, and random streams with resizes.
- The linuxX64 test binaries of `vesper-grid` (90/90) and `vesper-term` (88/88) pass in Colima (ubuntu:24.04, amd64).
- Each mutant of the point translation in `Reflow.kt` fails both golden and property tests:
  - every landing exact: 3 tests fail, among them `anyResizeSequenceKeepsPointsInTheGridAndTheRingSound`;
  - points landing one cell late: 8 tests fail, among them `rewrapSequencesLoseNoPointAndNoLine`;
  - the last row cut before a point past the text: 6 tests fail.
