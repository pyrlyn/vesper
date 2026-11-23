# T12.1. Done

## What was done

- **`vesper-input`** (package `vesper.input`), ported from the earlier Rust core with its spec tables:
  - `ByteSink`, a reusable byte buffer, so a host that keeps one sink encodes a stream of keys without allocating;
  - `Key.kt`: `Modifiers` (kitty bits, `bits + 1` on the wire), `KeyAction`, the keypad, media, modifier and system key enums with their kitty codes, `Key`, `KeyEvent`, the functional-key and Ctrl tables;
  - `Legacy.kt`: xterm encoding with DECCKM, DECKPAM and modifyOtherKeys levels 1 and 2, falling back to `CSI code ; mods u` where legacy has no spelling;
  - `Kitty.kt`: `KittyFlags`, `SetMode`, `KittyFlagStack` (capped at 8, evicts the oldest), `KittyKeyboard` (one stack per screen) and the `CSI … u` encoder for every flag combination;
  - `Win32.kt`: win32-input-mode records with US-layout virtual keys and scan codes, surrogate pairs as two records;
  - `Keyboard.kt`: `KeyModes`, `ModifyOtherKeys` and `encodeKey`, which picks win32, then kitty, then legacy;
  - `encodeText` for committed IME text, without control characters.
- **`vesper-term`**:
  - `Modes` keeps the kitty stacks, `modifyOtherKeys` and `win32Input` (`?9001`, answered by DECRQM); RIS clears all of them;
  - `Keyboard.kt` handles `CSI ? u`, `CSI > flags u`, `CSI < n u`, `CSI = flags ; mode u`, XTMODKEYS `CSI > 4 ; n m` and `CSI > 4 n`; a plain `CSI u` stays restore-cursor;
  - `Terminal.keyModes()`, `encodeKey(event, out)` and `encodeText(text, out)`.
- No native code and no new dependencies.

## Commits

- `292766a` T12.1: key encoders (legacy, kitty, win32-input-mode) in vesper-input
- `a240ea4` T12.1: keep kitty keyboard stacks, modifyOtherKeys and win32-input-mode in vesper-term
- PR: see the T12.1 pull request on branch `t12-t12-input`.

## How it was checked

- `vesper-input`: 53 tests.
  - They are the Rust table tests (kitty legacy C0 and text-key tables, functional keys, event types, associated text, alternates, stack rules, ConPTY examples).
  - Plus seeded property tests: 20 000 random key events in random modes never throw, and the flag stack never passes its cap.
- `vesper-term`: 97 tests, 9 of them new. They cover the sequences, both screens' stacks, RIS, XTMODKEYS, `?9001` with DECRQM, and that `CSI u` still restores the cursor.
- `./gradlew ktlintFormat check` passes on macOS arm64, including the C ABI tests.
- The `vesper-input` and `vesper-term` test binaries pass on linuxX64 in Colima (ubuntu:24.04).
