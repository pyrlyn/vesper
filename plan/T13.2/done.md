# T13.2. Done

## What was done

- **`scull-grid`.** `Grid.evicted` counts every row that left the ring. Absolute line `n` is logical row `n - evicted`, so a line keeps its number while output scrolls and history is dropped.
- **`scull-term`.**
  - `Selection.kt`: cell, word, line and block selections, anchored to absolute lines.
    - The stamps of the rows a selection covers are kept. `feed` drops the selection when one of them changes or leaves the ring, or when the screen switches; a resize to another size drops it too.
    - The text joins soft-wrapped rows, ends hard lines with `\n`, reads wide characters once and blank or concealed cells as spaces, trims trailing spaces, and is cut at 16 MiB on a character boundary.
  - `Search.kt`: literal search over history and screen across soft wraps, with per-code-point case folding.
    - The pattern is capped at 256 UTF-8 bytes and the count at 10 000.
    - Next and previous wrap round and scroll the viewport to the match.
  - `Text.kt`: the cell-to-text helper both share.
  - `Frame`:
    - `FrameCell.SELECTED`, `MATCH` and `CURRENT_MATCH`.
    - Damage compares each row's marks next to its stamp. A row whose highlights changed is repainted, and one that moved with its highlights still scrolls.
- **`scull-ffi`, ABI 0.3.**
  - `tt_term_select_start`, `tt_term_select_extend`, `tt_term_select_clear` and `tt_term_selection_text`.
  - `tt_term_search_set`, `tt_term_search_step` and `tt_term_search_count`.
  - `tt_match`, `TT_SELECT_*`, `TT_SEARCH_IGNORE_CASE`, the caps and the `TT_CELL_*` bits.
  - The sized-buffer helpers are shared with the event exports.

## Reused

- The Rust scull selection, search, frame-mark and FFI code and tests were ported.
- `checkOut`/`copyOut`, `bytesAt`, `writeStruct` and `guarded` come from `scull-ffi`.
- No UTF-8 or case-folding helper existed elsewhere in the project or under `packages/`.

## Checked

- **macOS:** `./gradlew ktlintFormat check` passes, C ABI tests under ASan and TSan included.
- **Linux x86_64 in Colima:** scull-grid passes 91 tests, scull-term 130 and scull-ffi 44.
- **New tests:**
  - `SelectionTest`, `SearchTest` and the frame-mark cases in `FrameTest`;
  - the `selection.txt` conformance cases;
  - `SelectExportsTest`, one or more cases per export;
  - `select_and_search` in `tests/c/events.c`.

## Commits

8a53f63, 1f1f718, ff98d74 and the closing commit on branch `t13.2-selection`. The PR is linked from the branch.
