# T19.4. Done

## What changed

- `vesper-image`:
  - `ImageStore.move(id, column, line)` re-anchors a placement and keeps its id and order, so
    kitty's id maps stay valid across a reflow.
  - `MAX_CELL_PIXELS` is public: the terminal checks a reported cell size against it.
- `vesper-term` (new `Images.kt`, with hooks in `State`, `OscDispatch`, `Edit`, `Screen`, `Resize`
  and `Terminal`):
  - DCS `q` goes to `SixelDecoder`, APC to `KittyGraphics`, OSC 1337 to `ITerm2Images`. Kitty
    replies go out through `takeReplies()`.
  - Each screen has its own `ScreenImages`: an `ImageStore` with a 320 MB quota and its
    `KittyGraphics`. The two swap with the grids.
  - Placements are anchored to absolute lines and pruned once they leave the ring. A tall image
    stays while any of its lines is kept.
  - ED 2, ED 3, the alternate screen and RIS clear as `task.md` → Decisions says.
  - The cursor moves after an image as WezTerm does: under sixel's left edge, right of iTerm2's and
    kitty's images unless kitty's `C=1` keeps it.
  - Sixel and auto-sized iTerm2 images are padded to whole cells. A new one drops older ones of
    its kind that it covers whole.
  - Placements ride a reflow as `TrackPoint`s; one whose cell is cut goes.
  - `Terminal.setCellSize(width, height)` and `Terminal.images`.
- Frame:
  - `FramePlacement` and `Frame.placements`: the viewport's placements, lowest `z` first.
  - The rows under a placement that appeared, moved or went are added to `damage.dirty`
    (`Damage.addDirty`).
- C ABI 0.4 (`scull.h`, `FrameExports`, `TermExports`):
  - `tt_image`, `tt_placement`, and `placements` / `placements_len` appended to `tt_frame_view`,
    in the layout the Rust core had and the macOS and Windows hosts already read.
  - `tt_image_pixels` reads pixels that the frame keeps pinned while an image is listed, so
    nothing is copied.
  - `generation` comes from a per-frame counter, so a texture keyed on `(image, generation)` is
    never stale, even after main and alternate stores reuse an id.
  - `tt_term_resize` passes `width_px / cols` by `height_px / rows` as the cell size; 0 keeps the
    old one.

Reused rather than written: the decoders and `ImageStore` from T19.1–T19.3, `TrackPoint` reflow
tracking from selections, `Grid.firstLine` and `screenTopLine`, `Png.encode` and `Base64` in the
tests, and the frame's `Grow` buffers in the FFI.

## Not done here

- No new native host program. Native code goes in Zig first, and the Zig host harness
  (T901, PR #23) is not in this branch's base. An `images.zig` host test should follow once both
  are in `main`.
- The C# binding regeneration stays with T17, as `task.md` → Findings says.

## Commits

- `876151e` T19.4: move placements in the image store and share the cell-size cap
- `566f2e3` T19.4: images in the terminal and the frame
- `1aafdc1` T19.4: image placements in the C ABI

## How it was checked

- `./gradlew ktlintFormat`, then `./gradlew check` on macOS arm64: passes, including the C
  programs (`status`, `two_threads`, `events`) built against the new header under ASan+UBSan and
  TSan.
- `nm -gU` on the debug dylib lists `_tt_image_pixels`.
- In Colima (`ubuntu:24.04`, linux/amd64), the linuxX64 test binaries pass: vesper-image 51,
  vesper-term 165, scull-ffi 48.
- New tests:
  - `ImagesTest`, 20 cases: each protocol end to end, cursor moves, scrolling and eviction,
    ED 2 / ED 3, the alternate screen, reflow, RIS, the cell size, kitty replies, and frame
    placements with their dirty rows;
  - `ImageExportsTest`, 4 cases: placement fields and in-place pixels, generation stability and
    replacement, `NULL` handling, and the cell size from `tt_term_resize`;
  - `ImageStoreTest.aMovedPlacementKeepsItsIdItsOrderAndTheRest`.
