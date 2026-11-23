# T901. Done

## What changed

- `scull-ffi/tests/c` is gone. The same programs now live in `scull-ffi/tests/zig`:
  - `status.zig`, `two_threads.zig` and `events.zig` keep every check of the C versions;
  - `host.zig` holds the helpers that each C file used to repeat.
- They reach the core only through `scull`, the Zig module `zig translate-c` makes of `scull.h`.
- Every view array is read as a slice of the length the core reports, so Zig's Debug safety
  bounds every index against it.
- `:scull-ffi:hostTests` replaces `cTests` and is part of `check` on macOS and Linux.
  - TSan builds come from `zig build-exe -fsanitize-thread`.
  - ASan builds are `zig build-obj` objects linked by `clang -fsanitize=address`.
  - Both Zig builds pass `-fllvm`.
  - The LSan and TSan suppressions moved unchanged.
- `mise.toml` pins Zig 0.17.1, so the CI jobs install it through mise-action.
- `toolchain.md` has a Zig row; the clang row now covers only the ASan link.
- PLAN.md §5 is updated.

The Zig workarounds this needed (`sh -c` around translate-c, pthread instead of `std.Thread`,
`-fllvm`, a fixed macOS object target) are explained in `task.md` → Findings and in the build
script.

## How it was checked

- **macOS arm64:**
  - `./gradlew ktlintFormat check` passes.
  - All six runs of `hostTests` pass: status, two_threads and events, each under ASan and under TSan.
  - `zig fmt --check scull-ffi/tests/zig` is clean.
- **Linux x86_64:**
  - The programs were cross-compiled with Zig against the linuxX64 `libscull.so`: plain, TSan and the ASan object.
  - Run in Colima (`ubuntu:24.04`, amd64) without sanitizers, all three pass.
  - Sanitized programs are killed by Colima's emulation (as in T10), so the Linux sanitizer runs are CI's.
  - The clang ASan link on Linux was not run locally: no local amd64 image has clang or gcc.

## Commits

- `T901: claim the task`
- `T901: port the scull-ffi host tests from C to Zig`
- `T901: close the task`

PR: see the branch `t901-zig-tests`, stacked on `t13.2-selection` (#19).
