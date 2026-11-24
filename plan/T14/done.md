# T14. Done

- `vesper-session` (new): a `Terminal` and its `Pty` under one `FairLock`. The PTY sink feeds the
  terminal, holds replies up to 64 KiB, records a failure instead of throwing on the reader thread,
  and coalesces wakeups to one until the host looks. Input goes through the core's encoders into an
  ordered outbox of up to 1 MiB that survives partial writes. Clipboard reads (OSC 52 `?`) are
  answered with a denial and never reach the host. Resize reaches both the frame and the child.
- `vesper-macos` (new): `macosArm64` executable over AppKit and CoreText.
  - Pure, tested on every runner: xterm palette (reverse, dim), key-code map by position with
    Option and Ctrl handling, cell geometry, launch environment (`TERM`, `COLORTERM`,
    `TERM_PROGRAM`, UTF-8 `LANG` when unset).
  - AppKit: one window sized to 80×24 with cell resize increments; an `NSView` that draws runs
    (backgrounds, bold and italic faces, underline, strike, cursor) with CoreText kerned to
    whole-point cells; key, paste, mouse, motion, wheel and focus input; live resize with output
    paused; the wakeup posted to the main queue; title, directory, bell and OSC 52 writes to
    `NSPasteboard.general`; quit when the window closes.
- Wide-cell runs are drawn as one CoreText line; a fallback glyph whose advance is not two cells
  can still sit off its column. T15's atlas places glyphs per cell.

Commits on branch `t14-macos-first-light` (stacked on T902's PR, pyrlyn/vesper#33).

Checked:
- `./gradlew ktlintFormat check` on macOS arm64: green.
- Linux x64 tests of all 12 modules in Colima (`ubuntu:24.04`): green, including
  `vesper-session` (5) and `vesper-macos` (12).
- `FirstLightTest` types `echo vesper; ls /` into `/bin/sh` through synthetic `NSEvent` key
  events, waits for the output line and saves the rendering to
  `vesper-macos/build/reports/first-light/first-light.png`: prompt, echo and coloured `ls`
  columns aligned.
- The executable, wrapped in a scratch `.app` bundle, launched and ran the login shell.
