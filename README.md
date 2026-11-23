# Vesper

A modern terminal for macOS, Windows and Linux, built with Kotlin Multiplatform and Compose Multiplatform. The design comes from the Figma file [Modern terminal](https://www.figma.com/design/tCFCAuDJvskm2XTh0cOLcX/Modern-terminal); each screen is described in [`docs/design/`](docs/design/).

## Features

- Command blocks. Each command and its output form a card with exit status, duration, and copy, re-run, stop and delete actions. Blocks are detected through shell integration (OSC 133/7) for zsh, bash, fish and PowerShell.
- Full-screen programs (vim, htop, less) switch to a classic grid with mouse and alt-screen support.
- A prompt bar with context chips (directory, git branch and status, node, package manager), ghost-text suggestions and history.
- Tabs and split panes, with zoom and draggable dividers.
- A command palette (⌘K / Ctrl+K) covering hosts, workflows, commands, history and directories.
- A system monitor showing CPU, memory, processes, network, listening ports and activity.
- SSH hosts, read from `~/.ssh/config` plus your own saved hosts.
- Workflows: named multi-step commands.
- Settings for theme (dark/light/system), accent colour, font, cursor and shell.

## Native core

The repository also holds the Kotlin/Native terminal core that started as the Scull project: the VT parser, Unicode tables, grid and scrollback, with a C ABI for native front ends (SwiftUI in `macos/`, WinUI in `windows/`). It is being built task by task; the plan is in [`PLAN.md`](PLAN.md) and the task folders in [`plan/`](plan/README.md). The Compose app still runs on its own JVM parser and emulator.

## Requirements

JDK 21 or newer. Gradle is provided by the wrapper. `mise install` sets up the JDK and the lint tools from `mise.toml`.

## Check

```bash
./gradlew check
```

Lints (ktlint) and tests the native core for the host's targets and runs the app's JVM tests. This is the merge gate.

## Run

```bash
./gradlew :composeApp:run
```

## Package

```bash
./gradlew :composeApp:packageDistributionForCurrentOS
```

This produces a DMG on macOS, an MSI on Windows, and a DEB or RPM on Linux.

## Layout

| Path | Contents |
| --- | --- |
| `composeApp/src/commonMain/.../terminal` | VT parser, emulator and PTY session with block tracking |
| `composeApp/src/commonMain/.../app` | App state: tabs, panes, settings, history |
| `composeApp/src/commonMain/.../ui` | Compose UI: blocks, grid, prompt, palette, monitor, settings, hosts |
| `composeApp/src/jvmMain` | pty4j launcher, OSHI monitor, platform services, window chrome, `Main.kt` |
| `vesper-unicode`, `vesper-parser`, `vesper-grid`, `vesper-input`, `vesper-pty`, `vesper-term`, `scull-ffi` | The Kotlin/Native core, layered as `PLAN.md` section 3 describes |
| `vesper-harness`, `vesper-bench` | Conformance harness and benchmarks for the core |
| `build-logic` | Gradle convention plugins and the Unicode table generator |
| `macos/`, `windows/` | Native front ends that will link the core through the C ABI |
| `docs/` | Design notes per screen, benchmarks, research and the config schema |

## Fonts

The app bundles JetBrains Mono (© The JetBrains Mono Project Authors) and Inter (© The Inter Project Authors). Both are licensed under the SIL Open Font License 1.1: https://openfontlicense.org

## License

1. [GNU GPLv3](LICENSE): free for open source applications on any platform, including embedded systems.
2. [Royalty-free License](LICENSE-ROYALTY-FREE.md): free for proprietary desktop, mobile, and web applications, as long as you disclose that your application uses this project. Embedded systems are not covered.
3. [Commercial license](PRICING.md): for proprietary applications, including embedded systems, without the attribution requirement.

<!-- license-sync:start -->
Commercial use not covered by the GPLv3 or the Royalty-free License requires a separate paid
license — see [PRICING.md](PRICING.md).
<!-- license-sync:end -->
