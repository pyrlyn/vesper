# T34. Done

Branch `t34-png`; the PR is linked from the branch.

## What was done

- **New module `scull-png`.** Pure Kotlin over `platform.zlib`, which ships with Kotlin/Native, so it adds no dependency and no native code of ours. The reasons for a custom decoder are in `task.md` → Findings.
- **`Png.header(bytes)`** checks the signature and IHDR and returns `PngHeader` with `rgbaBytes`. It allocates nothing.
- **`Png.decode(bytes, maxBytes)`** decodes to 8-bit sRGB RGBA with straight alpha.
  - **Cap first.** It throws `PngTooLargeException(needed, limit)` before allocating when the RGBA buffer plus two scanlines pass the cap, or when they do not fit in a Kotlin array.
  - **Streamed inflate.** IDAT is inflated one scanline at a time into two row buffers, and inflating stops at the image's size, so a zlib bomb costs nothing.
  - **Coverage.** Every colour type at every legal depth, filters 0–4 and Adam7.
  - **Samples.** 16-bit samples are rounded to 8 bits and low depths are stretched; `tRNS` becomes alpha.
  - **Checks.** CRCs are checked on IHDR, PLTE, tRNS and IDAT. An unknown critical chunk is an error; other ancillary chunks are skipped.
- **`Png.encode(width, height, rgba)`** writes unfiltered RGBA8, for test fixtures here and in T19.
- **Errors.** Everything else malformed throws `PngException`; T19 maps it to `ImageError.Decode`.
- **Docs.** `PLAN.md` §3 lists the module and marks T34 done. `toolchain.md` gains a row for `platform.zlib`.

## How it was checked

- **`scull-png/src/nativeTest/kotlin/scull/png/PngTest.kt`, 13 tests:**
  - **Fixture builder.** Most fixtures come from an in-test builder with its own forward filters and Adam7 split, independent of the decoder's.
  - **Independent encoder.** An image from macOS `sips` (ImageIO), embedded as hex, with Sub and Paeth filters plus sRGB, eXIf and pHYs chunks.
  - **Colours and depths.** Grey 1/2/4/8/16, grey with alpha 8/16, RGB 8/16 and RGBA 8/16, palette 1/2/4/8. Includes the tRNS key (compared at 16 bits) and palette alpha.
  - **Filters and Adam7.** Each filter, a mix of filters, and Adam7 against the plain image at sizes that leave passes empty.
  - **Cap failures.**
    - Exact limit and limit minus one.
    - An IHDR of 2^31−1 squared, refused with no cap.
    - A 64 MiB zero bomb behind a one-pixel image.
  - **Malformed input.** Signature, CRCs, truncation, short and corrupt data, a bad IHDR, a missing or misplaced PLTE, an index past the palette, an unknown critical chunk, a bad filter type, and a bad tRNS.
  - **Accepted input.** IDAT split across chunks with an empty one, and ancillary chunks skipped.
- **macOS.** `./gradlew ktlintFormat check` passes, and the scull-png tests (13) pass.
- **Linux.** The scull-png tests (13) pass in Colima on `ubuntu:24.04` (`linuxX64`).
- **Windows.** `mingwX64` compiles. Its tests do not run on a macOS host.
