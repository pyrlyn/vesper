---
status: in progress
priority: P0
complexity: 4
readiness: 10%
agent: Claude Code / claude-opus-5-5
---

# T902. Remove the old hosts and the C ABI, rename scull to vesper

The creator's direction: Kotlin wherever possible, and the name "scull" is no longer used. Both
hosts become Kotlin/Native (AppKit, Metal and CoreText on macOS; Win32, Direct3D and DirectWrite on
Windows) and call the core modules directly. So the Compose app with its own emulator, the Swift
and C# hosts and the C ABI between them and the core all go, and the core takes the project's name.

Done when the repository holds only the Kotlin/Native core under `vesper-*` modules and `vesper.*`
packages, nothing builds or documents the removed parts, the plan describes Kotlin/Native hosts,
and `./gradlew check` passes on macOS and Linux.

## Execution plan

1. Delete `composeApp/` with its Gradle wiring, version catalog entries and docs.
2. Delete the Swift host `macos/` and the C# host `windows/`.
3. Delete `scull-ffi` (header, exports, C tests, sanitizer tasks) and the CI step that links it.
4. Rename `scull-*` modules to `vesper-*`, packages `scull.*` to `vesper.*`, the build-logic plugins
   `scull.*` to `vesper.*`, `VESPER_TESTS_DIR` to `VESPER_TESTS_DIR`, and every "Scull" in the docs.
   Port notes that named a file of the earlier Rust core say "the earlier Rust core" instead.
5. Rewrite `AGENTS.md`, `PLAN.md`, `README.md`, `toolchain.md` and the active tasks for Kotlin/Native
   hosts and no C ABI; close tasks that only existed for the removed parts.
6. Verify: `./gradlew check` on macOS arm64, the native tests on linuxX64 under Colima, and a search
   that finds no "scull" left.
