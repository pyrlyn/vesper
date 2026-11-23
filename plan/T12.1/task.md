---
status: in progress
priority: P1
complexity: 4
readiness: 100%
agent: Claude Code / claude-opus-5-5
---

# T12.1. Key encoding

The first half of T12. A host hands the core a platform-neutral key event and gets back the bytes the program expects for the modes it set:

- legacy xterm encoding, with application cursor and keypad modes and modifyOtherKeys;
- the kitty keyboard protocol and its per-screen flag stack;
- win32-input-mode, which ConPTY sets.

## Findings

- **Reuse.** The creator's Rust scull already has these encoders with table tests taken from the specifications:
  - `crates/scull-input/src/{key,keyboard,kitty,legacy,win32,bytes}.rs`;
  - the wiring in `crates/scull-term/src/input.rs`.
  - They are ported to Kotlin, tables and tests included. They were written from the kitty specification, not kitty's GPL code.
- **Nothing to build on in vesper.**
  - `scull-input` is an empty module that `scull-term` already exposes as `api`.
  - `Modes` keeps DECCKM and DECKPAM, but not modifyOtherKeys, win32-input-mode or a kitty stack.
  - The parser already passes `<`, `=`, `>` and `?` as private markers.
- **No native code.** Everything is common Kotlin.

## Execution plan

1. **`scull-input`, package `scull.input`.**
   - `ByteSink`: a growable byte buffer the encoders append to. A reused one makes encoding allocation-free, and the core modules take no dependencies.
   - `Key.kt`: `Modifiers`, `KeyAction`, the key enums with their kitty codes, `Key`, `KeyEvent`, the functional-key and Ctrl tables.
   - `Legacy.kt`, `Kitty.kt` (flags, `SetMode`, `KittyFlagStack` capped at 8, `KittyKeyboard`), `Win32.kt`.
   - `Keyboard.kt`: `KeyModes`, `ModifyOtherKeys` and `encodeKey`, which picks win32, kitty or legacy.
2. **`scull-term`.**
   - `Modes` holds the kitty stacks, `modifyOtherKeys` and win32-input-mode (`?9001`, answered by DECRQM).
   - Dispatch reads `CSI ? u`, `CSI > flags u`, `CSI < n u`, `CSI = flags ; mode u` and XTMODKEYS (`CSI > 4 ; n m`).
   - RIS clears all of it.
   - `Terminal.keyModes()`, `encodeKey(event, out)` and `encodeText(text, out)` (committed IME text without control characters).
3. **Verify.**
   - The Rust tables as `commonTest`, plus a seeded property test that no key event in any mode throws and the stack never passes its cap.
   - `scull-term` tests for the sequences and both screens' stacks.
   - `./gradlew ktlintFormat check`, and Linux in Colima.

## Acceptance

- [x] The work described above is implemented in Kotlin/Native (platform UI stays Swift/C# where this task says so).
- [x] Tests and the `./gradlew check` gate (plus the platform tests this task names) pass.
