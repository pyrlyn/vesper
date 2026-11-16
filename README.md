# Vesper

A modern terminal for macOS, Windows and Linux, built with Kotlin Multiplatform and Compose Multiplatform. The design comes from the Figma file "Modern terminal"; reference screenshots are in `docs/design/`.

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

## Requirements

JDK 21 or newer. Gradle is provided by the wrapper.

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

## Fonts

The app bundles JetBrains Mono (© The JetBrains Mono Project Authors) and Inter (© The Inter Project Authors). Both are licensed under the SIL Open Font License 1.1: https://openfontlicense.org
