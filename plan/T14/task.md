---
status: todo
priority: P0
complexity: 5
readiness: 0%
agent:
---

# T14. macOS app: first light

A Kotlin/Native macOS app (`vesper-macos`, `macosArm64`) over AppKit through the Kotlin/Native platform libraries: an `NSApplication` with one window whose `NSView` draws the frame's text runs with CoreText (backgrounds, cursor, wide cells), sends key, text and mouse events to `vesper-term` through `vesper-input`, resizes the terminal and the PTY, and turns the core's wakeup into a main-queue redraw. The core is called directly: no C ABI. Done when the user's shell is usable in one window. Minimum macOS 26.

Before the app: the terminal stores the input modes the encoders need (mouse tracking 1000/1002/1003, SGR 1006 and pixels 1016, focus 1004, bracketed paste 2004, the kitty keyboard flag stack) and gains `key`, `text`, `mouse`, `paste`, `focus` and `scrollDisplay` calls that encode with `vesper-input` against them and write to the PTY. Title, working directory and OSC 52 events reach the window and the pasteboard (`NSPasteboard.general`; reads denied until a setting allows them).

The removed Swift app in `macos/` at eaeb20f is the reference for the AppKit side. The execution plan is written at claim time and split into slices of at most 500 lines.

Check: `./gradlew check`; the app runs the user's shell in one window, and a screenshot after typing `ls` shows the output.

Acceptance:
- [ ] The work described above is implemented in Kotlin/Native.
- [ ] Tests and the `./gradlew check` gate (plus the host tests this task names) pass.
