---
title: Vesper
tagline: A terminal emulator written in Kotlin, with a Kotlin/Native core and a native host on each platform.
repo: https://github.com/pyrlyn/vesper
install: "git clone https://github.com/pyrlyn/vesper && cd vesper && mise install && ./gradlew check"
version: "0.0.0"
status: "Early development · no release yet"
accent: "#7FD1E8"
order: 11
---

<!-- Website copy for the pyrlyn project site, copied to pyrlyn/landing as
content/projects/vesper.md; front matter follows CONTENT_CONTRACT.md in that repository.
Sources (checked 2026-11-23): README.md, PLAN.md and research.md; no release yet, hence
status. The accent is a placeholder: the repository has no brand colour yet. -->

## Overview

Vesper is a terminal emulator written in Kotlin. A Kotlin/Native core parses the terminal
stream, keeps the screen and scrollback, and encodes input; each platform gets a Kotlin/Native
host that draws text with the platform's own text stack and handles windows, input methods and
accessibility.

Vesper is in early development with no release: the core is built and tested, and the macOS
and Windows hosts are next.

## Features

- **Kotlin/Native core.** Parser, Unicode tables, grid and scrollback with reflow, PTY, input encoders, selection and search, and images (kitty, iTerm2, sixel).
- **Native hosts in Kotlin.** AppKit, Metal and CoreText on macOS; Win32, Direct3D 11 and DirectWrite on Windows; no C ABI in between.
- **Platform text stacks.** Each platform shapes text and handles input methods and accessibility itself; the core alone decides cell widths.
- **Untrusted input has limits.** Every payload, table and queue the terminal stream can grow has a hard cap and a test.

## Install

There is no release yet. Build and test from the repository:

```bash
git clone https://github.com/pyrlyn/vesper && cd vesper && mise install && ./gradlew check
```

## Links

- Repository: <https://github.com/pyrlyn/vesper>
- Plan: <https://github.com/pyrlyn/vesper/blob/main/PLAN.md>
- Research: <https://github.com/pyrlyn/vesper/blob/main/research.md>
- Documentation: <https://github.com/pyrlyn/vesper/tree/main/docs>
