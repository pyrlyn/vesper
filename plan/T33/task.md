---
status: in progress
priority: P2
complexity: 4
readiness: 100%
agent: Claude Code / claude-opus-5-5
---

# T33. Jump between shell prompts with OSC 133 marks

Shells that emit OSC 133 mark where each prompt starts; the core reports the marks as events but keeps no positions. Done when the core remembers prompt lines in the scrollback and both hosts bind a jump to the previous or next prompt (Cmd+Up/Down on macOS, Ctrl+Shift+Up/Down on Windows).

Core: the primary screen keeps the sorted absolute lines of `OSC 133;A` marks, deduplicated, beside the image anchors that already survive scrolling, trimming and ED 3; marks are dropped when their line leaves the ring, on ED 3 and when ED 2 wipes their rows, ride a rewrapping resize as tracking points, and are not recorded on the alternate screen. `Terminal.scrollToPrompt(forward)` moves the viewport so that prompt is the top row and answers false, moving nothing, when there is none.

Check: `./gradlew check` with tests for recording, the walk both ways, trimming, ED 2 and ED 3, the alternate screen and a rewrapping resize, and a host test per binding.

Acceptance:
- [ ] The work described above is implemented in Kotlin/Native.
- [ ] Tests and the `./gradlew check` gate (plus the host tests this task names) pass.

## Execution plan

1. **Core (`vesper-term`).**
   - A new `Prompts.kt` holds `PromptMarks`: sorted, deduplicated absolute lines in `State.prompts`.
     They belong to the primary screen and are not swapped with the alternate one.
   - `OSC 133;A` records the cursor's line unless the alternate screen is shown.
   - `feed` prunes the marks the ring dropped. ED 2 and RIS drop the marks on the screen's rows.
     ED 3 prunes the history's marks.
   - The primary screen's resize carries the marks as tracking points beside the image anchors.
   - `Terminal.scrollToPrompt(forward)` steps from the viewport's top row.
2. **Session and hosts.**
   - `Session.scrollToPrompt` runs under the session's lock.
   - `vesper-host` `promptDirection(action)` maps the action to a direction.
   - The macOS `TerminalView.perform` and the Windows `App.perform` jump, and beep when there is
     no prompt to jump to.
3. **Tests.**
   - `PromptsTest` covers recording, the walk both ways, trimming, ED 2 and ED 3, RIS, the
     alternate screen and the rewrapping resize.
   - `BindingsTest` checks both platforms' chords.
   - `SessionTest` runs a shell that prints marks.
   - The macOS `PromptJumpTest` uses ⌘↑/⌘↓ on a real shell.
   - The Windows `SettingsTest` checks that Ctrl+Shift+↑/↓ are the app's.
4. **Verify.** The gate, plus the linuxX64 tests in Colima.
