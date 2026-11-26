# T13.4. Done

- `vesper-session`: `hasSelection`, `selectionText()`, `search(pattern, caseSensitive)` (an empty
  pattern ends the search), `searchStep(forward)` and `searchCount()`.
- `vesper-macos`, common code (ported from the removed Swift host's `SelectionGesture` and `FindBar`):
  - `selectionPress`: two clicks select a word, three or more a line, Option a block; Shift extends a
    selection that is there. A plain or block press that never moves clears on release.
  - `dragScroll`: a drag past the top or bottom edge scrolls the history a line at a time.
  - `FindState`: open, edit, match case, settle, step, close, as effects for the view to run. Typing
    searches at once; stepping and counting wait for the pattern to settle or for the first step.
    The label reads "No matches", "1 match", "N matches", or "N+ matches" at `MAX_SEARCH_MATCHES`.
  - `Palette`: selection, match and current-match background colours. A current match beats the
    selection, which beats a match; the text keeps its colour.
- Both renderers paint those backgrounds from `FrameCell.flags`: the Metal background pass and the
  CoreText fallback.
- `TerminalView`:
  - A left press goes to the program when it tracks the mouse, unless Shift is held; otherwise
    press, drag and release drive the core's selection, scrolling past an edge.
  - `copy:` puts the selected text on the pasteboard (swappable, so tests use a private one).
  - `FindBar`: an `NSSearchField`, a match case toggle, the count, previous, next and close, over the
    top-right corner. Return steps forward, Shift-Return back, Escape closes. Counting runs 0.25 s
    after the last keystroke.
  - Edit menu: Copy (Cmd-C), Find… (Cmd-F), Find Next (Cmd-G), Find Previous (Shift-Cmd-G).
    `validateMenuItem` enables Copy with a selection and the steps with a pattern.

Commits on branch `t13.4-macos-selection`, stacked on T16's `t16-macos-ime`: the claim, the session
and pure logic, the highlights, the view.

Checked:
- `./gradlew ktlintFormat check linkDebugTestLinuxX64` on macOS arm64: green.
- Linux x64 tests of all 12 modules in Colima (`ubuntu:24.04`, `VESPER_TESTS_DIR` set): green.
- Tests:
  - `SessionTest.theSelectionCopiesAndASearchStepsAndCounts`: the copied word and the case-sensitive
    and case-insensitive counts.
  - `SelectionGestureTest` (4), `FindStateTest` (5) and `HighlightTest` (1) in common code.
  - `MetalRendererTest.selectedMatchingAndCurrentCellsAreHighlighted`: the pixels of each highlight.
  - `SelectionUiTest` (4) drives the view in a window under `cat` with synthesized mouse events: a
    drag and Shift-click copy the right text, two and three clicks take a word and the line, a plain
    click clears, the find bar counts, honours match case and reopens on the last search, and menu
    validation follows. Its snapshot `macos-selection.png` shows a selection, the current match and
    another match.
- Not checked (needs a person): selecting with a real mouse or trackpad, and the find bar's look.
