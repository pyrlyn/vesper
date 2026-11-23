---
status: in progress
priority: P1
complexity: 5
readiness: 5%
agent: Claude Code / claude-opus-5-5
---

# T10. C ABI

`scull-ffi`: opaque handles, the frame API, one wakeup callback, a polled event queue, status codes, a boundary exception handler on every export with per-terminal poisoning, an ABI version check and `struct_size` in every struct. The header and C# bindings are committed and diff-checked in CI. Done when a C test program runs create, feed, update and free under the sanitizers from two threads.

## Findings

- **The hosts already speak the Rust ABI.**
  - The names are `tt_*`, the version is `TT_ABI_VERSION`, and the C# bindings are `NativeMethods` in `Scull.Native`.
  - See `windows/Scull.Core/Abi.cs` and `macos/Sources/CScull/module.modulemap`.
  - The Kotlin ABI keeps those names, layouts and numbering, so T14 and T17 move the hosts over by pointing them at the new header.
  - The placeholder export `scull_abi_version` becomes `tt_abi_version`.
- **The header is hand-written, not generated.**
  - Kotlin/Native cannot emit C structs from Kotlin code, so the direction of the Rust build flips: `scull-ffi/include/scull.h` is the source of truth.
  - The Kotlin exports compile against the header through cinterop, so a layout that drifts from it is a compile error, not a test failure.
  - The C# bindings are generated from the header.
- **T10 ships the subset the core has today.**
  - Create: `tt_term_new`, `tt_term_spawn`.
  - Feed, write, and resize: `tt_term_resize_begin`, `tt_term_resize`.
  - Free.
  - Events: bell, title, child exit.
  - Frames: new, update, free.
- **The rest of the Rust ABI comes later.**
  - `tt_frame_view` stops after `dirty_len`. Images (T19) and preedit (T16) append their fields in the Rust order, so the final layout is the Rust one.
  - Clipboard and links come with T13.3, input with T12, selection and search with T13.2, config with T20.
- **`scull-pty` already owns the threads, the lock and the wakeup hook.**
  - It runs the reader, I/O, writer and waiter threads, and holds the `FairLock` and the wakeup hook.
  - The FFI adds the `Sink` that drives a `Terminal`, and the coalescing flag.

## Execution plan

1. **Handles and frame API (T10.1).**
   - `include/scull.h` and `nativeInterop/cinterop/scull.def`, with the prototypes hidden from cinterop.
   - `@CName` exports with one guard that catches every `Throwable`, answers `TT_PANIC` and poisons the terminal.
   - The `struct_size` rules: read `min(size, sizeof)` of an options struct, write `min(size, sizeof)` of a view.
   - An ABI check at create.
   - `tt_frame_update` fills cells, rows with UTF-8 runs, styles, scrolls and dirty rows into buffers the frame owns.
2. **Threads and events (T10.2).**
   - `tt_term_spawn` over `Pty.spawn` with a `Sink` that handles feed, replies, child exit, and 2026 holds through `syncHeld`/`syncDeadline`.
   - The wakeup stays coalesced until the next update or poll.
   - `tt_term_poll_event` and `tt_term_event_text`.
   - `tt_term_write`.
   - `tt_term_resize_begin` and `tt_term_resize`: pause, one resize, resume.
3. **Bindings and C tests (T10.3).**
   - C# bindings generated from the header and committed, with a `just` recipe and a CI diff check.
   - C programs `tests/c/two_threads.c` and `tests/c/status.c`, built with clang against the shared library.
   - A Gradle task on the host target runs them under ASan+UBSan and under TSan.
4. **Verify.**
   - Kotlin/Native tests for every status path, for poisoning and for the struct-size rules.
   - `./gradlew ktlintFormat check`.
   - The C programs on macOS, and on Linux in Colima.

## Acceptance

- [ ] The work described above is implemented in Kotlin/Native (platform UI stays Swift/C# where this task says so).
- [ ] Tests and the `./gradlew check` gate (plus the platform tests this task names) pass.
