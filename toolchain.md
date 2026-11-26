# Toolchain

## Programs

| Program | How to install | Why here | Source |
| --- | --- | --- | --- |
| JDK 21 (Temurin) | mise (`java = "temurin-21"` in `mise.toml`) | Runs Gradle and the Kotlin/Native compiler | https://adoptium.net |
| Gradle | the wrapper (`./gradlew`, 9.9.2 with a pinned SHA-256 in `gradle/wrapper/gradle-wrapper.properties`) | Builds the Kotlin/Native core; `./gradlew check` (ktlint and the native tests) is the merge gate | https://github.com/gradle/gradle |
| Kotlin/Native | the Kotlin Gradle plugin (`gradle/libs.versions.toml`); Gradle downloads the compiler into `~/.konan` | Compiles the core for `macosArm64`, `linuxX64` and `mingwX64`; a macOS host cross-links all three, tests run only on the host's own target | https://github.com/JetBrains/kotlin |
| ktlint | the ktlint Gradle plugin runs the version pinned in `gradle/libs.versions.toml` | Formatting and lint of every Kotlin source and build script, part of `check` | https://github.com/pinterest/ktlint |
| mise | brew, then `mise install` | Pins the JDK and lint tools for local work and CI | https://github.com/jdx/mise |
| jactionlint | mise (`mise.toml` pins `github:jdx/jactionlint` 2.0.2) | Lint the workflows (the `jactionlint` CI job, the pre-push hook and `mise x -- jactionlint`) | https://github.com/jdx/jactionlint |
| hk | mise (`mise.toml` pins 2.5.0) | Runs jactionlint as a pre-push hook (`hk.pkl`) | https://github.com/jdx/hk |
| shellcheck | mise (`mise.toml` pins 0.11.0; CI uses the runner image's copy) | Checks `run:` scripts inside jactionlint | https://github.com/koalaman/shellcheck |
| Zig | mise (`mise.toml` pins 0.17.1) | Supplies mingw-w64's IDL-generated DirectWrite headers (`dwrite*.h`, `dcommon.h`) that `vesper-windows` binds with cinterop; the Kotlin/Native sysroot's copies do not compile as C (`research.md` §6.2). No Zig code is built | https://codeberg.org/ziglang/zig |

The PTY bindings (POSIX PTY, ConPTY) need no package: `vesper-pty` declares them in its own cinterop `.def` files, compiled by the clang that ships with Kotlin/Native (`plan/T8/task.md` says why). `vesper-windows` binds Direct3D 11, DXGI and DirectWrite the same way (`directx.def`, headers only), linking the sysroot's `d3d11`, `dxgi`, `dxguid` and `dwrite` import libraries. A fuzz/property-test runner is chosen and recorded here in T25.

## Kotlin/Native platform libraries

| Package | Where | Source | Why here |
| --- | --- | --- | --- |
| `platform.zlib` | local (`vesper-png`; ships with the Kotlin/Native distribution, links the system `libz` on macOS and Linux and a static copy on `mingwX64`) | https://github.com/madler/zlib | Inflate, deflate and CRC-32 for the PNG decoder and its fixture encoder; capped inflate for kitty graphics `o=z` |

## Gradle

| Package | Where | Source | Why here |
| --- | --- | --- | --- |
| org.jetbrains.kotlin:kotlin-gradle-plugin 2.4.21 | local (`gradle/libs.versions.toml`, used by `build-logic`) | https://github.com/JetBrains/kotlin | Kotlin Multiplatform and the Kotlin/Native compiler for every module |
| org.jlleitschuh.gradle:ktlint-gradle 14.2.0 | local (`gradle/libs.versions.toml`, used by `build-logic`) | https://github.com/JLLeitschuh/ktlint-gradle | Runs ktlint 1.8.0 as part of `check` |
| org.jetbrains.kotlin:kotlin-test | local (every module's `commonTest`, version from the Kotlin plugin) | https://github.com/JetBrains/kotlin | Test API of the native tests |
| com.squareup.okio:okio 3.18.2 | local (`vesper-harness`, `vesper-bench`) | https://github.com/square/okio | Multiplatform file access: the harness reads the golden frames and the conformance fixtures in `tests/`, the bench writes `docs/benchmarks/baseline.md`; Kotlin/Native has no `java.io` |

## Linkers

- **Windows:** the Kotlin/Native `mingwX64` toolchain linker.
- **Linux:** the Kotlin/Native `linuxX64` linker.
- **macOS:** Apple's native linker, used by Kotlin/Native (`macosArm64`).
