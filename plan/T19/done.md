# T19. Done

Done in four parts. Each part's `done.md` says what changed and how it was checked:

- `plan/T19.1`: the image store, with a byte quota and LRU eviction, and iTerm2 inline images.
- `plan/T19.2`: the sixel decoder.
- `plan/T19.3`: the kitty graphics protocol.
- `plan/T19.4`: the wiring into `vesper-term`, the frame and C ABI 0.4.

Acceptance:

- Golden tests per protocol: `ImagesTest` in `vesper-term`, end to end, plus each decoder's own
  tests in `vesper-image`.
- A fuzz entry per decoder and for the store, in `vesper-image/.../Fuzz.kt`: `fuzzInlineImages`,
  `fuzzSixel`, `fuzzKitty` and `fuzzImageStore`.
- `./gradlew check` passes on macOS arm64, and the linuxX64 tests pass in Colima (T19.4).

Left to other tasks:

- The `images.zig` host test, after T901.
- The C# bindings, in T17.
