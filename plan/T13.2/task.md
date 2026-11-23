---
status: in progress
priority: P1
complexity: 4
readiness: 100%
agent: Claude Code / claude-opus-5-5
---

# T13.2. Selection and scrollback search in the core

A selection (cell, word, line; anchored to absolute rows so it survives scrolling and clears when its rows are overwritten), its text (wrapped rows joined, wide cells once, trailing blanks trimmed), and a capped search over screen and scrollback returning match ranges. The frame view marks selected and matched cells. C ABI exports for both, ABI minor bump, the bindings generator. Verify: golden tests, Kotlin/Native tests for every export, `./gradlew check`, the C ABI sanitizer test.

Plan: `vesper-term` selection keeps the selection on `Terminal` as absolute lines (the images' `line_of`: evicted rows plus the ring index) with the stamps of its screen rows and end rows; `feed` drops it when one of them changed or left the ring, `resize` and a screen switch drop it. Word boundaries are a documented separator set. the search module scans logical lines (soft wraps joined) built by the cell-to-text helper shared with `read_text`, with case folding per character; pattern and match count are capped, next and previous scan from a point, and the frame scans only the viewport. the frame module sets `FrameCell` flag bits for selected, matched and current-match cells and hashes them into the row slices so damage repaints those rows. `scull-ffi` selection exports exports start, extend, clear, copy (the `tt_term_read_text` sized-buffer pattern), set pattern, next, previous, count; minor 6; the bindings generator. Golden cases in `tests/fixtures/selection.txt`.

Acceptance:
- [x] The work described above is implemented in Kotlin/Native (platform UI stays Swift/C# where this task says so).
- [x] Tests and the `./gradlew check` gate (plus the platform tests this task names) pass.

## Findings

- The earlier Rust core (`selection.rs` of its `term` crate, `search.rs`, `text.rs`, `frame.rs` marks, `select.rs` of its `ffi` crate) is ported with its tests.
- The Kotlin grid has no count of rows that left the ring, and nothing anchors to absolute lines yet: images (T19) are not ported. So `Grid` gets the counter, rather than `vesper-term` re-deriving it at every scroll as the Rust core does.
- The frame's damage compares row stamps, not a row hash. Marks are therefore compared exactly per row, next to the stamps: a row whose marks changed is repainted, and one that moved with unchanged marks still scrolls.
- The ABI is at 0.2, so this is minor 3, not the "minor 6" the plan line names. The bindings generator for C# moved to T17.1 with the rest of C#; here the committed header changes.
- `tt_term_read_text` is not ported. No export uses it yet, and the accessibility tasks own it. Selection and search share its cell-to-text helper.

## Execution plan

1. **`vesper-grid`.** `Grid.evicted` counts the rows that ever left the top of the ring: by a full history, by ED 3, by the alternate screen keeping no history, and by a resize. Line `n` is logical row `n - evicted`.
2. **`vesper-term`.**
   - `Text.kt`: the cell-to-text helper. Concealed and blank cells read as spaces, and trailing blanks are trimmed.
   - `Selection.kt`: `Point`, `SelectionKind` (cell, word, line, block), start, extend, clear and text. It keeps the stamps of the rows it covers that can change. `feed` drops the selection when they change or leave the ring, or when the screen switches; `resize` drops it too.
   - `Search.kt`: literal search with per-character case folding, over a window that crosses soft wraps. The pattern is capped at 256 UTF-8 bytes and the count at 10 000. It has next and previous with wrap-around, and they scroll the viewport to the match.
   - `Frame`:
     - `FrameCell.SELECTED`, `MATCH` and `CURRENT_MATCH` bits;
     - marks per viewport row;
     - damage repaints a row whose marks changed.
3. **`scull-ffi`.**
   - Exports:
     - `tt_term_select_start`, `tt_term_select_extend`, `tt_term_select_clear`;
     - `tt_term_selection_text`, using the sized-buffer copy;
     - `tt_term_search_set`, `tt_term_search_step`, `tt_term_search_count`.
   - `tt_match`, the `TT_CELL_*` bits, the caps, ABI 0.3.
4. **Verify.**
   - `selection.txt` joins `ConformanceTest`.
   - The Rust selection, search and frame-mark tests become `commonTest`.
   - A Kotlin/Native test for each export.
   - The C test calls the new prototypes under the sanitizers.
   - `./gradlew ktlintFormat check`, and Linux in Colima.
