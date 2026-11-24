---
status: in progress
priority: P3
complexity: 2
readiness: 10%
agent: Claude Code / claude-opus-5-5
---

# T28. Powerline separators from cell geometry

The Powerline separators of shell prompts come from the bundled symbols font (T27), whose glyphs do not fill the cell exactly, so thin seams show where prompt segments meet. Done when U+E0B0–U+E0BF are drawn from the cell's own geometry on both hosts, like the box sprites, and their edges reach the cell edges.

Check: sprite tests that every code point in the range has a sprite and its edges reach the cell edges.

Acceptance:
- [ ] The work described above is implemented in Kotlin/Native.
- [ ] Tests and the `./gradlew check` gate (plus the host tests this task names) pass.

## Execution plan

Scope agreed with the creator: macOS now; the Windows host does not exist yet, so its half belongs to T17, whose renderer draws U+E0B0–U+E0BF from geometry like its box sprites.

The Metal renderer already draws U+E0B0–U+E0BF as sprites (T15, `Sprites.kt`). Left to do, stacked on T13.4's `t13.4-macos-selection` (it rewrote the CoreText `drawRect` this changes):

1. `Sprites.kt`: `drawSprite` paints a sprite straight into a context at a cell, in the context's fill and stroke colours; shades use the context alpha, so the atlas and the direct path share one painter.
2. `TerminalView`'s CoreText fallback draws sprite cells (Powerline separators and box drawing) from geometry and leaves them out of the text runs; the cursor cell too. Its drawing moves into `drawCells(context)` so a test can draw it into a bitmap.
3. Tests: every code point in U+E0B0–U+E0BF is a sprite with ink on all four cell edges, and the solid shapes' flat sides are fully covered; a CoreText-path test where a separator's edge meets the next segment's background without a seam.

Verify: `./gradlew ktlintFormat check linkDebugTestLinuxX64` on macOS, the Linux tests in Colima.
