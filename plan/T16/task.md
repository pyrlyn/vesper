---
status: in progress
priority: P1
complexity: 4
readiness: 5%
agent: Claude Code / claude-opus-5-5
---

# T16. macOS input method and accessibility

`NSTextInputClient` on the Kotlin/Native view (T14), with the preedit text, caret and clauses carried in the frame (laid over the cursor row by the core, so any renderer draws them), and `NSAccessibility` text over a viewport read-text call and the core's selection. Done when CJK input and VoiceOver reading, including the selection, work.

The removed Swift code in `macos/` at eaeb20f (`TextInput`, `ScreenText`, `TerminalView+Accessibility`) is the reference. The execution plan is written at claim time and split into slices of at most 500 lines.

Check: `./gradlew check` with tests for the text and range mapping (ASCII, CJK wide cells, emoji, wrapped rows, trailing blanks); a real input method and VoiceOver speech need a human.

Acceptance:
- [ ] The work described above is implemented in Kotlin/Native.
- [ ] Tests and the `./gradlew check` gate (plus the host tests this task names) pass.

## Execution plan

Four slices, each under 500 lines with its tests, committed in this order on `t16-macos-ime`:

1. **Preedit in the frame** (`vesper-term`): `Preedit.kt` ports the Rust core's `preedit.rs` (text capped at 1024 UTF-16 units, caret, clauses of kind input, converted or target, grapheme clusters and widths from `vesper-unicode`, the caret kept in view on a narrow row). `Frame.setPreedit(text, caret, clauses)`; `copyFrom` lays it over the cursor row in the default style, moves the frame's cursor to the caret, exposes `preedit` (row, col, cols) and its clause spans, and repaints the rows it enters and leaves. Offsets are UTF-16, as NSString and the Windows APIs count them. Tests in `commonTest`.
2. **Screen text** (`vesper-term`, `vesper-session`): `ScreenText` holds the viewport as one string (line N is row N, wide characters once, blank and concealed cells as spaces, trailing blanks trimmed), the column of every character, and the selection as UTF-16 ranges, one per row. It maps offsets to lines, line ranges, columns and cells both ways. `Terminal.screenText()`; `Session.screenText()`, `select`, `selectExtend`, `selectClear` in viewport cells. Tests: ASCII, CJK wide cells, emoji clusters, wrapped rows, trailing blanks, concealed text, selection ranges and their inverse.
3. **Input method** (`vesper-macos`): `TextInput` (marked text, its selection, the clauses from underline and clause-segment attributes, the key outcome) as in the removed Swift `TextInput`; `TerminalView` implements `NSTextInputClient`, runs `keyDown` through `interpretKeyEvents`, sends committed text with `Session.text`, sets the frame's preedit and places the candidate window at the caret. The Metal renderer and the CoreText fallback underline the preedit, thick under the target clause. Tests for the key outcome, clauses and ranges, and a host test that composes through the view.
4. **Accessibility** (`vesper-macos`): `TerminalView` as an `NSAccessibility` text area over `Session.screenText()`: value, lines, ranges, frames, the range under a point, the insertion point at the cursor, the selection read and set; value and selection change notifications only while VoiceOver runs. Host tests through the accessibility methods.

Verify: `./gradlew ktlintFormat check` on macOS, the Linux tests in Colima; CJK input and VoiceOver speech need a human.
