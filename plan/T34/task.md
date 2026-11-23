---
status: in progress
priority: P1
complexity: 3
readiness: 10%
agent: Claude Code / claude-opus-5-5
---

# T34. Decode PNG through a shared, allocation-capped decoder

PNG decode for iTerm2 images and kitty `f=100` goes through one shared decoder: read the header first, compute the RGBA length from the size before allocating a pixel buffer, then decode with a hard allocation cap. Errors map to `ImageError.Decode`. Test fixtures can encode through the same helper; grey and palette PNGs stay covered.

Acceptance:
- [ ] Header-first, size-capped PNG decode lives in a shared module used by `scull-image`.
- [ ] Tests cover cap failures, grey and palette fixtures, and sRGB output.
- [ ] `./gradlew check` passes.

## Findings

- **zlib comes with Kotlin/Native.** The `platform.zlib` klib exists for all three targets.
  - macOS and Linux link the system `libz` (`-lz`).
  - `mingwX64` links it statically (`-Wl,-Bstatic -lz`).
  - Checked in the 2.4.21 distribution's `klib/platform/*/org.jetbrains.kotlin.native.platform.zlib/default/manifest`.
  - So inflate and CRC-32 need no new dependency and no native code of ours.
- **No ready PNG decoder fits.** Checked 2026-11-23:
  - **ImageKodec** (formerly KiteImage; https://github.com/yuroyami/KiteImage) is pure Kotlin and builds for our targets. Against it:
    - It is a few months old, with 0 stars.
    - Its Maven coordinates changed three times (`kiteimage`, then `kiteimagecodec`, then `imagekodec`).
    - Its pixel limit is fixed (2^28) rather than set by the caller.
  - **korlibs-image** brings the KorGE I/O and coroutine stack for one codec.
  - **libspng** (https://github.com/randy408/libspng, 0.7.4) has the right limit API (`spng_set_image_limits`). But it is C: it would need cross-built static libraries for three targets, and the creator's rule is Zig before C for native code.
  - A Kotlin decoder over `platform.zlib` is a few hundred lines. It allocates only what the cap allows.
- **`scull-image` does not exist yet;** T19 creates it. This task adds the shared module `scull-png`. `ImageError.Decode` is T19's, which will map `PngException` to it.
- **"sRGB output" means 8-bit sRGB RGBA, straight alpha.**
  - Samples are taken as sRGB and the colour chunks (`gAMA`, `cHRM`, `sRGB`, `iCCP`) are ignored, as terminals do.
  - 16-bit samples are scaled with rounding.
  - Grey and palette images are expanded, and `tRNS` becomes alpha.

## Execution plan

1. **Module.** A new `scull-png` module (`scull.library`, `nativeMain`) that depends on nothing in the repository.
2. **`PngHeader`.** `Png.header(bytes)` checks the signature and IHDR (size, depth and colour type, compression, filter, interlace), and gives `rgbaBytes` without allocating.
3. **`Png.decode(bytes, maxBytes)`.**
   - **Cap check.** `PngTooLargeException` is thrown before anything is allocated when the RGBA buffer plus two scanlines would pass `maxBytes`.
   - **Chunks.** CRCs are checked on the chunks read; PLTE and tRNS are handled; other ancillary chunks are skipped.
   - **Inflate.** IDAT is inflated straight into the row buffers, never into the whole raw image. Inflate stops at the expected size, so a zlib bomb costs no memory.
   - **Pixels.** Filters 0–4, Adam7, every colour type at every legal depth.
4. **`Png.encode(width, height, rgba)`.** RGBA8 with filter 0, for test fixtures (here and in T19).
5. **Tests.**
   - Grey 1/2/4/8/16, grey plus alpha, RGB 8/16 with tRNS, palette 1/2/4/8 with tRNS, RGBA.
   - Every filter, and Adam7 against the same image not interlaced.
   - An image from an independent encoder (macOS `sips`), embedded as hex.
   - Cap failures; malformed input (signature, CRC, truncated and short data, bad IHDR, a missing PLTE); the encode and decode round trip.
6. **Verify.** `./gradlew ktlintFormat check`, then Linux in Colima. Update `toolchain.md` (`platform.zlib`) and `PLAN.md`.
