# T2. Done

Pull request: https://github.com/pyrlyn/vesper/pull/64

- Gradle 9.9.2 wrapper with a pinned SHA-256, Kotlin 2.4.21, and ktlint 1.8.0 run through ktlint-gradle 14.2.0. The wrapper, version catalog and convention plugins reuse runa's phase 0 skeleton (pyrlyn/runa#142).
- `build-logic`: `vesper.kmp` sets up `macosArm64`, `linuxX64` and `mingwX64`, with warnings as errors, ktlint and `kotlin.test`. `vesper.library` adds `explicitApi()`.
- The seven modules, wired as in `PLAN.md` §3. `vesper-ffi` links `libvesper` as shared and static libraries and exports `vesper_abi_version`, because Kotlin/Native links no library from an empty module. The other six modules have no sources yet.
- `ci.yml` gains one `<os> (<target>)` job per target. Each runs `./gradlew check` and links the release libraries.
- `toolchain.md`, `README.md`, `AGENTS.md` and `PLAN.md` §6 and §8 are updated. §8 notes that Kotlin 2.5 is needed before Gradle 10 because of a KGP 2.4 deprecation.

Checked:
- macOS arm64: `./gradlew check` is green (ktlint, plus `VersionTest` on `macosArm64`). `nm` shows `_vesper_abi_version` exported from `libvesper.dylib`.
- Linux x64: the `linuxX64` test binary passes in an `ubuntu:24.04` amd64 container on Colima, and `libvesper.so` links.
- Windows: `vesper.dll` and `vesper.def` cross-link from macOS. The `mingwX64` tests have not run yet, because every workflow in pyrlyn/vesper is disabled in the repository settings, so the new CI jobs have not started.
