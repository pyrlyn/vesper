# T902. Done

Pull request: https://github.com/pyrlyn/vesper/pull/33

What was done:
- **Compose app removed.** `composeApp/` is gone, with its Gradle wiring and version catalog entries.
- **Swift and C# hosts removed.** `macos/` and `windows/` are gone, with their CodeQL languages, toolchain rows, NuGet and bundled-font sections and `.gitignore` lines. The bench no longer pairs with the C# bench copy.
- **C ABI removed.** `scull-ffi` is gone: the header, the exports, the C tests under ASan, UBSan and TSan, and the generated C# bindings. The CI link step, the clang row and `Frame.packedCell` (read only by the ABI) went with it. Comments that justified a choice by the C ABI now give the reason that still holds.
- **Renamed scull to vesper:**
  - modules `vesper-*`;
  - packages `vesper.*`;
  - the build-logic plugins `vesper.kmp`, `vesper.library` and `vesper.ucd`, and the `vesper.ucd` package;
  - `VESPER_TESTS_DIR`;
  - the `vesper_*` C helpers in the PTY cinterop;
  - the bench row `vesper stub`;
  - the config schema's `vesper-dark` and `vesper-light`;
  - the docs and the task records.
  
  Port notes name the earlier Rust core's file and crate.
- **Docs and plan rewritten for Kotlin/Native hosts that call the core directly.** This covers AGENTS.md, README.md, PLAN.md, research.md §6–§9 (decisions 8 and 9 are new), docs/README.md and docs/site.md.
  - The renderer benchmark pages of the removed hosts are deleted.
  - The active host tasks (T13.4, T14–T18, T20, T21, T27–T29, T31–T33) are re-planned, each pointing at the removed code at eaeb20f as its reference design.
  - T25 and T900 got front matter.
  - No active task existed only for the removed parts, so none was dropped. T14.1 (C ABI input exports) never reached `main`: its pull request was closed.
- **Reused:** nothing new was written. The rename was mechanical, and the bench, harness and core code stayed as they were apart from the removed `packedCell`.

How it was checked:
- `./gradlew ktlintFormat check` on macOS arm64 passes.
- The linuxX64 test binaries of all ten modules pass in an `ubuntu:24.04` amd64 container on Colima, 525 tests: bench 3, grid 91, harness 7, image 53, input 69, parser 46, png 18, pty 47, term 165, unicode 26.
- `git grep -i scull` finds nothing outside `plan/T902`, and no tracked path contains it.
- `jactionlint` is clean. One stale baseline entry for `pipeline.yml` predates this task.
