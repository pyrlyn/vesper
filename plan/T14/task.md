---
status: in progress
priority: P0
complexity: 5
readiness: 100%
agent: Claude Code / claude-opus-5-5
---

# T14. macOS app: first light

A Kotlin/Native macOS app (`vesper-macos`, `macosArm64`) over AppKit through the Kotlin/Native platform libraries: an `NSApplication` with one window whose `NSView` draws the frame's text runs with CoreText (backgrounds, cursor, wide cells), sends key, text and mouse events to `vesper-term` through `vesper-input`, resizes the terminal and the PTY, and turns the core's wakeup into a main-queue redraw. The core is called directly: no C ABI. Done when the user's shell is usable in one window. Minimum macOS 26.

Before the app: the terminal stores the input modes the encoders need (mouse tracking 1000/1002/1003, SGR 1006 and pixels 1016, focus 1004, bracketed paste 2004, the kitty keyboard flag stack) and gains `key`, `text`, `mouse`, `paste`, `focus` and `scrollDisplay` calls that encode with `vesper-input` against them and write to the PTY. Title, working directory and OSC 52 events reach the window and the pasteboard (`NSPasteboard.general`; reads denied until a setting allows them).

The removed Swift app in `macos/` at eaeb20f is the reference for the AppKit side. The execution plan is written at claim time and split into slices of at most 500 lines.

Check: `./gradlew check`; the app runs the user's shell in one window, and a screenshot after typing `ls` shows the output.

Execution plan:

1. Session layer, new module `vesper-session` (it sees both the terminal and the PTY, which never
   see each other). `Session` owns a `Terminal`, the `FairLock` and the `Pty`; its `Sink` is the
   adapter the removed C ABI had (`TermHandle` at eaeb20f): feed, replies, sync-held wakeups, and a
   throwing terminal stops only this session. One coalesced wakeup until the host reads the frame.
   Calls: `update(frame)`, `key`, `text`, `mouse`, `paste`, `focus`, `scrollDisplay`, `resize`,
   `pauseOutput`/`resumeOutput`, `pollEvent` (OSC 52 reads answered with a denial), `exitStatus`,
   `close`. Input that does not fit the PTY's write queue waits in an outbox flushed on the next
   call. Tests (Unix PTY): output reaches the frame; typed text comes back through `cat`; a
   clipboard read is denied; resize reaches the frame.
2. Host, new module `vesper-macos`: pure parts in `commonMain` so every runner tests them (xterm
   palette and style colours, Mac virtual key codes and modifier flags to `KeyEvent`, pixel to
   cell); AppKit in `macosMain`: `NSApplication`, one window, an `NSView` that draws runs with
   CoreText (backgrounds, cursor, wide cells), key, text, mouse and scroll input, live resize with
   output paused, the wakeup posted to the main queue, title, directory and OSC 52 writes to
   `NSPasteboard.general`. An executable for `macosArm64`.
3. Wire `settings.gradle.kts`, README, PLAN; gate with `./gradlew ktlintFormat check` and the Linux
   tests in Colima; run the app, type `ls`, take a screenshot.

Acceptance:
- [x] The work described above is implemented in Kotlin/Native.
- [x] Tests and the `./gradlew check` gate (plus the host tests this task names) pass.
