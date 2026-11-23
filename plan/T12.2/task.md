---
status: in progress
priority: P1
complexity: 3
readiness: 10%
agent: Claude Code / claude-opus-5-5
---

# T12.2. Mouse, paste and focus encoding

The second half of T12.

- `scull-input`:
  - mouse events in X10, normal, button-event and any-event tracking;
  - the default, UTF-8, SGR, urxvt and SGR-pixel encodings;
  - bracketed paste with end-marker stripping;
  - focus reports.
- `scull-term` keeps the mouse modes (`?9`, `?1000`–`?1003`, `?1005`, `?1006`, `?1015`, `?1016`) and alternate scroll (`?1007`), answers DECRQM for them, and encodes mouse, paste and focus against them.

The creator's Rust scull (`crates/scull-input/src/mouse.rs`, `paste.rs`, `focus.rs`, `crates/scull-term/src/input.rs`) is ported, as T12.1 ports the key encoders.

Done when the table-driven tests match xterm's ctlseqs, `./gradlew check` passes and Linux passes in Colima.

## Findings

- Builds on T12.1 (`ByteSink`, `Modes`, `Terminal.encodeKey`), so the branch starts from T12.1's.
- The Rust encoders and their tests port one to one. No native code, no new dependencies.

## Execution plan

1. **`scull-input`.**
   - `Mouse.kt`: `MouseButton`, `MouseAction`, `MouseEvent`, `MouseTracking`, `MouseEncoding`, `MouseModes`, `encodeMouse`.
   - `Paste.kt`: `encodePaste` strips the 7- and 8-bit markers in one pass; unbracketed text turns LF into CR.
   - `Focus.kt`: `encodeFocus`.
2. **`scull-term`.**
   - `Modes` keeps the mouse modes and `?1007`, which is on at power-on as in xterm.
   - Resetting any tracking or encoding mode frees the mouse, as in xterm.
   - DECRQM answers for all of them.
   - New `Terminal` methods:
     - `mouseModes()`;
     - `encodeMouse(event, out)`: the program takes the event while tracking; on the alternate screen with `?1007`, a wheel step turns into a cursor key;
     - `encodePaste(text, out)`;
     - `encodeFocus(focused, out)`, only while `?1004` is set.
3. **Verify.**
   - The Rust tables as `commonTest`, plus seeded property tests: no mouse event throws, and a bracketed body never holds a marker.
   - The `scull-term` tests from Rust's `input.rs`.
   - `./gradlew ktlintFormat check`, and Linux in Colima.

## Acceptance

- [ ] The work described above is implemented in Kotlin/Native (platform UI stays Swift/C# where this task says so).
- [ ] Tests and the `./gradlew check` gate (plus the platform tests this task names) pass.
