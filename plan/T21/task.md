---
status: todo
priority: P2
complexity: 3
readiness: 0%
agent:
---

# T21. Tabs, splits and windows

Tabs, split panes and multiple windows in both Kotlin/Native hosts over the same pane tree: a pure `PaneTree` shared by both (split, close, focus, focus next and previous, layout of one pixel surface into viewports), one terminal per pane so a split never restarts a shell, a child exit closing its pane. On Windows the panes are viewports of the window's one swap chain and share one glyph atlas per font and size. Done when a terminal that fails stops only its pane and the rest keep running.

The removed Swift and C# code in `macos/` and `windows/` at eaeb20f is the reference. The execution plan is written at claim time and split into slices of at most 500 lines.

Check: `./gradlew check` with tests for split, close, focus order and layout, and for a failed terminal leaving its siblings working.

Acceptance:
- [ ] The work described above is implemented in Kotlin/Native.
- [ ] Tests and the `./gradlew check` gate (plus the host tests this task names) pass.
