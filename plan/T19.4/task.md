---
status: in progress
priority: P1
complexity: 4
readiness: 100%
agent: Claude Code / claude-opus-5-5
---

# T19.4. Images in the terminal and the frame

Part of T19, after T19.1–T19.3.

- **`vesper-term`.** Wire `vesper-image` in:
  - route DCS sixel, APC `G` and OSC 1337 to the decoders;
  - place images at the cursor and move the cursor past them;
  - re-anchor placements on scroll and reflow;
  - clear them with the alternate screen and erase.
- **Frame.** Carry the visible placements, so the platform uploads textures by image id.
- **C ABI.** Exports for placements and pixels, an ABI minor bump and the bindings.

Done when golden tests cover each protocol end to end and `./gradlew check` passes, with the C ABI sanitizer test.

## Findings

- The Rust core this module replaced already did this work. Its `images.rs` of its `term` crate, its tests and `frame.rs` of its `ffi` crate are in this repository's history (`git show 677ad4c^:crates/vesper-term/src/images.rs`, checked 2026-11-23). This task ports that design, not its code.
- The hosts already read the old layout:
  - `windows/Vesper.Core/FrameView.cs` reads `tt_frame_view.placements`, `tt_placement` and `tt_image_pixels(image, &w, &h, &stride)`;
  - `macos/Sources/VesperKit/Renderer/ImageLayer.swift` keys textures on `(image, generation)`.
- The Kotlin header has none of it yet, and no C# binding is generated from it. `Vesper.Core.csproj` still links the deleted Rust `NativeMethods.g.cs`, so wiring the C# binding generator stays with T17.
- kitty keeps a separate image store per screen, each with a quota of 320 MB. Source: https://sw.kovidgoyal.net/kitty/graphics-protocol/, "Image persistence and storage quotas" (checked 2026-11-23).

## Decisions

- **One `ScreenImages` per screen.**
  - Each holds an `ImageStore` and its `KittyGraphics`, and swaps with the grid when the screens swap.
  - The terminal keeps one `ITerm2Images`, one `SixelDecoder` while a DCS `q` lasts, and an APC buffer under the parser's `MAX_APC_BYTES`.
- **Anchors are absolute lines** (`Grid.evicted` plus the ring index), the numbering selections already use.
  - Scrolling the whole screen moves nothing, and a placement whose last line leaves the ring is dropped.
  - A partial region scroll (DECSTBM, IL, DL) leaves images where they are, as the Rust core did.
- **Cursor after an image**, as WezTerm reads the protocols (behaviour only):
  - sixel ends under the image's left edge;
  - iTerm2 and kitty end right of its bottom-right cell, unless they ask the cursor to stay.
  - Moving down past the bottom margin scrolls, through IND.
- **Sixel and auto-sized iTerm2 images** are padded with transparent pixels to whole cells, so a host that scales an image to its cells shows it at its own size.
  - A new sixel or iTerm2 image drops older ones of the same kind that it covers whole, as foot does, so an animation drawn in place does not pile up.
- **Clearing.**
  - ED 2 drops the placements that touch the screen; ED 3 drops those left wholly in the cleared history.
  - Leaving the alternate screen drops its images, as foot does, and so does entering it with a clear (`?1049`).
  - RIS drops both screens' images.
- **Resize.** Placement anchors ride the grid's reflow as `TrackPoint`s, and a placement whose cell is lost goes.
- **Cell size.**
  - Assumed to be 8 x 16 pixels until the host reports one.
  - `tt_term_resize` gives it as the view's pixels over the cells, when it is known.
- **Sixel background.** With `P2 != 1`, unpainted pixels are opaque black, because the core does not know the host's colours.
- **C ABI.** Same layout as the Rust core's:
  - `tt_placement`, plus `placements` and `placements_len` appended to `tt_frame_view`;
  - `tt_image_pixels`, with the pixels valid until the next update or free of the frame. The frame pins each listed image's pixels, so nothing is copied.
  - `generation` is unique per frame, so a reused pointer never hits a stale texture.
  - ABI minor 3 to 4.

## Execution plan

1. `vesper-image`: `ImageStore.move(id, column, line)`, which re-anchors a placement and keeps its id, so kitty's id maps stay valid. Make `MAX_CELL_PIXELS` public.
2. `vesper-term`:
   - `api(projects.vesperImage)`;
   - `Images.kt`: `ScreenImages`, the DCS/APC/OSC 1337 handlers, placing and cursor moves, pruning, the ED 2 clear and the resize anchors;
   - hooks in `State`, `OscDispatch`, `Screen` (swap, alternate screen, RIS), `Edit` (ED 2) and `Resize`;
   - `Terminal.setCellSize` and `Terminal.images`.
3. Frame:
   - `FramePlacement` and `Frame.placements`, the visible placements in viewport rows, lowest `z` first;
   - rows under a changed placement go into `dirty`.
4. C ABI:
   - `tt_placement`, `tt_image`, `tt_image_pixels` and the frame view fields in `vesper.h`, ABI 0.4;
   - `FrameExports` fills them and pins the pixels;
   - `tt_term_resize` passes the cell size.
5. Tests:
   - `ImagesTest` in `vesper-term`: a golden run per protocol, cursor moves, scroll and eviction, ED 2 and ED 3, the alternate screen, reflow, RIS, the kitty replies and frame placements;
   - an FFI test in Kotlin;
   - no new host program: native code is written in Zig first, and the Zig host harness (T901, PR #23) is not on this branch's base yet. The existing C programs still build against the new header under both sanitizers, and the export of `tt_image_pixels` is checked with `nm`. An `images.zig` host test follows once both are in `main`.
6. Verify: `./gradlew ktlintFormat`, then `./gradlew check` on macOS, and the vesper-term, vesper-image and vesper-ffi tests on Linux in Colima.
