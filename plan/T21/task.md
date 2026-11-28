---
status: in progress
priority: P2
complexity: 3
readiness: 75%
agent: Claude Code / claude-opus-5-5
---

# T21. Tabs, splits and windows

Tabs, split panes and multiple windows in both Kotlin/Native hosts over the same pane tree: a pure `PaneTree` shared by both (split, close, focus, focus next and previous, layout of one pixel surface into viewports), one terminal per pane so a split never restarts a shell, a child exit closing its pane. On Windows the panes are viewports of the window's one swap chain and share one glyph atlas per font and size. Done when a terminal that fails stops only its pane and the rest keep running.

The removed Swift and C# code in `macos/` and `windows/` at eaeb20f is the reference. The execution plan is written at claim time and split into slices of at most 500 lines.

Check: `./gradlew check` with tests for split, close, focus order and layout, and for a failed terminal leaving its siblings working.

Acceptance:
- [ ] The work described above is implemented in Kotlin/Native.
- [ ] Tests and the `./gradlew check` gate (plus the host tests this task names) pass.

## Execution plan

The reference is `PaneTree.swift`, `PaneTree.cs`, `PaneSet.cs` and `PaneCommands.cs` at eaeb20f.

**Slices**, each one pull request of at most 500 lines:

1. **Shared model.**
   - `vesper-host` gets `PaneTree`: split, close, focus, focus next and previous, a layout of one
     pixel surface into viewports, and the pane under a pixel.
   - `vesper-host` gets `PaneSet<T>`: one closeable per pane. The new pane's terminal is made
     before the tree changes, and closing a pane closes only its terminal.
   - `vesper-config` gets the actions `new-tab`, `new-window`, `split-right`, `split-down`,
     `close-pane`, `next-pane` and `previous-pane`.
     - On macOS they are bound to ⌘T, ⌘N, ⌘D, ⇧⌘D, ⌘W, ⌘] and ⌘[.
     - On PC they are bound to Ctrl+Shift with T, N, D, E, W, ] and [.
     - `docs/config.schema.json` is rebuilt.
   - `vesper-session` gets a test that a failed session leaves its sibling working.
2. **macOS host.**
   - A pane host view lays one `TerminalView` per pane out from `PaneTree`.
   - Splits keep every shell running. A child that exits closes its pane, and the last pane
     closes the window.
   - New tabs are native window tabs, and new windows are new windows.
   - Host tests cover splits, closes, focus and a failed pane.
3. **Windows host.**
   - The panes are viewports of the window's one swap chain. They share one glyph atlas per font
     and size.
   - Mouse focus uses the pane under the pointer. A child that exits closes its pane.
   - New windows are new top-level windows.
   - Host tests cover the same cases.
4. **Windows tabs.**
   - A tab strip over the panes, with Ctrl+Shift+T and closing the last pane of a tab.
   - Host tests.

Each slice runs the gate and the Linux tests in Colima.
