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
| kitty, Alacritty, foot, cage, Xvfb, Mesa | apt in the `ubuntu:26.04` container of `vesper-bench/terminals.sh` (CI, Linux job) | Reference terminals the T3 baseline times, and the X11 and Wayland displays and software OpenGL they draw on | https://github.com/kovidgoyal/kitty, https://github.com/alacritty/alacritty, https://codeberg.org/dnkl/foot, https://github.com/cage-kiosk/cage, https://gitlab.freedesktop.org/xorg/xserver, https://gitlab.freedesktop.org/mesa/mesa |
| WezTerm 20240203-110809-5046fc22, Contour 0.8.0.9335 | their GitHub release `.deb`s, pinned by SHA-256 in `vesper-bench/terminals.sh` | Reference terminals the T3 baseline times; Ubuntu does not package them | https://github.com/wezterm/wezterm, https://github.com/contour-terminal/contour |
| Zig | mise (`mise.toml` pins 0.17.1) | Supplies mingw-w64's IDL-generated DirectWrite headers (`dwrite*.h`, `dcommon.h`) that `vesper-windows` binds with cinterop; the Kotlin/Native sysroot's copies do not compile as C (`research.md` §6.2). No Zig code is built | https://codeberg.org/ziglang/zig |

The PTY bindings (POSIX PTY, ConPTY) and the locks and threads need no package: `vesper-pty` and `vesper-sync` declare them in their own cinterop `.def` files, compiled by the clang that ships with Kotlin/Native (`plan/T8/task.md` says why). `vesper-windows` binds Direct3D 11, DXGI, DirectWrite and the HLSL compiler the same way (`directx.def`, headers only), linking the sysroot's `d3d11`, `dxgi`, `dxguid`, `dwrite` and `d3dcompiler_47` import libraries; `d3dcompiler_47.dll` ships with Windows 10 and later.

Fuzzing needs no package either (T25). No coverage-guided fuzzer runs Kotlin/Native code: kotlinx.fuzz (https://github.com/JetBrains-Research/kotlinx.fuzz) is built on Jazzer and runs only on the JVM, the modules have no JVM target, and Kotlin/Native has no libFuzzer integration. Each fuzz target therefore keeps a seeded corpus in its tests through `vesper.harness.fuzzCases`, on `kotlin.random` and `kotlin.time`. The CI job `fuzz and Unicode (linuxX64)` runs those corpora for 30 s each from a fresh seed (`VESPER_FUZZ_SECONDS`, `VESPER_FUZZ_SEED`), and runs `:vesper-unicode:checkUcd`, which downloads the pinned data files from unicode.org with the JDK's HTTP client and checks their SHA-256s. That job uses only the programs above.

### Advisory audit (T900)

The audit covers the whole resolved Gradle graph: the build-logic and plugin classpaths, every module's configurations, `okio` and `kotlin-native-prebuilt`. To run it:

1. `./gradlew --write-verification-metadata sha256 --dry-run help` writes `gradle/verification-metadata.dryrun.xml` with every artifact the build resolves. The file is ignored by git and is not a verification file.
2. Each `group:name:version` in it goes to the OSV batch API (https://api.osv.dev/v1/querybatch, ecosystem `Maven`), which carries the GitHub advisories.

On 2026-11-28 the audit covered 113 artifacts:

- **Fixed: `ch.qos.logback:logback-core` 1.3.16.** It has GHSA-qqpg-mvqg-649v, GHSA-p47f-322f-whfh and GHSA-jhq6-gfmj-v8fx, all low, fixed by 1.5.34. It comes from the `ktlint` configuration through `ktlint-cli` 1.8.0 (the latest release). `vesper.kmp` constrains logback on that configuration to the catalog's 1.5.38, which pulls slf4j-api 2.0.17.
- **Waived: `io.opentelemetry:opentelemetry-api` 1.41.0.** It has GHSA-ccrj-gw8r-854m and GHSA-rcgg-9c38-7xpx, both moderate, fixed by 1.67.0 and 1.62.0. Both concern parsing W3C `tracestate` and baggage headers.
  - It reaches the graph only through `kotlin-gradle-plugin` 2.4.21 (the latest stable release): `swift-export-embeddable`, on the `swiftExportClasspathResolvable` configuration of the Swift Export worker.
  - Nothing here runs that worker. It serves `embedSwiftExportForXcode`, which only an Xcode build invokes. A logged run of the gate resolved that configuration in no module.
  - Recheck the waiver when Kotlin moves on, or if a module ever uses Swift Export.

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
| com.squareup.okio:okio 3.18.2 | local (`vesper-harness`, `vesper-bench`, `vesper-config`) | https://github.com/square/okio | Multiplatform file access: the harness reads the golden frames and the conformance fixtures in `tests/`, the bench writes `docs/benchmarks/baseline.md`, `vesper-config` reads the config file under its size cap; Kotlin/Native has no `java.io` |
| com.akuleshov7:ktoml-core 0.7.1 | local (`vesper-config`) | https://github.com/orchestr7/ktoml | Decodes the TOML config file into `@Serializable` types and refuses unknown keys. It is multiplatform with all three native targets and maintained. It brings `kotlinx-datetime` |
| org.jetbrains.kotlinx:kotlinx-serialization-core 1.13.0 | local (`vesper-config`) | https://github.com/Kotlin/kotlinx.serialization | The runtime ktoml decodes through. Pinned to the release built for Kotlin 2.4 rather than ktoml's 1.9.0 |
| org.jetbrains.kotlinx:kotlinx-serialization-json 1.13.0 | local (`vesper-config` tests) | https://github.com/Kotlin/kotlinx.serialization | Renders `docs/config.schema.json` from the config model in the staleness test; the same release as the core runtime |
| org.jetbrains.kotlin:kotlin-serialization (version from the Kotlin plugin) | local (`gradle/libs.versions.toml`, used by `build-logic`) | https://github.com/JetBrains/kotlin | The kotlinx.serialization compiler plugin, `org.jetbrains.kotlin.plugin.serialization`, applied by `vesper-config` |
| ch.qos.logback:logback-classic, logback-core 1.5.38 | local (`gradle/libs.versions.toml`, a constraint on the `ktlint` configuration in `vesper.kmp`) | https://github.com/qos-ch/logback | Not a direct dependency: raises the logback that ktlint-cli 1.8.0 runs on past its advisories (T900) |

## Linkers

- **Windows:** the Kotlin/Native `mingwX64` toolchain linker.
- **Linux:** the Kotlin/Native `linuxX64` linker.
- **macOS:** Apple's native linker, used by Kotlin/Native (`macosArm64`).
