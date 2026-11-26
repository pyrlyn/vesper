# T17. Done

`vesper-windows` is a Kotlin/Native Win32 app on `mingwX64`. It calls the core directly; there is no C ABI.

- It draws with Direct3D 11 into a flip-model DXGI swap chain, using a DirectWrite glyph atlas.
- It runs the user's shell through ConPTY.
- Input mapped to the core: keys, text, mouse, wheel, paste and focus.
- Window behaviour: live resize and DPI changes.
- Core events handled: title, working directory, OSC 52 clipboard and the bell.
- The T3 benchmark runs on Windows.

The code both hosts share lives in `vesper-host`:

- the atlas, the quad builder, the palette and the shaping cache;
- the event decoding;
- the launch environment, find state, selection gesture and image quad;
- the sprites, rasterised in plain Kotlin. Box drawing, blocks, shades, Powerline U+E0B0–U+E0BF and the underlines are drawn from the cell's geometry on both hosts: the Windows half of T28.

## Slices

All PRs are in pyrlyn/vesper and stacked, each on the one before:

| Slice | PR | Branch |
| --- | --- | --- |
| 1 and the task | #40 | `t17-windows-app` |
| 2, window and swap chain | #41 | `t17.2-window-swapchain` |
| 4, glyph atlas | #42 | `t17.4-glyph-atlas` |
| 5, renderer | #43 | `t17.5-d3d11-renderer` |
| 6, shell and input | #44 | `t17.6-shell-input` |
| 7, events | #45 | `t17.7-events` |
| 8, benchmark | #46 | `t17.8-bench` |
| 3, shared host code and sprites | #47 | `t17.3-shared-host` |

Fixed in slice 3: the Windows CI job had failed from slice 4 on, for two reasons.

- `AtlasLayout` never grew for a glyph larger than the whole atlas.
- The DPI test doubled a window past the CI screen's size.

## How it was checked

- `./gradlew ktlintFormat check linkDebugTestLinuxX64 :vesper-windows:linkDebugTestMingwX64` on macOS.
- Every Linux test binary in Colima (Ubuntu 24.04).
- `./gradlew check` on the `windows-2025` runner, CI run 45902817321 on #47. The Windows tests:
  - WARP renders into an offscreen texture: backgrounds, glyph ink, the cursor and a scroll;
  - the atlas texture keeps its glyphs through growth, and box lines and underlines are sprites;
  - `cmd.exe` runs under ConPTY, driven by window messages: typed text echoes back, `title` reaches the window text, a border drag keeps the session's size until it ends, and a DPI change keeps the grid and scales the cell;
  - a clipboard round trip.
- The pure policies are tested on every target: keys, wheel, pointer, the OSC 7 path and live resize.
- Not checked (needs a person): how the app looks and feels on a Windows desktop.
