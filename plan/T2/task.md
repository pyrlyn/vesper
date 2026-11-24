---
status: in progress
priority: P0
complexity: 3
readiness: 90%
agent: Claude Code / claude-opus-5-5
---

# T2. Workspace scaffold

Gradle Kotlin Multiplatform workspace with the seven modules of `research.md` §7, a pinned JDK and Kotlin/Native toolchain, ktlint, compiler warnings as errors, CI, and `toolchain.md` filled in. Done when an empty Gradle workspace builds and lints clean on macOS, Linux and Windows.

Targets: `macosArm64` (primary), `linuxX64`, and `mingwX64` for the core library the Windows host loads. The C ABI module (`vesper-ffi`) produces a static or shared library for the Swift and C# apps.

Acceptance:
- [x] The seven modules exist, compile as Kotlin/Native, and lint clean.
- [x] `./gradlew check` is the merge gate and is documented in `README.md` and `toolchain.md`.

## Execution plan

The skeleton reuses the one runa landed in its phase 0 (pyrlyn/runa#142): the same Gradle wrapper, version catalog, `build-logic` convention plugins and ktlint setup, with Vesper's targets and modules.

1. Gradle wrapper (`gradlew`, `gradlew.bat`, `gradle/wrapper/`), `gradle.properties`, `gradle/libs.versions.toml` (Kotlin, ktlint-gradle, ktlint).
2. `build-logic/`: `vesper.kmp` (targets `macosArm64`, `linuxX64`, `mingwX64`; warnings as errors; ktlint; `kotlin.test`) and `vesper.library` (adds `explicitApi()`).
3. `settings.gradle.kts` with the seven modules `vesper-ffi`, `vesper-term`, `vesper-input`, `vesper-grid`, `vesper-unicode`, `vesper-parser`, `vesper-pty`, wired by the layering of `PLAN.md` §3. `vesper-ffi` builds a shared and a static library named `vesper`; Kotlin/Native links no library from an empty module, so it exports `vesper_abi_version` with a test. The other six modules stay empty until their tasks.
4. CI: `ci.yml` gains `<os> (<target>)` jobs (macOS `macosArm64`, Ubuntu `linuxX64`, Windows `mingwX64`) that run `./gradlew check` and link the `vesper` libraries, keeping the jactionlint job and the `SKIP_CI_TESTS` / `MACOS_CI_DISABLED` switches.
5. Docs: `toolchain.md` (Gradle rows, a Gradle plugins table), `README.md` and `AGENTS.md` commands, `PLAN.md` §6.
6. Verify: `./gradlew check` and the library link tasks on macOS locally; the three CI jobs green on the PR.
