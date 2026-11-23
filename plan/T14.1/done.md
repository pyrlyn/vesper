# T14.1. Done

PR: https://github.com/pyrlyn/vesper/pull/31 (stacked on #26).

## What changed

- `scull-input`: `Key.ofCode(code)` names the key behind a `tt_key_event.key` code: kitty's
  functional key codes for named keys, else the code point a main-block key types. Control
  characters, surrogates and numbers past U+10FFFF name nothing.
- C ABI 0.5 (`scull.h`), in the layout the Rust core had and the macOS and Windows hosts already
  use:
  - `TT_KEY_*`, `TT_MOD_*` and `TT_MOUSE_*` constants; `tt_key_event` and `tt_mouse_event`.
  - `tt_term_key`, `tt_term_text`, `tt_term_paste`, `tt_term_focus`, `tt_term_mouse` and
    `tt_term_scroll_display` (new `InputExports.kt`).
- One `send` path for all five inputs: the terminal encodes under the lock against its current
  modes (stored since T12), typed input brings the viewport back to the screen, and the bytes go to
  the child whole. Statuses: `TT_OK` when nothing is sent, `TT_CLOSED` without a child, `TT_FULL`
  when the queue lacks room, and also, before any copy, for text longer than the queue can ever
  hold (256 KiB). `tt_term_mouse` sets `*taken` whatever the status, and 0 on a refused event.
- `utf8()` in `Options.kt` is the one strict UTF-8 decode: `tt_str.text()`, the clipboard reply and
  the new exports use it.

Not here:
- The C# bindings: they moved to T17 with T10.
- A native host test calling the new prototypes: the Zig harness of T901 (PR #23) is not merged.
  The Kotlin tests call the exports with the header's own structs.

## How it was checked

- `KeyCodeTest` (scull-input): every named key's code, the round trip of the four coded enums,
  code points, and the codes that name nothing.
- `InputExportsTest` (scull-ffi):
  - A raw, non-echoing `sh` child prints in hex the bytes it reads. Keys, an arrow and committed
    text reach it, with the control character dropped. Bracketed paste, a focus report and an SGR
    mouse press reach it once the program asks for them.
  - Without a child: `TT_CLOSED` for bytes, `TT_OK` for a legacy release, a composing key and focus
    with reports off.
  - `taken` follows mouse tracking.
  - The viewport returns to the screen on a key but not on focus. `tt_term_scroll_display` clamps
    both ways.
  - Every malformed argument answers `TT_INVALID`, and oversized input answers `TT_FULL` before
    it is read.
- `./gradlew check` on macOS arm64 (ktlint, the native tests, the C programs under ASan+UBSan and
  TSan).
- The scull-input and scull-ffi tests on `linuxX64` under Colima (`ubuntu:24.04`).
