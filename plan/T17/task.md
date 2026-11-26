---
status: in progress
priority: P1
complexity: 5
readiness: 90%
agent: Claude Code / claude-opus-5-5
---

# T17. Windows app and renderer

A Kotlin/Native Windows app (`vesper-windows`, `mingwX64`) over Win32: a window with a DXGI swap chain drawn by a Direct3D 11 renderer with a DirectWrite glyph atlas, the user's shell through `vesper-pty` (ConPTY), keys, text, mouse, wheel, paste and focus mapped to the core, live resize, DPI changes, and the core's title, directory and OSC 52 events. The core is called directly: no C ABI. Done when a shell is usable and the T3 benchmark runs on Windows.

First step, a spike: Win32 and Direct3D 11 have C interfaces that Kotlin/Native cinterop can bind (`windows.h`, `d3d11.h` and `dxgi1_2.h` with `COBJMACROS`), but `dwrite.h` is C++ only. Pick how DirectWrite is reached (a small Zig shim exposing a C API is the first choice, C++ only if Zig cannot) and record it in `research.md` and `toolchain.md`.

Box drawing, block elements and the Powerline separators U+E0B0–U+E0BF are drawn from the cell's own geometry, as the macOS sprites are (`vesper-macos` `Sprites.kt`; T28), so prompt segments meet without seams; the code that both hosts then share moves into a common module.

The removed C# app in `windows/` at eaeb20f (its renderer, app shell, input mapping and event policy) is the reference design. The execution plan is written at claim time and split into slices of at most 500 lines.

Check: `./gradlew check` on the Windows runner, renderer tests on WARP into an offscreen texture; how the app looks on a desktop needs a human.

Acceptance:
- [ ] The work described above is implemented in Kotlin/Native.
- [ ] Tests and the `./gradlew check` gate (plus the host tests this task names) pass.

## Execution plan

Slices of at most 500 lines each, one PR each, every one green on the Windows runner (`./gradlew check`, which runs the `mingwX64` tests). No machine here runs Windows code, so each slice's tests are what proves it.

1. **Spike and bindings.** The `vesper-windows` module; `directx.def` binds Direct3D 11, DXGI and DirectWrite (decision in `research.md` §6.2: DirectWrite headers from Zig, no shim). `Gpu` (a hardware or WARP device), `OffscreenTarget` (render and read back), `FontSystem` and `FontFace`. Tests: WARP clears an offscreen texture that reads back exactly; Consolas is found and measured.
2. **Window and swap chain.** A Win32 window class and message loop, a flip-model DXGI swap chain on the window, resize of its buffers, per-monitor DPI awareness v2; the app entry point. Test: a hidden window gets a swap chain that presents.
3. **Shared host code.** Move what is still AppKit-bound but host-neutral out of `vesper-macos` (launch environment, find state, selection gesture, image placement) into `vesper-host`, which slices 4 and 5 started. Sprites move too, as geometry drawn by a pure Kotlin rasteriser both hosts share: the Windows half of T28. Starts once T16, T13.4 and T28 (PRs 36, 37, 39) are merged, as they change those files.
4. **Glyph atlas.** `vesper-host`, a common module, takes the atlas layout, the bitmap type and the cache logic from `vesper-macos` (none of them is changed by PRs 36, 37 or 39); `GpuAtlas` backs the cache with a D3D11 texture. DirectWrite rasterises glyphs as grayscale coverage and colour fonts' layers in their colours; `IDWriteFontFallback` maps what a face lacks, through a text source implemented in Kotlin. Tests on WARP.
5. **Renderer.** What turns a frame into quads moves to `vesper-host` so both renderers share it: the palette, `Quads`, row slots, cell geometry and metrics, grapheme clusters and the shaping cache, and a `QuadBuilder` (row backgrounds, glyph runs, strike and overline, the cursor) over a `GlyphSource` each host implements. `MetalRenderer` keeps only Metal. `Palette` is changed by PR 37, and the builder takes over code PRs 36 and 39 touch, so whichever lands second carries their changes into the moved code. On Windows: a DirectWrite `GlyphSource` (glyphs per cluster, `IDWriteFontFallback` for what the face lacks) and `D3DRenderer`, HLSL compiled at start-up with `D3DCompile`, one per-instance vertex buffer of row slots drawn with `DrawInstanced`. Underlines and the other sprites wait for slice 3's shared rasteriser. Tests: the builder on every target with a fake glyph source; on WARP, backgrounds, glyph ink, the cursor block and outline, and a scroll into an `OffscreenTarget`.
6. **Shell and input.** A `vesper-session` session on ConPTY; keys, text (`WM_CHAR`), mouse, wheel, paste and focus mapped to the core with the reference C# app's policy (eaeb20f `windows/`). `vesper-input` names a Windows key from its virtual key, scan code and extended bit (`windowsKey`, the reverse of the win32-input-mode table, so both read one table). The pure policy is common code in `vesper-windows`, tested on every runner: held modifiers, the paste shortcuts, pairing `WM_KEYDOWN` with the `WM_CHAR` that follows (AltGr, dead keys, surrogates), swallowed releases, wheel notches to steps and to history rows, and pointer buttons with the releases a lost capture owes. `App` spawns the shell, renders the frame with `D3DRenderer` into the swap chain, and is woken by a posted message from the PTY thread; `Window` hands it the input messages, and Alt+F4 still closes. The launch environment for slice 3. Tests: the policy on every target; on Windows, `cmd.exe` typed into through window messages echoes back on the screen.
7. **Events.** Live resize, DPI changes, title, working directory and OSC 52 clipboard. Live resize follows macOS: once a border drag starts (`WM_SIZING` inside `WM_ENTERSIZEMOVE`), output pauses and only the swap chain follows the window; `WM_EXITSIZEMOVE` resizes the session once and resumes. A plain move never pauses. `WM_DPICHANGED` keeps the new DPI in `App`, and the `WM_SIZE` that follows refits the grid and the cell pixels the session reports. The events: the title (not an icon name) becomes the window's text, "Vesper" when empty; an OSC 7 `file://` URI for this computer (no host, `localhost` or its own name) with a drive becomes `App.directory` as a Windows path; an OSC 52 write that is UTF-8 goes on the clipboard as `CF_UNICODETEXT`; the bell is `MessageBeep`. The title and clipboard decoding macOS does inline moves to `vesper-host`, so both hosts share it. Tests: the URI-to-path policy and the live-resize state on every target; the shared decoding in `vesper-host`; on Windows, a clipboard round trip, `cmd`'s `title` reaching the window text through ConPTY, and the session keeping its size through a border drag and taking the final one after.
8. **Benchmark and close.** The T3 benchmark runs on Windows. The reference terminals are looked up as `<name>.exe` there, since Windows names its programs with an extension, so an installed WezTerm or Alacritty is no longer reported as missing. The Windows CI job builds the release bench after `check` and runs the baseline and terminal tables, writing them to the job summary rather than to `docs/`: a CI VM's numbers are only that run's. `done.md` waits for slice 3, which waits for PRs 36, 37 and 39.
