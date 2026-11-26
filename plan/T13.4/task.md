---
status: in progress
priority: P1
complexity: 3
readiness: 100%
agent: Claude Code / claude-opus-5-5
---

# T13.4. macOS selection, copy and find UI

Selection, copy and find in the Kotlin/Native macOS app over the core's selection and search (T13.2): mouse drag, double and triple click, Option for a block, Shift to extend, Cmd-C to the pasteboard, and a Cmd-F find bar with next, previous, match case and a match count. The renderer colours selected, matching and current-match cells from the frame's cell flags. Done when a selection and a highlighted match show in a snapshot.

The removed Swift code in `macos/` at eaeb20f (`SelectionGesture`, `FindBar`) is the reference. The execution plan is written at claim time.

Check: `./gradlew check` with tests for the gesture mapping, the find state machine and the copied text.

Acceptance:
- [x] The work described above is implemented in Kotlin/Native.
- [x] Tests and the `./gradlew check` gate (plus the host tests this task names) pass.

## Execution plan

Stacked on T16's `t16-macos-ime` (it adds the session's selection calls). Three slices, each under 500 lines with its tests:

1. **Session and pure logic**: `Session` gains `hasSelection`, `selectionText()`, `search(pattern, caseSensitive)`, `searchStep(forward)` and `searchCount()`. In `vesper-macos` common code: `SelectionGesture` (two clicks a word, three a line, Option a block, Shift extends or forces selection past a program's mouse tracking), `FindState` (open, edit, match case, settle, step, close, the match count label, capped at `MAX_SEARCH_MATCHES`), and the palette's selection, match and current-match colours. Tests in `commonTest`.
2. **Highlights**: the Metal renderer and the CoreText fallback paint the backgrounds of selected, matching and current-match cells from `FrameCell.flags`. A Metal pixel test.
3. **View**: `TerminalView` turns a left press, drag (scrolling past an edge) and release into core selection calls when the program does not take the mouse or Shift is held; `copy:` puts the selected text on the general pasteboard; a find bar (`NSSearchField`, match case, previous and next, count, Escape closes) over the top-right corner; Edit menu items Copy (Cmd-C), Find (Cmd-F), Find Next (Cmd-G), Find Previous (Shift-Cmd-G) with validation. Host tests drive the view with synthesized mouse events and find calls; a snapshot shows a selection and a highlighted match.

Verify: `./gradlew ktlintFormat check` on macOS, the Linux tests in Colima.
