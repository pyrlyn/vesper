# T10. Done

The C ABI of the Kotlin/Native core, in PR https://github.com/pyrlyn/vesper/pull/14 (stacked on #13).

## What was done

- `scull-ffi/include/scull.h`, hand-written, is the source of truth.
  - It keeps the Rust ABI's names, layouts and numbering (`tt_*`, `TT_ABI_VERSION`, now 0.1).
  - The exports compile against it through cinterop (`TT_TYPES_ONLY` hides the prototypes), so a layout that drifts fails the build.
- Exports: `tt_abi_version`, `tt_term_new`, `tt_term_spawn`, `tt_term_feed`, `tt_term_write`, `tt_term_resize_begin`, `tt_term_resize`, `tt_term_poll_event`, `tt_term_event_text`, `tt_term_free`, `tt_frame_new`, `tt_frame_update`, `tt_frame_free`.
- One guard on every export: a refusal maps to its status, any other throw answers `TT_PANIC` and poisons the terminal.
- `struct_size`: options are read up to `min(struct_size, sizeof)` and at least through `scrollback`; views and events need `struct_size >= sizeof`.
- `tt_term_spawn` drives a `Terminal` from scull-pty's threads through a `Sink`. The wakeup stays coalesced until the next frame update or event poll.
- `tt_frame_update` fills cells (one little-endian `u64` each), rows with UTF-8 runs, styles, scrolls and dirty rows into buffers the frame owns.
- Reused: the threads, `FairLock` and wakeup hook of scull-pty, and the `Frame` of scull-term (which gained `packedCell` and `styleCount`).
- Not done here: the C# bindings, moved to T17.1 on the creator's word.

## Commits

- `6ebb7a4` T10: handles, frame API, events and exception boundary over the C ABI
- `456a67f` T10: C tests of the ABI under ASan+UBSan and TSan

## How it was checked

- 29 Kotlin/Native tests in `scull-ffi`: every status, poisoning, struct sizes, frame contents, events, spawn and wakeup coalescing.
  - A mutation that drops the coalescing makes its test fail.
- `tests/c/two_threads.c` and `tests/c/status.c` pass under ASan+UBSan and under TSan on macOS (`:scull-ffi:cTests`, part of `check`).
- `./gradlew ktlintFormat check` is green on macOS: 335 tests, 0 failures.
- On Linux in Colima the Kotlin tests pass, and so do both C programs without sanitizers.
  - Colima's amd64 emulation kills even an empty sanitized program, so the Linux sanitizer run is CI's.
