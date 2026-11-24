---
status: todo
priority: P2
complexity: 3
readiness: 0%
agent:
---

# T20. Configuration, fonts and themes

A config file loaded and validated in Kotlin (`vesper-config`): font family and size, colour scheme with overrides, scrollback and key bindings, defaults and built-in schemes, a size cap and unknown keys refused, errors that name the file, the JSON Schema in `docs/config.schema.json` checked for staleness. Edits validate before they write and replace the file atomically; a watcher reloads live and never drops a reload under a flood of events (T30), and a bad edit keeps the last good settings. Both hosts apply it live and have a settings view (family, size, scheme, scrollback). Done when a change applies without restart.

The earlier Rust `config` crate and the removed Swift and C# settings code in `macos/` and `windows/` at eaeb20f are the reference. A TOML library for Kotlin/Native is picked at claim time (maintained, multiplatform) and recorded in `toolchain.md`. The execution plan is written at claim time and split into slices of at most 500 lines.

Check: `./gradlew check` with tests for parsing, defaults, every validation error and cap, key binding parsing, atomic edits and the flood test.

Acceptance:
- [ ] The work described above is implemented in Kotlin/Native.
- [ ] Tests and the `./gradlew check` gate (plus the host tests this task names) pass.
