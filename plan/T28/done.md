# T28. Done

Scope, agreed with the creator: macOS now. The Windows host does not exist yet, so its half went
into T17's task: its renderer draws these code points from geometry too, and the sprite code both
hosts then share moves into a common module.

- The Metal renderer already drew U+E0B0–U+E0BF as sprites from the cell's geometry (T15,
  `Sprites.kt`); this task proves it with tests.
- `Sprites.kt`:
  - `drawSprite` paints a sprite straight into a context over a cell, in the context's fill and
    stroke colours, so the atlas and the CoreText fallback share one painter.
  - Shades go through the context alpha instead of a grey fill, so they keep the colour drawn in.
- `TerminalView`'s CoreText fallback (Macs without a GPU):
  - Cells holding box drawing, block elements or Powerline separators are drawn from geometry and
    left out of the text runs as spaces, which keep their columns. The cursor cell is handled the
    same way.
  - The drawing moved from `drawRect` into `drawCells(context)`, so a test can draw it into a bitmap.
- `plan/T17/task.md`: the Windows half.

Commits on branch `t28-macos-powerline`, stacked on T13.4's `t13.4-macos-selection` (it rewrote
the `drawRect` this changes): the claim, the fallback with its tests, the close.

Checked:
- `./gradlew ktlintFormat check linkDebugTestLinuxX64` on macOS arm64: green.
- Linux x64 tests of all 12 modules in Colima (`ubuntu:24.04`, `VESPER_TESTS_DIR` set): green. In the
  first run `vesper-term`'s binary, which this task does not touch, crashed once with signal 11 under
  x86 emulation; three reruns passed all 186 tests.
- Tests:
  - `SpritesTest.powerlineSeparatorsReachTheCellEdges`, run on the atlas sprites at 2×:
    - every code point in U+E0B0–U+E0BF is a sprite with ink on all four cell edges;
    - each solid shape's flat sides are fully covered along their length, except the ends a slanted
      side cuts.
  - `CoreTextFallbackTest`: a script prints a blue segment, a solid separator and a grey segment,
    and the view's CoreText drawing goes into a bitmap.
    - The segment's last pixel and the separator's first are the same blue down the row.
    - The separator's far corners show the next segment's grey.
    - The snapshot is `macos-coretext-powerline.png`.
- Not checked (needs a person): the fallback on a Mac without a GPU.
