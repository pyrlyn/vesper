---
status: in progress
priority: P2
complexity: 3
readiness: 40%
agent: Claude Code / claude-opus-5-5
---

# T20. Configuration, fonts and themes

A config file loaded and validated in Kotlin (`vesper-config`): font family and size, colour scheme with overrides, scrollback and key bindings, defaults and built-in schemes, a size cap and unknown keys refused, errors that name the file, the JSON Schema in `docs/config.schema.json` checked for staleness. Edits validate before they write and replace the file atomically; a watcher reloads live and never drops a reload under a flood of events (T30), and a bad edit keeps the last good settings. Both hosts apply it live and have a settings view (family, size, scheme, scrollback). Done when a change applies without restart.

The earlier Rust `config` crate and the removed Swift and C# settings code in `macos/` and `windows/` at eaeb20f are the reference. A TOML library for Kotlin/Native is picked at claim time (maintained, multiplatform) and recorded in `toolchain.md`. The execution plan is written at claim time and split into slices of at most 500 lines.

Check: `./gradlew check` with tests for parsing, defaults, every validation error and cap, key binding parsing, atomic edits and the flood test.

Acceptance:
- [ ] The work described above is implemented in Kotlin/Native.
- [ ] Tests and the `./gradlew check` gate (plus the host tests this task names) pass.

## Execution plan

The reference is the Rust `scull-config` crate at 677ad4c^. The scheme names become `vesper-*`, as `docs/config.schema.json` already spells them.

**Decisions:**
- **TOML: ktoml** (https://github.com/orchestr7/ktoml, MIT). It is multiplatform with all three native targets, on kotlinx.serialization, maintained, and refuses unknown keys by default.
- **Files: okio**, already a dependency. It covers the capped read, the atomic move and the metadata stamp.
- **Watcher: a polling thread.** It checks the file's stamp often and compares the bytes on a slower resync. A change is applied only once two polls agree. Polling cannot miss the final state under a flood (T30), and it needs no cinterop for inotify, FSEvents or ReadDirectoryChangesW.

**Slices**, each one pull request of at most 500 lines:

1. **`vesper-config` core.**
   - `Settings`, `Font`, `Rgb`, `Scheme` and `Theme`, with the built-in schemes.
   - The caps: file size, family length, font size range, scrollback, palette overrides.
   - Strict parsing through ktoml. Errors name the file. A missing file means the defaults.
   - `defaultPath`.
   - Tests for parsing, defaults and every validation error and cap.
2. **Key bindings and the schema.**
   - `[[keybind]]` over `vesper-input`'s `Key` and `Modifiers`: chord grammar, aliases, the typing guard, duplicates, the cap, and defaults laid under the file's entries.
   - A test that rebuilds `docs/config.schema.json` from the model and fails when the committed file differs.
3. **Edits and live reload.**
   - `set(key, value)` changes only the listed settings. It validates the whole result before writing, and writes through a temp file and an atomic rename, writing through a symlink rather than replacing it.
   - `LiveConfig` keeps the last good settings when an edit is bad, and has generations and a change callback.
   - The flood test (T30) and the atomic edit tests.
4. **The macOS host.**
   - A shared settings-to-host mapping in `vesper-host`: palette, font, scrollback and bindings.
   - `vesper-macos` applies it live and gets a settings window (family, size, scheme, scrollback).
5. **The Windows host.** `vesper-windows` applies it live and gets a settings dialog.

Each slice runs the gate and the Linux tests in Colima.
