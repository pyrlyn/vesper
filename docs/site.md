---
title: Scull
tagline: A terminal emulator with a Kotlin/Native core and a native interface on each platform — SwiftUI on macOS, WinUI on Windows.
repo: https://github.com/pyrlyn/scull
install: "git clone https://github.com/pyrlyn/scull && cd scull && mise install && ./gradlew check"
version: "0.0.0"
status: "Early development · no release yet"
accent: "#7FD1E8"
order: 11
---

<!-- Website copy for the pyrlyn project site, copied to pyrlyn/landing as
content/projects/scull.md; front matter follows CONTENT_CONTRACT.md in that repository.
Sources (checked 2026-11-22): README.md, PLAN.md and research.md; no release yet, hence
status. The accent is a placeholder: the repository has no brand colour yet. -->

## Overview

Scull is a terminal emulator with a Kotlin/Native core and a native interface on each
platform. The core parses, keeps state and encodes input; the platform draws text with its
own text stack and handles windows, input methods and accessibility.

Scull is in early development with no release: it is being rebuilt in Kotlin/Native. The
SwiftUI app in `macos/` and the WinUI app in `windows/` stay in the repository and will
link the core over a C ABI (`scull-ffi`).

## Features

- **Kotlin/Native core.** Parser, grid, PTY, input, Unicode, image and config modules.
- **Native UI per platform.** SwiftUI on macOS and WinUI on Windows, over a C ABI (`scull-ffi`).
- **Platform text stacks.** Each platform draws text, handles input methods and accessibility itself.
- **Config schema.** A JSON Schema for the config file is generated from the code.

## Install

There is no release yet. Build from the repository (the Gradle wrapper lands in T2):

```bash
git clone https://github.com/pyrlyn/scull && cd scull && mise install && ./gradlew check
```

## Usage examples

Build the native apps (after the core library is in place):

```bash
# macOS: swift build in macos/, then assemble Scull.app
# Windows: dotnet build windows/Scull.App/Scull.App.csproj
```

## Links

- Repository: <https://github.com/pyrlyn/scull>
- Plan: <https://github.com/pyrlyn/scull/blob/main/PLAN.md>
- Research: <https://github.com/pyrlyn/scull/blob/main/research.md>
- Documentation: <https://github.com/pyrlyn/scull/tree/main/docs>
