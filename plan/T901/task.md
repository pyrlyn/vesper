---
status: in progress
priority: P2
complexity: 3
readiness: 10%
agent: Claude Code / claude-opus-5-5
---

# T901. Port the scull-ffi C tests to Zig

The host programs in `scull-ffi/tests/c` (status, two_threads, events) call the C ABI through the
prototypes of `scull.h` under ASan and TSan. The creator's rule for native code is Zig first, C or
C++ only where Zig does not fit, so the programs move to Zig with the same checks.

Done means: `scull-ffi/tests/zig` holds the three programs, `tests/c` is gone, `:scull-ffi:cTests`
(part of `check` on macOS and Linux) builds and runs them under both sanitizers, and CI installs
Zig through `mise.toml`.

## Findings

- Zig 0.17.1 removed `@cImport`; `zig translate-c -lc -I include include/scull.h` turns the header
  into a Zig module that the programs import (checked locally on Zig 0.17.1).
- Zig instruments its own code for TSan (`-fsanitize-thread`, its runtime built from source) but has
  no ASan. The ASan variant is a Zig object linked by `clang -fsanitize=address`: the ASan runtime
  then replaces malloc and free, so LeakSanitizer and the double-free and bad-free checks still
  cover the Kotlin/Native library. Loads in the Zig code itself are not instrumented; Zig's Debug
  safety checks bound every slice the programs take of a view to the length the core reports.
- `std.Thread.spawn` crashes the ASan runtime at start-up ("unable to unmmap": it maps the thread's
  stack itself), so the feeder thread goes through `pthread_create`, as the C program did.

## Execution plan

1. Translate `scull.h` in a Gradle task into `build/ztest/scull.zig`.
2. Rewrite the programs as `tests/zig/{status,two_threads,events}.zig` with a shared
   `tests/zig/host.zig` for the helpers the three C files each repeated; move the `.supp` files.
3. Gradle: TSan through `zig build-exe -fsanitize-thread`; ASan through `zig build-obj` and a
   `clang -fsanitize=address` link; same suppressions and environment.
4. `mise.toml` pins Zig; `toolchain.md` gets the Zig row and the narrower clang row.
5. Verify: `./gradlew :scull-ffi:cTests` and `./gradlew ktlintFormat check` on macOS; on Linux the
   linuxX64 library with Zig cross-compiled programs in Colima (TSan executables as built, the ASan
   object linked by gcc's `-fsanitize=address`, since no local image carries clang).
