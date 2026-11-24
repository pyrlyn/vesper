---
status: todo
priority: P1
complexity: 5
readiness: 0%
agent:
---

# T17. Windows app and renderer

A Kotlin/Native Windows app (`vesper-windows`, `mingwX64`) over Win32: a window with a DXGI swap chain drawn by a Direct3D 11 renderer with a DirectWrite glyph atlas, the user's shell through `vesper-pty` (ConPTY), keys, text, mouse, wheel, paste and focus mapped to the core, live resize, DPI changes, and the core's title, directory and OSC 52 events. The core is called directly: no C ABI. Done when a shell is usable and the T3 benchmark runs on Windows.

First step, a spike: Win32 and Direct3D 11 have C interfaces that Kotlin/Native cinterop can bind (`windows.h`, `d3d11.h` and `dxgi1_2.h` with `COBJMACROS`), but `dwrite.h` is C++ only. Pick how DirectWrite is reached (a small Zig shim exposing a C API is the first choice, C++ only if Zig cannot) and record it in `research.md` and `toolchain.md`.

The removed C# app in `windows/` at eaeb20f (its renderer, app shell, input mapping and event policy) is the reference design. The execution plan is written at claim time and split into slices of at most 500 lines.

Check: `./gradlew check` on the Windows runner, renderer tests on WARP into an offscreen texture; how the app looks on a desktop needs a human.

Acceptance:
- [ ] The work described above is implemented in Kotlin/Native.
- [ ] Tests and the `./gradlew check` gate (plus the host tests this task names) pass.
