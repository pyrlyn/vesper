---
status: todo
priority: P1
complexity: 3
readiness: 0%
agent:
---

# T19.2. Sixel decoder

Part of T19. A streaming decoder fed DCS bytes (`put`) into an RGBA buffer in `scull-image`: the palette (HLS and RGB), raster attributes, repeat, carriage return and newline, and background select. Width, height and colour caps are checked before the buffer grows. Reuse a maintained Kotlin library if one fits; otherwise write it and say why in the commit. Done when golden tests cover the protocol, the decoder has a fuzz entry run by the tests, and `./gradlew check` passes.
