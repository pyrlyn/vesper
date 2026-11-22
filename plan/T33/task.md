---
status: not started
priority: P2
complexity: 4
readiness: 0%
---

# T33. Jump between shell prompts with OSC 133 marks

Status: not started

Shells that emit OSC 133 mark where each prompt starts; the core reports the marks as events but keeps no positions. Done when the core remembers prompt lines in the scrollback (dropped as history is trimmed), exports a call that scrolls the viewport to the previous or next prompt, and both shells bind it to a key (Ctrl+Shift+Up/Down on Windows, Cmd+Up/Down on macOS), with Kotlin/Native tests for the marks and the jump and a test per platform for the binding.

- T33.1 Core: the prompt lines and the jump. Execution plan: (1) `ScreenImages` (`images.rs`, the per-screen absolute line numbering that already survives scrolling, trimming and ED 3) keeps `prompts: Vec<u64>`, the sorted absolute lines of `OSC 133;A` marks; they are recorded on the primary screen only (the alternate screen has no history to jump in), deduplicated, and dropped when their line leaves the ring (`prune`), when ED 3 clears history, and when ED 2 wipes the screen rows they sit on (like image placements). RIS keeps the scrollback in this core, so it keeps the marks too. (2) A resize rewraps the primary screen, so the marks ride along as `TrackPoint`s next to the image anchors (`anchors`/`reanchor`) and a mark whose row is lost is dropped. (3) `Terminal::scroll_to_prompt(forward) -> bool` moves the display offset so the previous (or next) prompt above (below) the viewport top is the top row, or the live screen when the prompt is on it; false, and nothing moved, when there is none. (4) `tt_term_scroll_to_prompt(term, forward)` in `input.rs` beside `tt_term_scroll_display` (its own block in the header and `lib.rs`; `TT_OK`, or `TT_EMPTY` when there is no such prompt), ABI minor 8 to 9 (after T31's 8), header and C# bindings regenerated with the bindings generator. (5) Kotlin/Native tests: marks recorded, previous/next walk, no prompt answers `TT_EMPTY`, history trimming and ED 3 drop marks, ED 2, alternate screen ignored, a rewrapping resize keeps the marks on their lines, FFI export. Verify: `./gradlew check`, `swift test` in `macos/`, `dotnet test windows/Scull.slnx`, CI Windows jobs.

Acceptance:
- [ ] The work described above is implemented in Kotlin/Native (platform UI stays Swift/C# where this task says so).
- [ ] Tests and the `./gradlew check` gate (plus the platform tests this task names) pass.
