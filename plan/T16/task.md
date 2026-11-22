---
status: not started
priority: P1
complexity: 4
readiness: 0%
---

# T16. macOS input method and accessibility

Status: not started

`NSTextInputClient` with preedit carried in the frame, and `NSAccessibility` text over the core's read-text calls. Done when CJK input and VoiceOver reading work.

Execution plan (split to fit the budget):

- T16.1 Preedit in the frame and `NSTextInputClient`. The frame (`scull-term`) holds the preedit text and caret the UI gives it, capped in size, and on update lays it over the cursor row: clusters and widths from `scull-unicode` with the terminal's width options, shifted left to fit the row, the cursor moved to the caret, and the row's overlay hash changed so damage repaints it and scroll damage never moves it. So any renderer draws composing text with no code of its own; `tt_frame_view.preedit` (row, col, cols) tells it where to underline. `scull-ffi` exports `tt_frame_preedit`; ABI minor bump, header and C# bindings regenerated. In `macos/`, `TextInput` (marked text, selection, what a key event turned into) and a `TerminalView` extension conforming to `NSTextInputClient` in new files; `keyDown` asks it whether the input method took the key, and committed text goes through `tt_term_text`. The CoreText pass underlines the preedit.
- T16.2 Accessibility. `Terminal::read_text` gives viewport rows as text, one line per row, trailing blanks trimmed, hidden (SGR 8) cells blank; `tt_term_read_text` copies it into a caller buffer, answering the needed length when it is short. A `TerminalView` extension in a new file makes the view an `NSAccessibility` text area over it: value, line for index, range for line, string and frame for range, insertion point at the cursor, `valueChanged` posted on new output while VoiceOver runs.
- T16.3 The selection in accessibility: `TerminalView+Accessibility.swift` says there is no selection, but the view has had mouse selection since `SelectionGesture.swift`, so VoiceOver should hear it.
    Execution plan: (1) `ScreenText` (pure, in `ScreenText.swift`): `SelectedSpan` (a screen line and a half-open run of columns), `ranges(of:width:)` (a span to a UTF-16 range in the value: it starts at the character the first column belongs to, ends after the character holding the last, so a wide character is never cut and cells past the trimmed text add nothing; empty ranges are dropped) and `selection(for:width:)` (the inverse, for VoiceOver setting a range: the first and last cells of the non-newline characters in it). (2) `TerminalSession.selectedSpans()` reads them from the frame's `TT_CELL_SELECTED` marks, one span per row (a block selection is a column run on each row), because the core exports no selection bounds and the marks are what the screen shows. (3) `TerminalView+Accessibility.swift`: `accessibilitySelectedTextRanges` (one range per row), `accessibilitySelectedTextRange` (their hull; with no selection the empty range at the cursor as now), `accessibilitySelectedText` (the ranges' text joined by newlines, so it matches the value's coordinates), `setAccessibilitySelectedTextRange` (an empty range clears; otherwise `tt_term_select_start` at the first cell and `tt_term_select_extend` to the last) and `postSelectionChange`, which posts `selectedTextChanged` only when the ranges differ from the last posted ones, from the gestures' refresh and the update path (output can drop a selection). (4) Tests (Swift Testing): the span and range mapping over ASCII, CJK wide cells, an emoji, wrapped rows and trailing blanks; the view with a real terminal (select, read the ranges and text, set a range and read back the core's selection text, clear). Verify: `./gradlew check`, `swift test` in `macos/`, CI macOS job. VoiceOver speech needs a human.

Acceptance:
- [ ] The work described above is implemented in Kotlin/Native (platform UI stays Swift/C# where this task says so).
- [ ] Tests and the `./gradlew check` gate (plus the platform tests this task names) pass.
