---
status: todo
priority: P1
complexity: 5
readiness: 0%
agent:
---

# T15. macOS renderer

A Metal renderer in Kotlin/Native for the macOS app (T14), through the `platform.Metal`, `platform.QuartzCore` and `platform.CoreText` libraries: a CoreText glyph atlas with eviction, a shaped-run cache, ligatures, colour emoji, box drawing, block elements and underline kinds drawn from cell geometry, image placements by `z`, and dirty-row uploads from the frame's damage. Done when input latency and throughput are measured against the T3 baseline and written to `docs/benchmarks/macos-renderer.md`.

The removed Swift renderer in `macos/` at eaeb20f (`GlyphAtlas`, `RunShaper`, `Sprites`, `MetalRenderer`) is the reference design. The execution plan is written at claim time and split into slices of at most 500 lines.

Check: `./gradlew check`; an offscreen render of a fed frame checked at known pixels; a snapshot showing text, colours, wide characters, emoji, box drawing and the cursor.

Acceptance:
- [ ] The work described above is implemented in Kotlin/Native.
- [ ] Tests and the `./gradlew check` gate (plus the host tests this task names) pass.
