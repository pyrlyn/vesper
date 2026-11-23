---
status: todo
priority: P2
complexity: 5
readiness: 0%
agent:
---

# T18. Windows input method and accessibility

TSF text input and a UI Automation text provider in the Kotlin/Native Windows app (T17), through the C interfaces of `msctf.h` and `uiautomationcore.h`. TSF as Windows Terminal ships it: a context owner with no text store of our own, the composition and its clauses carried in the frame, the candidate window at the cursor. UIA: one text provider per pane over a viewport read-text call, with the selection, text attributes from the frame's cell styles, and text-changed events only while a client listens. Done when CJK input and Narrator reading work.

The removed C# code in `windows/` at eaeb20f (`ScreenText`, `TextRange`, `TsfInput`, the automation peers) is the reference. The execution plan is written at claim time and split into slices of at most 500 lines.

Check: `./gradlew check` with tests for the text model, unit moves, selection and attribute mapping; a real IME, Narrator and Accessibility Insights need a human on Windows.

Acceptance:
- [ ] The work described above is implemented in Kotlin/Native.
- [ ] Tests and the `./gradlew check` gate (plus the host tests this task names) pass.
