# T13.2. Selection and scrollback search in the core

Status: not started

a selection (cell, word, line; anchored to absolute rows so it survives scrolling and clears when its rows are overwritten), its text (wrapped rows joined, wide cells once, trailing blanks trimmed), and a capped search over screen and scrollback returning match ranges. The frame view marks selected and matched cells. C ABI exports for both, ABI minor bump, the bindings generator. Verify: golden tests, Kotlin/Native tests for every export, `./gradlew check`, the C ABI sanitizer test.

Plan: `scull-term` selection keeps the selection on `Terminal` as absolute lines (the images' `line_of`: evicted rows plus the ring index) with the stamps of its screen rows and end rows; `feed` drops it when one of them changed or left the ring, `resize` and a screen switch drop it. Word boundaries are a documented separator set. the search module scans logical lines (soft wraps joined) built by the cell-to-text helper shared with `read_text`, with case folding per character; pattern and match count are capped, next and previous scan from a point, and the frame scans only the viewport. the frame module sets `FrameCell` flag bits for selected, matched and current-match cells and hashes them into the row slices so damage repaints those rows. `scull-ffi` selection exports exports start, extend, clear, copy (the `tt_term_read_text` sized-buffer pattern), set pattern, next, previous, count; minor 6; the bindings generator. Golden cases in `tests/fixtures/selection.txt`.

Acceptance:
- [ ] The work described above is implemented in Kotlin/Native (platform UI stays Swift/C# where this task says so).
- [ ] Tests and the `./gradlew check` gate (plus the platform tests this task names) pass.
