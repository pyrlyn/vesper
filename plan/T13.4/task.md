---
status: todo
priority: P1
complexity: 3
readiness: 0%
agent:
---

# T13.4. macOS selection, copy and find UI

Selection, copy and find in the Kotlin/Native macOS app over the core's selection and search (T13.2): mouse drag, double and triple click, Option for a block, Shift to extend, Cmd-C to the pasteboard, and a Cmd-F find bar with next, previous, match case and a match count. The renderer colours selected, matching and current-match cells from the frame's cell flags. Done when a selection and a highlighted match show in a snapshot.

The removed Swift code in `macos/` at eaeb20f (`SelectionGesture`, `FindBar`) is the reference. The execution plan is written at claim time.

Check: `./gradlew check` with tests for the gesture mapping, the find state machine and the copied text.

Acceptance:
- [ ] The work described above is implemented in Kotlin/Native.
- [ ] Tests and the `./gradlew check` gate (plus the host tests this task names) pass.
