---
status: in progress
priority: P1
complexity: 5
readiness: 0%
agent: Claude Code / claude-opus-5-5
---

# T15. macOS renderer

A Metal renderer in Kotlin/Native for the macOS app (T14), through the `platform.Metal`, `platform.QuartzCore` and `platform.CoreText` libraries: a CoreText glyph atlas with eviction, a shaped-run cache, ligatures, colour emoji, box drawing, block elements and underline kinds drawn from cell geometry, image placements by `z`, and dirty-row uploads from the frame's damage. Done when input latency and throughput are measured against the T3 baseline and written to `docs/benchmarks/macos-renderer.md`.

The removed Swift renderer in `macos/` at eaeb20f (`GlyphAtlas`, `RunShaper`, `Sprites`, `MetalRenderer`) is the reference design. The execution plan is written at claim time and split into slices of at most 500 lines.

Check: `./gradlew check`; an offscreen render of a fed frame checked at known pixels; a snapshot showing text, colours, wide characters, emoji, box drawing and the cursor.

Execution plan:

1. Pure parts in `vesper-macos/commonMain`, tested on every runner: the quad layout the shaders
   read (32 bytes: rect, atlas texel, RGBA, kind, image span) in a growable `IntArray`; the shelf
   packer with least-recently-drawn eviction and growth; damage composed across several frame
   updates into a row-to-previous-row map, so a scroll only reorders row slots.
2. CoreText and CoreGraphics in `macosMain`: glyph bitmaps (coverage, or colour for emoji fonts);
   sprites drawn from cell geometry (box drawing U+2500–U+257F, blocks U+2580–U+259F, Powerline
   U+E0B0–U+E0BF, the five underline kinds); the atlas texture over the packer; the run shaper
   with a font registry and a two-generation cache, pinning each glyph to its cluster's column so
   wide characters and fallback fonts never drift.
3. `MetalRenderer`: shaders compiled from source, rows built only when damaged and kept in slots,
   three in-flight buffers copying a slot only when it is newer, images by `z`, the cursor (block
   with the glyph redrawn in the background colour, or an outline when unfocused). The view hosts
   a `CAMetalLayer`; without a Metal device (a VM, a CI runner) it keeps T14's CoreText drawing.
   Offscreen render tests check known pixels and save a snapshot PNG.
4. Measurements: throughput on the T3 input (feed, update, render offscreen) and key-to-frame
   latency from a probe in the release app; results in `docs/benchmarks/macos-renderer.md`.

Acceptance:
- [ ] The work described above is implemented in Kotlin/Native.
- [ ] Tests and the `./gradlew check` gate (plus the host tests this task names) pass.
