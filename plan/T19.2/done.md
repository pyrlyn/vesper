# T19.2. Done

Branch `t19.2-sixel`, stacked on T19.1 (`t19.1-images`); the PR is linked from the branch.

## What was done

- **`SixelDecoder`** in `scull-image`, fed the DCS payload in pieces through `put(bytes, start, end)`. `finish()` gives an `RgbaImage`.
- **Protocol.** The VT340 colour map; HLS and RGB definitions with the components clamped; registers 0–255; repeat, where a count of 0 draws once; `$` and `-`; raster attributes before the first sixel; background select. Bytes outside the commands are ignored.
- **Pixels.** Each keeps the colour it was drawn in. Unset pixels are transparent for P2 = 1; otherwise they take the background colour the caller passes.
- **Caps.** The width, height and pixel-byte caps are checked before the buffer grows. Past a cap the rest is ignored and `finish` throws `TooLarge`. Repeat counts saturate rather than overflow.
- **Fuzz.** `fuzzSixel` checks that a stream cut into pieces decodes exactly as the whole one does.
- **Reasons** for a custom decoder and the decisions (aspect ratio not applied, background from the caller) are in `task.md`.

## How it was checked

- **`SixelTest`, 9 tests.**
  - Bit order and image height, repeat, `$` and `-`.
  - The VT340 map, HLS hues (blue at 0, red at 120, green at 240), grey, clamping, a register past 255, redefinition after drawing.
  - Raster size and background fill, raster attributes after a sixel ignored, ignored bytes.
  - Each cap, an empty image, a command at the very end of the stream.
  - 3500 random streams through `fuzzSixel`.
- **macOS.** `./gradlew ktlintFormat check` passes.
- **Linux.** The `scull-image` tests (27 in all) and the `scull-png` tests (13) pass in Colima on `ubuntu:24.04`.
