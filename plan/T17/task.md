---
status: in progress
priority: P1
complexity: 5
readiness: 40%
agent: Claude Code / claude-opus-5-5
---

# T17. Windows app and renderer

A Kotlin/Native Windows app (`vesper-windows`, `mingwX64`) over Win32: a window with a DXGI swap chain drawn by a Direct3D 11 renderer with a DirectWrite glyph atlas, the user's shell through `vesper-pty` (ConPTY), keys, text, mouse, wheel, paste and focus mapped to the core, live resize, DPI changes, and the core's title, directory and OSC 52 events. The core is called directly: no C ABI. Done when a shell is usable and the T3 benchmark runs on Windows.

First step, a spike: Win32 and Direct3D 11 have C interfaces that Kotlin/Native cinterop can bind (`windows.h`, `d3d11.h` and `dxgi1_2.h` with `COBJMACROS`), but `dwrite.h` is C++ only. Pick how DirectWrite is reached (a small Zig shim exposing a C API is the first choice, C++ only if Zig cannot) and record it in `research.md` and `toolchain.md`.

The removed C# app in `windows/` at eaeb20f (its renderer, app shell, input mapping and event policy) is the reference design. The execution plan is written at claim time and split into slices of at most 500 lines.

Check: `./gradlew check` on the Windows runner, renderer tests on WARP into an offscreen texture; how the app looks on a desktop needs a human.

Acceptance:
- [ ] The work described above is implemented in Kotlin/Native.
- [ ] Tests and the `./gradlew check` gate (plus the host tests this task names) pass.

## Execution plan

Slices of at most 500 lines each, one PR each, every one green on the Windows runner (`./gradlew check`, which runs the `mingwX64` tests). No machine here runs Windows code, so each slice's tests are what proves it.

1. **Spike and bindings.** The `vesper-windows` module; `directx.def` binds Direct3D 11, DXGI and DirectWrite (decision in `research.md` §6.2: DirectWrite headers from Zig, no shim). `Gpu` (a hardware or WARP device), `OffscreenTarget` (render and read back), `FontSystem` and `FontFace`. Tests: WARP clears an offscreen texture that reads back exactly; Consolas is found and measured.
2. **Window and swap chain.** A Win32 window class and message loop, a flip-model DXGI swap chain on the window, resize of its buffers, per-monitor DPI awareness v2; the app entry point. Test: a hidden window gets a swap chain that presents.
3. **Shared host code.** Move what is not AppKit-specific out of `vesper-macos` (palette, geometry, quads, row slots, launch environment, find state, selection gesture) into `vesper-host`, which slice 4 started. Sprites move too, as geometry drawn by a pure Kotlin rasteriser both hosts share: the Windows half of T28. Starts once T16, T13.4 and T28 (PRs 36, 37, 39) are merged, as they change those files.
4. **Glyph atlas.** `vesper-host`, a common module, takes the atlas layout, the bitmap type and the cache logic from `vesper-macos` (none of them is changed by PRs 36, 37 or 39); `GpuAtlas` backs the cache with a D3D11 texture. DirectWrite rasterises glyphs as grayscale coverage and colour fonts' layers in their colours; `IDWriteFontFallback` maps what a face lacks, through a text source implemented in Kotlin. Tests on WARP.
5. **Renderer.** HLSL shaders compiled at start-up, instanced cell quads (backgrounds, glyphs, cursor, underlines, selection) drawn from the core's frame. Pixel tests into `OffscreenTarget` on WARP.
6. **Shell and input.** A `vesper-session` session on ConPTY; keys, text (`WM_CHAR`), mouse, wheel, paste and focus mapped to the core with the reference C# app's policy (eaeb20f `windows/`).
7. **Events.** Live resize, DPI changes, title, working directory and OSC 52 clipboard.
8. **Benchmark and close.** The T3 benchmark runs on Windows; `done.md`.
