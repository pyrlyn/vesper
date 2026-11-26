# Vesper

A terminal for macOS and Windows, written in Kotlin. A Kotlin/Native core parses the terminal stream, keeps the screen and scrollback, and encodes input; each platform gets a Kotlin/Native host over its own APIs (AppKit, Metal and CoreText on macOS; Win32, Direct3D 11 and DirectWrite on Windows) that calls the core directly. The design comes from the Figma file [Modern terminal](https://www.figma.com/design/tCFCAuDJvskm2XTh0cOLcX/Modern-terminal); each screen is described in [`docs/design/`](docs/design/).

## Status

The core is built and tested: the VT parser, Unicode tables, grid and scrollback with reflow, terminal state, input encoders, selection and search, images (kitty, iTerm2, sixel) and the PTY layer. The macOS app runs the user's shell in one window, drawn with Metal (CoreText where there is no GPU); input methods, accessibility and the Windows host come next; the plan is in [`PLAN.md`](PLAN.md) and the tasks in [`plan/`](plan/README.md).

## Requirements

JDK 21 or newer. Gradle is provided by the wrapper. `mise install` sets up the JDK and the lint tools from `mise.toml`.

## Check

```bash
./gradlew check
```

Lints (ktlint) and runs the native tests of every module for the host's targets. This is the merge gate. On macOS it also renders a typed command and a test screen offscreen to `vesper-macos/build/reports/first-light/` (`first-light.png`, `macos-renderer.png`).

## Run (macOS)

```bash
./gradlew :vesper-macos:linkDebugExecutableMacosArm64 && vesper-macos/build/bin/macosArm64/debugExecutable/vesper-macos.kexe
```

## Run (Windows)

```bash
./gradlew :vesper-windows:linkDebugExecutableMingwX64 && vesper-windows/build/bin/mingwX64/debugExecutable/vesper-windows.exe
```

A macOS host builds the same `.exe` (Zig from `mise.toml` must be installed); it runs only on Windows. For now it opens an empty window: the Direct3D renderer is tested offscreen, and the shell comes in a later slice of T17.

## Layout

| Path | Contents |
| --- | --- |
| `vesper-unicode`, `vesper-parser`, `vesper-grid`, `vesper-input`, `vesper-pty`, `vesper-term` | The Kotlin/Native core, layered as `PLAN.md` section 3 describes |
| `vesper-session` | A terminal and the child it runs, under one lock: what each window holds |
| `vesper-host` | What both apps draw with that no graphics API owns: the palette, the frame turned into quads, the glyph atlas's layout and cache, and its bitmaps |
| `vesper-macos` | The macOS app over AppKit, Metal and CoreText |
| `vesper-windows` | The Windows app over Win32, Direct3D 11 and DirectWrite (in progress, T17) |
| `vesper-png`, `vesper-image` | Allocation-capped PNG decoding and the image store with the kitty, iTerm2 and sixel decoders |
| `vesper-harness`, `vesper-bench` | Conformance harness and benchmarks for the core |
| `build-logic` | Gradle convention plugins and the Unicode table generator |
| `tests/` | Golden frames, conformance fixtures and the pinned Unicode data |
| `docs/` | Design notes per screen, benchmarks, research and the config schema |

## License

1. [GNU GPLv3](LICENSE): free for open source applications on any platform, including embedded systems.
2. [Royalty-free License](LICENSE-ROYALTY-FREE.md): free for proprietary desktop, mobile, and web applications, as long as you disclose that your application uses this project. Embedded systems are not covered.
3. [Commercial license](PRICING.md): for proprietary applications, including embedded systems, without the attribution requirement.

<!-- license-sync:start -->
Commercial use not covered by the GPLv3 or the Royalty-free License requires a separate paid
license — see [PRICING.md](PRICING.md).
<!-- license-sync:end -->
