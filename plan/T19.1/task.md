---
status: in progress
priority: P1
complexity: 3
readiness: 100%
agent: Claude Code / claude-opus-5-5
---

# T19.1. Image store and iTerm2 inline images

The first part of T19: a module `vesper-image` with the decoded image, its placements and a store under a byte quota, and the iTerm2 inline image decoder. Done when the store's quota and eviction hold under random operations, iTerm2 images decode under caps checked before allocation, the decoder has a fuzz entry run by the tests, and `./gradlew check` passes.

## Findings

- **iTerm2 protocol.** Source: https://iterm2.com/documentation-images.html, checked 2026-11-23 (the page has no date).
  - `OSC 1337 ; File=` arguments `:` base64 file contents, ended by BEL or ST.
  - The arguments are `key=value` pairs separated by `;`: `name` (base64), `size` (bytes, progress only), `width` and `height` (`N` cells, `Npx`, `N%`, `auto`), `preserveAspectRatio` (default 1), `inline` (default 0: download, not shown).
  - Since iTerm2 3.5 the multipart form also exists: `MultipartFile=` arguments, then `FilePart=` base64 chunks, then `FileEnd`.
  - iTerm2 shows any format macOS decodes. The core decodes PNG (T34). JPEG, GIF and the rest are refused as unsupported; a decoder for them is an idea, not this task.
- **The parser already streams the strings.** OSC is capped at 16 MiB (about 12 MiB of file), DCS at 16 MiB, APC at 4 MiB, in `vesper-parser`.
- **Base64** comes from the Kotlin standard library (`kotlin.io.encoding.Base64`, stable since Kotlin 2.2), so it needs no dependency.
- **Source set.** `vesper-term` is `commonMain`, so `vesper-image` is `commonMain` too and depends on `vesper-png`'s `nativeMain`; with only native targets both have the same targets. That needed `vesper-png` to avoid zlib's `uLong` (fixed on the T34 branch).

## Decisions

- **No generation counter.** Image ids are never reused, so an id names one immutable pixel buffer and a texture cache keyed by id never goes stale.
- **Eviction.** Least recently used images that no placement shows go first. If that is not enough, the least recently used placed images go too, with their placements. The quota is a hard memory cap, and a full scrollback must not block new images, which is what kitty does. An image larger than the whole quota is refused.

## Execution plan

1. **Module.** `vesper-image` (`commonMain`, `api(projects.vesperPng)`), in `settings.gradle.kts`.
2. **Types.**
   - `RgbaImage` (width, height, RGBA8).
   - `ImageError`: `Malformed`, `Unsupported`, `Decode`, `TooLarge`. PNG errors map to `Decode` and `TooLarge`.
   - `Image` (store id and pixels).
   - `Placement` (image id, column and absolute line, cell size, optional pixel crop, z-index).
3. **`ImageStore(quotaBytes, maxImages)`.**
   - `add`, `get` (touches), `delete`, `place`, `unplace`, `removePlacements(predicate)`, `placements`, `usedBytes`.
   - LRU by a use counter, evicting as decided above.
4. **`ITerm2Images(maxFileBytes, maxPixelBytes)`.**
   - `accept(payload)` takes an OSC 1337 payload: `File=`, `MultipartFile=`, `FilePart=`, `FileEnd`.
   - It returns an `InlineImage` (pixels, name, width, height, aspect flag) or null.
   - Caps: `size=` past the file cap is refused before any data, multipart data is capped as it grows, and the pixel cap goes to `Png.decode`.
5. **Fuzz entries.** `fuzzInlineImages(data)` and `fuzzImageStore(data)` in main code, as the parser has. The tests run them over a seeded random corpus.
6. **Tests.**
   - Store: quota, LRU order, placed images kept over unplaced ones, a placed image evicted with its placements, an oversized image refused, the image-count cap.
   - iTerm2: every argument, extents, the download form ignored, multipart, base64 with and without padding, PNG decoded, JPEG and GIF unsupported, caps, malformed input.
7. **Verify.** `./gradlew ktlintFormat check`, then Linux in Colima. Update `PLAN.md` and `toolchain.md` if anything new is used.
