---
status: todo
priority: P1
complexity: 4
readiness: 0%
agent:
---

# T16. macOS input method and accessibility

`NSTextInputClient` on the Kotlin/Native view (T14), with the preedit text, caret and clauses carried in the frame (laid over the cursor row by the core, so any renderer draws them), and `NSAccessibility` text over a viewport read-text call and the core's selection. Done when CJK input and VoiceOver reading, including the selection, work.

The removed Swift code in `macos/` at eaeb20f (`TextInput`, `ScreenText`, `TerminalView+Accessibility`) is the reference. The execution plan is written at claim time and split into slices of at most 500 lines.

Check: `./gradlew check` with tests for the text and range mapping (ASCII, CJK wide cells, emoji, wrapped rows, trailing blanks); a real input method and VoiceOver speech need a human.

Acceptance:
- [ ] The work described above is implemented in Kotlin/Native.
- [ ] Tests and the `./gradlew check` gate (plus the host tests this task names) pass.
