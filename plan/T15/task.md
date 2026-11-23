---
status: not started
priority: P1
complexity: 5
readiness: 0%
---

# T15. macOS renderer

Status: not started

A Swift Metal renderer with a CoreText glyph atlas that has eviction, a shaped-run cache, ligatures, colour emoji, geometry-drawn box characters, and dirty-row uploads. Done when input latency and throughput are recorded against the T3 baseline.

Execution plan (split to fit the budget). All new code lives in `macos/Sources/ScullKit/Renderer/`; `TerminalView` and `TerminalSession` change only where the renderer plugs in, because T16 edits the same view.

- T15.1 Glyph atlas (`GlyphAtlas.swift`): one BGRA8 `MTLTexture` packed in shelves; CoreText rasterises a glyph of any font at the backing scale, mask glyphs as coverage and colour glyphs (emoji) as premultiplied colour, and only the new glyph's region is uploaded. Eviction drops the least recently used shelf that no frame still in flight has drawn; when nothing can be evicted the atlas doubles up to a cap and bumps its generation so the renderer rebuilds every row. Tests: packing, lookup reuse, eviction order, the in-flight guard, growth, colour detection.
- T15.2 Shaping (`RunShaper.swift`): a run's text shaped by CoreText with standard ligatures and system font fallback, so emoji come from the colour emoji font; every glyph is placed at the column of its cluster plus its offset inside the cluster, so fallback advances never drift off the core's grid. A bounded shaped-run cache keyed by text, face and width. Tests: a ligature font collapses `->`, emoji shape with a colour font, combining marks stay on their base cell, wide runs land two columns apart, the cache stays within its cap.
- T15.3 Sprites (`Sprites.swift`): box drawing (U+2500–U+257F), block elements and shades (U+2580–U+259F) and the underline kinds (single, double, curly, dotted, dashed) drawn from geometry into cell-sized atlas bitmaps, so lines meet across cells whatever the font. Tests: every code point in the ranges has a sprite, line arms reach the cell edges, shades have the right coverage.
- T15.4 Renderer (`MetalRenderer.swift`, `Shaders.swift`; the shader source is compiled at run time because SwiftPM does not build `.metal` files): instanced quads for backgrounds, decorations, glyphs and the cursor; per-row instance slots behind an indirection table, so a scroll from the frame's damage only remaps slots; dirty rows are rebuilt once and copied into each of the three in-flight buffers when that buffer is next used. `TerminalSession` accumulates the damage of every `tt_frame_update` (`FrameDamage.swift`) so no update between two draws is lost. `TerminalView` hosts a `CAMetalLayer`, and the debug snapshot renders offscreen through the same renderer. Tests: damage composition across updates and scrolls, an offscreen render of a fed frame checked at known pixels.
- T15.5 Image placements: textures cached by image and generation, drawn below or above the text by `z`.
- T15.6 Measurements: throughput (the T3 fixed 1,000,000-byte input fed through the real core, with frames updated and rendered offscreen) and input latency (key event to presented drawable through the shell's echo, `-ScullLatencyProbe`), written to `docs/benchmarks/macos-renderer.md` beside the T3 baseline.
- Verify: `./gradlew check`, the macOS app build, `swift test` in `macos/`; a `-ScullSnapshot` PNG showing text, colours, wide characters, emoji, box drawing and the cursor drawn by Metal.

Acceptance:
- [ ] The work described above is implemented in Kotlin/Native (platform UI stays Swift/C# where this task says so).
- [ ] Tests and the `./gradlew check` gate (plus the platform tests this task names) pass.
