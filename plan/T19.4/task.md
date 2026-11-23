---
status: todo
priority: P1
complexity: 4
readiness: 0%
agent:
---

# T19.4. Images in the terminal and the frame

Part of T19, after T19.1–T19.3.

- **`scull-term`.** Wire `scull-image` in:
  - route DCS sixel, APC `G` and OSC 1337 to the decoders;
  - place images at the cursor and move the cursor past them;
  - re-anchor placements on scroll and reflow;
  - clear them with the alternate screen and erase.
- **Frame.** Carry the visible placements, so the platform uploads textures by image id.
- **C ABI.** Exports for placements and pixels, an ABI minor bump and the bindings.

Done when golden tests cover each protocol end to end and `./gradlew check` passes, with the C ABI sanitizer test.
