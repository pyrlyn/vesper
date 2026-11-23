# T19.1. Done

Branch `t19.1-images`, stacked on T34 (`t34-png`); the PR is linked from the branch.

## What was done

- **New module `scull-image`** (`commonMain`, `api(projects.scullPng)`), so `scull-term` can use it in T19.4.
- **`RgbaImage` and `ImageError`.**
  - `RgbaImage`: RGBA8, sizes checked.
  - `ImageError` has four kinds: `Malformed`, `Unsupported`, `Decode` and `TooLarge`.
  - `decodeFile` sniffs the format; only PNG is decoded. `decodePng` maps `PngException` to `Decode` and `PngTooLargeException` to `TooLarge`. Kitty `f=100` in T19.3 reuses both.
- **`ImageStore(quotaBytes, maxImages)`.**
  - It holds `Image`s and keeps `Placement`s apart from them: column and absolute line, a size in cells, an optional pixel crop and a z-index.
  - Image ids are never reused, so there is no generation counter.
  - LRU eviction takes unshown images first, then shown ones with their placements. An image larger than the quota is refused and evicts nothing.
- **`ITerm2Images(maxFileBytes, maxPixelBytes)`.**
  - It reads OSC 1337 `File=` and the multipart form (`MultipartFile=`, `FilePart=`, `FileEnd`) with every documented argument.
  - Downloads (`inline` other than 1) give no image.
  - The declared `size`, the base64 length and the PNG header are each checked against the caps before anything that large is allocated.
- **Fuzz entries.** `fuzzInlineImages` and `fuzzImageStore` are in main code, as the parser has. The store's bookkeeping is rechecked after every operation.
- **Fixes made on the T34 branch while doing this.** `scull-png` no longer calls zlib's `crc32` and `deflateBound`: their `uLong` differs in width between targets, and that broke the shared metadata that `scull-image` compiles against.
- **`PLAN.md`** lists `scull-image` and T19.1–T19.4.

## How it was checked

- **`scull-image` tests, 18 in all.**
  - **Store.** LRU order, shown images kept over unshown ones, eviction with placements, an oversized image refused, the image-count cap, place and unplace, crop bounds, ids never reused, and 2000 random operation lists.
  - **iTerm2.** Every argument and extent, the defaults, downloads and other OSCs ignored, base64 with and without padding, malformed input, JPEG and GIF refused as unsupported, each cap, multipart cut off quad boundaries, stray and interrupted parts, and an oversized transfer dropped.
  - **Fuzz.** 3500 mutated and random inputs through the fuzz entry.
- **macOS.** `./gradlew ktlintFormat check` passes.
- **Linux.** The `scull-image` tests (18) pass in Colima on `ubuntu:24.04`.
- **Windows.** `mingwX64` compiles through `check`.
