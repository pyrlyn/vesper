---
status: in progress
priority: P1
complexity: 3
readiness: 90%
agent: Claude Code / claude-opus-5-5
---

# T19.2. Sixel decoder

Part of T19. A streaming decoder fed DCS bytes (`put`) into an RGBA buffer in `scull-image`: the palette (HLS and RGB), raster attributes, repeat, carriage return and newline, and background select. Width, height and colour caps are checked before the buffer grows. Reuse a maintained Kotlin library if one fits; otherwise write it and say why in the commit. Done when golden tests cover the protocol, the decoder has a fuzz entry run by the tests, and `./gradlew check` passes.

## Findings

- **Protocol.** VT330/VT340 Programmer Reference, chapter 14: https://vt100.net/docs/vt3xx-gp/chapter14.html, checked 2026-11-23.
  - `DCS P1;P2;P3 q … ST`. P2 0 or 2 fills unset pixels with the background; 1 leaves them as they are.
  - Sixels are `?`..`~`, bit 0 on top. `!Pn` repeats, and `"Pan;Pad;Ph;Pv` (before any sixel) gives the aspect and size.
  - `#Pc` selects and `#Pc;Pu;Px;Py;Pz` defines (1 HLS, hue 0–360; 2 RGB; percent). `$` is carriage return and `-` is next band. Pc runs from 0 to 255 (Table 14-1).
- **Default colours.** The VT340 colour map, chapter 2, Table 2-3: https://vt100.net/docs/vt3xx-gp/chapter2.html, checked 2026-11-23. DEC hue puts blue at 0°, red at 120° and green at 240°.
- **No library to reuse.**
  - There is no maintained Kotlin Multiplatform sixel decoder.
  - libsixel (https://github.com/saitoha/libsixel) is C, and the rule is Zig before C for native code. It would also bring its decoders' attack surface into the core.

## Decisions

- **Aspect ratio is not applied** (P1, `Pan/Pad`). Today's encoders send 1:1, and xterm, foot and WezTerm draw square pixels. **Unverified**: from those projects' behaviour, not checked against their sources here.
- **Background (P2 0 or 2)** is a colour the caller passes, the terminal's background (T19.4). The register 0 of the image is not used for it.
- **Colour at draw time.** A pixel keeps the colour it was drawn in; redefining a register later does not repaint it.
- **Size.** The image is as large as the sixels drawn (to the lowest set row) or the raster size, whichever is larger. One that draws nothing and gives no size is an error.
- **Caps.** Past a cap the rest of the stream is ignored and `finish` throws `TooLarge`. The buffer doubles within the caps.

## Execution plan

1. `SixelDecoder(transparentBackground, maxWidth, maxHeight, maxPixelBytes, background)` with `put(bytes, start, end)` and `finish()` in `scull-image`.
2. `fuzzSixel(data)`: whole and cut streams must agree, only `ImageError` escapes, the image stays within the caps.
3. Golden tests: bit order, repeat, `$` and `-`, the VT340 map, HLS and RGB, clamping, redefinition, raster attributes and background, ignored bytes, caps, an empty image, and the fuzz over seeded corpora.
4. `./gradlew ktlintFormat check`, then Linux in Colima.
