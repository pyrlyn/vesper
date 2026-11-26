# T16. Done

- `vesper-term`:
  - `Preedit`: the input method's composition, ported from the Rust core's `preedit.rs`.
    - Text is capped at 1024 UTF-16 units, never cutting a surrogate pair.
    - Clauses are sorted with overlaps dropped. Each is input, converted or target.
    - Clusters and widths come from `vesper-unicode`.
    - On a narrow row the text shifts left to keep the caret in view.
  - `Frame.setPreedit(text, caret, clauses)`. `copyFrom` lays the composition over the cursor row
    in the default style and puts the frame's cursor at the caret. It exposes `preedit` (row, column,
    width, clause spans), so any renderer can draw it.
    - A wide character the composition cuts reads as a blank.
    - The rows the composition enters or leaves are never reused or moved by the damage.
  - `ViewportText`: the viewport as one string. Line N is row N, wide characters read once, blank and
    concealed cells read as spaces, and trailing blanks are trimmed. It holds:
    - every character's UTF-16 offset and column;
    - the selection as ranges, one per row.

    It maps offsets to lines, line ranges, columns, cells and whole clusters, and back.
    `Terminal.viewportText()` builds it. (The plan called it `ScreenText`; the name was taken by a
    test helper.)
- `vesper-session`: `viewportText()`, plus `select`, `selectExtend` and `selectClear` in viewport cells.
- `vesper-macos`:
  - `TextInput`, ported from the removed Swift host, holds:
    - the marked text and its selection;
    - clauses from the underline and clause-segment attributes (the thick clause is the target);
    - what a key event became: a key for the core, composed text, or a key the input method consumed.
  - `TerminalView` implements `NSTextInputClient`:
    - `keyDown` runs through `interpretKeyEvents`. The releases of composed keys are not reported.
    - Committed text goes to the child with `Session.text`.
    - The composition goes into the frame, and the view scrolls back to the cursor while composing.
    - The candidate window opens at the caret.
  - The Metal renderer and the CoreText fallback underline the composition per clause, thick under
    the target.
  - Accessibility: `TerminalView` is an `NSAccessibility` text area over `ViewportText`. It answers:
    - value, character count and visible range;
    - lines and line ranges, strings and attributed strings for a range;
    - a range's screen frame, and the character under a screen point;
    - the insertion point at the cursor;
    - the selection, read and set through the core's selection.

    Value and selection change notifications are posted only while VoiceOver runs, and a selection
    change only when the ranges differ from the last ones posted.
  - The range arithmetic sits in `ScreenReading` (common code). AppKit range conversions are in
    `Ranges`.

Commits on branch `t16-macos-ime`: one per slice (preedit, viewport text, input method,
accessibility).

Checked:
- `./gradlew ktlintFormat check` on macOS arm64: green, with ktlint leaving nothing to fix.
- Linux x64 tests of all 12 modules in Colima (`ubuntu:24.04`, with `VESPER_TESTS_DIR` set for the
  golden files): green.
- Tests of the text and range mapping:
  - `PreeditTest` (13): layout, clusters and emoji, the cap, clause spans, the frame overlay, wide
    cells cut by the composition, and damage on scroll.
  - `ViewportTextTest` (8): ASCII, CJK wide cells, emoji and combining clusters, wrapped rows,
    trailing blanks, concealed text, selection ranges and their inverse.
  - `ScreenReadingTest` (5): the cursor and selection ranges, substrings, range frames, and the
    character under a cell.
  - `TextInputTest` (7): key outcomes (dead keys, CJK commit, backspace in a composition), clauses
    and ranges.
- macOS host tests:
  - `InputMethodTest` composes through the view under `cat`: marked text shows on the cursor row
    and committed text echoes.
  - `AccessibilityTest` calls the accessibility methods on a view under `cat`: value, lines, ranges,
    and the selection set and read through the core.
  - `MetalRendererTest` checks the composition's underline pixels.
- Not checked (needs a person): typing with a real Japanese or Chinese input method, and VoiceOver
  speech.
