---
status: in progress
priority: P1
complexity: 3
readiness: 100%
agent: Claude Code / claude-opus-5-5
---

# T14.1. Input exports in the C ABI

The platform hosts (T14.2 macOS, T17 Windows) send keys, committed text, pastes, focus changes and
mouse events to the core through the C ABI, and scroll the viewport through history. The terminal
already encodes all of them (T12); this slice exports them. Done when the six exports below exist in
`scull.h` and libscull with the layout the Rust ABI had, and tests cover their statuses and bytes.

## Execution plan

1. `scull-input`: `Key.ofCode(code)`, the key behind a `tt_key_event.key` code (kitty's functional
   codes, else a non-control code point), with tests.
2. `scull-ffi/include/scull.h`: `TT_KEY_*`, `TT_MOD_*`, `TT_MOUSE_*`, `tt_key_event`,
   `tt_mouse_event`, and `tt_term_key`, `tt_term_text`, `tt_term_paste`, `tt_term_focus`,
   `tt_term_mouse`, `tt_term_scroll_display`; ABI minor 5.
3. `scull-ffi/.../InputExports.kt`: one `send` helper encodes under the lock, brings the viewport
   back to the screen for typed input, and writes the bytes whole (`TT_FULL` when the queue lacks
   room, `TT_CLOSED` without a child). Malformed structs and codes answer `TT_INVALID`.
4. `InputExportsTest`: bytes reach a `cat` child, mode-dependent encodings (bracketed paste, focus
   reports, mouse tracking and `taken`), the viewport following typed input, statuses without a
   child, and invalid arguments.
5. Verify: `./gradlew ktlintFormat check`, the scull-input and scull-ffi tests on Linux under Colima.
