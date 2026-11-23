# T14. macOS app: first light

Status: not started

A SwiftUI shell with an `NSView` surface that draws frames with CoreText, sends key and mouse events, and resizes. Done when a shell is usable in one window.

Execution plan (split to fit the budget):

- T14.1 Input through the C ABI: store the mouse tracking modes (1000/1002/1003, 1006 SGR, 1016 pixels), focus reporting (1004), bracketed paste (2004) and the kitty keyboard flags stack (CSI > u, < u, = u, ? u) in `vesper-term`, reusing `vesper-input`'s types rather than copying them; export `tt_term_key`, `tt_term_text`, `tt_term_mouse`, `tt_term_paste`, `tt_term_focus` and `tt_term_scroll_display` that encode with `vesper-input` against the current modes and write to the PTY; regenerate the header and C# bindings, bump the ABI minor.
- T14.2 macOS app in `macos/`: a Swift package with the SwiftUI app and an `NSView` surface, a module map over `scull.h`, and a the macOS app build recipe that builds `scull-ffi` as a static library for `aarch64-apple-darwin` and then the app bundle. The view draws the frame's text runs with CoreText (one `CTLine` per run, backgrounds, cursor, wide cells), maps `NSEvent` keys, text input and mouse to the T14.1 exports, sends pixel sizes on resize, and turns the wakeup callback into a main-queue redraw. Minimum macOS 26.
- Events (moved from T13.3, over its exports): `TerminalSession` keeps the title and working directory, writes OSC 52 writes to a pasteboard (`NSPasteboard.general` by default) and denies reads until a setting allows them; `TerminalView` puts the title on its window; Swift tests on a named pasteboard, `swift test` in `macos/`.
- Verify: Kotlin/Native tests for every new export and mode; `./gradlew check` and the C ABI sanitizer test exit 0; the macOS app build builds; the app runs the user's shell in one window, and a screenshot after typing `ls` shows the output.

Acceptance:
- [ ] The work described above is implemented in Kotlin/Native (platform UI stays Swift/C# where this task says so).
- [ ] Tests and the `./gradlew check` gate (plus the platform tests this task names) pass.
