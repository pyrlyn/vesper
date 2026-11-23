---
status: todo
priority: P2
complexity: 4
readiness: 0%
agent:
---

# T33. Jump between shell prompts with OSC 133 marks

Shells that emit OSC 133 mark where each prompt starts; the core reports the marks as events but keeps no positions. Done when the core remembers prompt lines in the scrollback and both hosts bind a jump to the previous or next prompt (Cmd+Up/Down on macOS, Ctrl+Shift+Up/Down on Windows).

Core: the primary screen keeps the sorted absolute lines of `OSC 133;A` marks, deduplicated, beside the image anchors that already survive scrolling, trimming and ED 3; marks are dropped when their line leaves the ring, on ED 3 and when ED 2 wipes their rows, ride a rewrapping resize as tracking points, and are not recorded on the alternate screen. `Terminal.scrollToPrompt(forward)` moves the viewport so that prompt is the top row and answers false, moving nothing, when there is none.

Check: `./gradlew check` with tests for recording, the walk both ways, trimming, ED 2 and ED 3, the alternate screen and a rewrapping resize, and a host test per binding.

Acceptance:
- [ ] The work described above is implemented in Kotlin/Native.
- [ ] Tests and the `./gradlew check` gate (plus the host tests this task names) pass.
