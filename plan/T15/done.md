# T15. Done

- `vesper-macos/commonMain`, tested on every runner:
  - `Quads`: the 32-byte instance layout the shaders read (rect, atlas texel, RGBA, kind, image span).
  - `AtlasLayout`: a shelf packer with least-recently-drawn eviction, kept three frames clear of the
    GPU, and growth up to 8192 px.
  - `RowSources` / `RowSlots`: the damage of several frame updates composed into a row-to-previous-row
    map, so a scroll only reorders row slots.
- `vesper-macos/macosMain`:
  - `Raster`: cell metrics in pixels; glyph bitmaps from CoreText (coverage, or colour for emoji fonts).
  - `Sprites`: box drawing U+2500–U+257F (lines, dashes, arcs, diagonals), blocks, shades and
    quadrants U+2580–U+259F, Powerline U+E0B0–U+E0BF and the five underline kinds, all drawn from
    cell geometry.
  - `GlyphAtlas`: a BGRA texture over the packer.
  - `RunShaper`: CoreText runs (ligatures, font fallback) with a font registry and a two-generation
    cache. Each glyph is pinned to its grapheme cluster's column, so wide characters and fallback
    fonts never drift.
  - `MetalRenderer`: MSL compiled from source at start; one instanced quad per background run,
    glyph, sprite and decoration; rows built only when damaged and kept in slots; three in-flight
    buffers that copy a slot only when it is newer; image placements by `z` (negative under the
    text); the cursor as a block with the glyph redrawn in the background colour, or an outline when
    unfocused; offscreen rendering for tests and benchmarks.
  - `TerminalView` is backed by the renderer's `CAMetalLayer`. AppKit never asks a Metal layer to
    display, so the view schedules its own draws, at most one per main-queue turn. Without a Metal
    device it keeps the CoreText drawing.
  - `vesper --bench-renderer` and `vesper --latency` (a key probe through `cat`) in the release app.
- The T3 input and the `fixed` number formatter moved from `vesper-bench` to `vesper-harness`, so the
  renderer bench feeds the same bytes without a copy.
- Results: `docs/benchmarks/macos-renderer.md`.
  - 64 KiB reads: 159.80 MiB/s with frame updates, 73.36 MiB/s drawing a frame after every read
    (T3 stub: 273.19).
  - About 0.34 ms per frame for one changed cell or a scroll.
  - Key to GPU-finished frame: 1.19 ms median, 2.45 ms p95.
  - Key to on-screen frame: not measured. The screen was locked during every run, so no frame was
    presented. `vesper --latency` fills that row on an unlocked display.

Commits on branch `t15-macos-renderer` (stacked on T14's `t14-macos-first-light`).

Checked:
- `./gradlew ktlintFormat check` on macOS arm64: green.
- Linux x64 tests of all 12 modules in Colima (`ubuntu:24.04`): green.
- `MetalRendererTest` renders fed `Terminal` frames offscreen on the GPU and checks known pixels:
  - truecolour backgrounds and a full block edge to edge;
  - the focused block cursor and the unfocused outline;
  - a scroll that reuses built rows and repaints the new one;
  - a kitty image covering its cells.
- The snapshot `vesper-macos/build/reports/first-light/macos-renderer.png` shows bold, italic, dim,
  ANSI and truecolour text, inverse, single, curly and strike lines, CJK wide characters, colour
  emoji, box drawing with arcs, blocks, shades and the cursor.
- `FirstLightTest` now saves the Metal rendering of the typed `echo vesper; ls /`.
