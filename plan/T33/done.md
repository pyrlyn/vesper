# T33. Done

The core remembers where shells said their prompts start, and both apps jump between them.

- **Core (`vesper-term`).**
  - `Prompts.kt` keeps the primary screen's `OSC 133;A` lines, sorted and deduplicated, as
    absolute lines beside the image anchors.
  - The marks are dropped when the ring drops their line, on ED 3, when ED 2 or RIS blanks their
    rows, and when a resize loses their row.
  - A rewrapping resize carries the marks as tracking points, also while the alternate screen is
    shown. The alternate screen records nothing.
  - `Terminal.scrollToPrompt(forward)` makes the prompt before or after the viewport's top row the
    top row. When that prompt sits on the screen below its top row, it scrolls as far as history
    allows. It answers false and moves nothing when there is no such prompt.
- **Session and hosts.**
  - `Session.scrollToPrompt` runs under the session's lock.
  - `vesper-host` `promptDirection` maps the actions to a direction.
  - The macOS view (⌘↑/⌘↓) and the Windows app (Ctrl+Shift+↑/↓) jump, and beep when there is no
    prompt to jump to. Both used to beep for these bindings unconditionally.

How it was checked:

- `./gradlew check` on macOS arm64. The mingwX64 test binaries of `vesper-windows`,
  `vesper-config`, `vesper-pty` and `vesper-host` were linked.
- The linuxX64 test binaries ran in Colima.
- Tests:
  - `PromptsTest`: recording, the walk both ways, no prompts, trimming, ED 2 and ED 3, RIS, the
    alternate screen, a rewrapping resize, and a resize while the alternate screen is shown.
  - `BindingsTest`: each platform's chords and their directions.
  - `SessionTest`: a shell that prints marks.
  - The macOS `PromptJumpTest`: ⌘↑/⌘↓ on `/bin/sh`.
  - The Windows `SettingsTest`: Ctrl+Shift+↑/↓ are the app's, and plain arrows are the program's.
    It runs on the Windows CI runner.
