# T12.2. Done

## What was done

- **`vesper-input`**, ported from the earlier Rust core with its spec tables:
  - `Mouse.kt`:
    - `MouseButton`, `MouseAction`, `MouseEvent`, `MouseTracking` and `MouseEncoding`, plus `MouseModes`.
    - `encodeMouse` covers X10, normal, button-event and any-event tracking in the default, UTF-8, SGR, urxvt and SGR-pixel encodings.
    - It follows xterm's clamping and past-end marker. SGR reports which button was released.
  - `Paste.kt`: `encodePaste` strips the 7- and 8-bit start and end markers from bracketed text, so a pasted marker cannot end the paste early or leave a marker behind. Unbracketed text sends LF as CR.
  - `Focus.kt`: `encodeFocus` writes `CSI I` or `CSI O`.
- **`vesper-term`**:
  - `Modes` keeps the mouse tracking (`?9`, `?1000`, `?1002`, `?1003`), the encoding (`?1005`, `?1006`, `?1015`, `?1016`) and alternate scroll (`?1007`, on at power-on).
    - Resetting any tracking or encoding mode frees the mouse, as in xterm.
    - DECRQM reports SET only for the active mode.
    - RIS restores the defaults.
  - New `Terminal` methods:
    - `mouseModes()`;
    - `encodeMouse(event, out)` returns whether the program took the event. While tracking it always does. On the alternate screen with `?1007`, a wheel step becomes a cursor key.
    - `encodePaste(text, out)`;
    - `encodeFocus(focused, out)`, which writes a report only while `?1004` is set.
- No native code and no new dependencies.

## Commits

- `e88d4d0` T12.2: claim the task
- `ad991db` T12.2: mouse, bracketed paste and focus encoders in vesper-input
- `65029c0` T12.2: mouse modes, alternate scroll, paste and focus in vesper-term
- PR: see the T12.2 pull request on branch `t12.2-mouse`.

## How it was checked

- `vesper-input`: 69 tests, 16 of them new. They are:
  - the Rust tables for button codes, tracking filters, X10 limits, UTF-8 limits, SGR, urxvt, SGR-pixel and paste markers;
  - seeded property tests: 20 000 random mouse events in random modes never throw, and 5 000 random bracketed bodies never contain a marker.
- `vesper-term`: 103 tests, 6 of them new, ported from Rust's `input.rs`. They cover modes and DECRQM, tracked encoding, alternate scroll, paste, focus and RIS.
- The old placeholder test that expected the mouse modes to be unrecognised now expects them to be reported.
- `./gradlew ktlintFormat check` passes on macOS arm64.
- The `vesper-input` and `vesper-term` test binaries pass on linuxX64 in Colima (ubuntu:24.04).
