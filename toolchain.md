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
| clang | system (Xcode Command Line Tools; the `clang` package on Linux, preinstalled on the CI image) | Builds `scull-ffi/tests/c` against the shared library and runs it under ASan+UBSan and TSan (`:scull-ffi:cTests`, part of `check` on macOS and Linux) | https://github.com/llvm/llvm-project |
| Xcode (Swift 6.4, SwiftPM, AppKit/SwiftUI SDK) | Mac App Store or developer.apple.com (27.0) | Build and test the macOS app in `macos/` (arm64 only) | https://developer.apple.com/xcode/ |
| codesign | system (Xcode) | Ad-hoc signs `Scull.app` so it launches locally | https://developer.apple.com/documentation/security/code-signing-services |
| .NET SDK (`dotnet`, C# compiler, MSBuild) | https://dot.net (pinned to 10.0.401 in `windows/global.json`; CI: `actions/setup-dotnet`) | Build and test the C# projects in `windows/` | https://github.com/dotnet/sdk |
| Direct3D 11 (with WARP), DirectWrite, `d3dcompiler_47.dll` | ship with Windows (whether the last is guaranteed: `research.md` §9) | `windows/Scull.Render`: draws frames, rasterises glyphs, compiles the HLSL at start-up; WARP renders the tests with no GPU | https://learn.microsoft.com/en-us/windows/win32/direct3d11/atoc-dx-graphics-direct3d-11 |

The PTY bindings (POSIX PTY, ConPTY) need no package: `scull-pty` declares them in its own cinterop `.def` files, compiled by the clang that ships with Kotlin/Native (`plan/T8/task.md` says why). A fuzz/property-test runner is chosen and recorded here in T25.

## Kotlin/Native platform libraries

| Package | Where | Source | Why here |
| --- | --- | --- | --- |
| `platform.zlib` | local (`scull-png`; ships with the Kotlin/Native distribution, links the system `libz` on macOS and Linux and a static copy on `mingwX64`) | https://github.com/madler/zlib | Inflate, deflate and CRC-32 for the PNG decoder and its fixture encoder |

## Gradle

| Package | Where | Source | Why here |
| --- | --- | --- | --- |
| org.jetbrains.kotlin:kotlin-gradle-plugin 2.4.21 | local (`gradle/libs.versions.toml`, used by `build-logic`) | https://github.com/JetBrains/kotlin | Kotlin Multiplatform and the Kotlin/Native compiler for every module |
| org.jlleitschuh.gradle:ktlint-gradle 14.2.0 | local (`gradle/libs.versions.toml`, used by `build-logic`) | https://github.com/JLLeitschuh/ktlint-gradle | Runs ktlint 1.8.0 as part of `check` |
| org.jetbrains.kotlin:kotlin-test | local (every module's `commonTest`, version from the Kotlin plugin) | https://github.com/JetBrains/kotlin | Test API of the native tests |
| com.squareup.okio:okio 3.18.2 | local (`scull-harness`, `scull-bench`) | https://github.com/square/okio | Multiplatform file access: the harness reads the golden frames and the conformance fixtures in `tests/`, the bench writes `docs/benchmarks/baseline.md`; Kotlin/Native has no `java.io` |

## Bundled files

| Package | Where | Source | Why here |
| --- | --- | --- | --- |
| Symbols Nerd Font Mono 3.5.1 (MIT, `NerdFontsSymbolsOnly.tar.xz`) | local (`macos/Resources/`, also shipped by `windows/Scull.Render` under `Fonts/`) | https://github.com/ryanoasis/nerd-fonts/releases/tag/v3.5.1 | Powerline separators and Nerd Font icons in shell prompts when the face lacks them, on macOS and Windows |

## NuGet

| Package | Where | Source | Why here |
| --- | --- | --- | --- |
| MSTest | local (`windows/Scull.Core.Tests`, `windows/Scull.Render.Tests`) | https://github.com/microsoft/testfx | Test framework of the C# core; the default of `dotnet new mstest` (the `xunit` template pins the deprecated xunit 2.9.3) |
| Microsoft.Windows.CsWin32 | local (`windows/Scull.Render` and `windows/Scull.App`, exactly 0.3.358; build-time source generator, nothing ships) | https://github.com/microsoft/CsWin32 | Direct3D 11, DXGI, DirectWrite, TSF and UIA from C# as blittable structs (`allowMarshaling: false`); only the APIs in `NativeMethods.txt` are generated; the comparison is in `research.md` §6.1 |
| Microsoft.WindowsAppSDK | local (`windows/Scull.App`, 2.5.1, self-contained: the runtime ships beside the executable) | https://github.com/microsoft/WindowsAppSDK | WinUI 3 for the Windows shell: the window and the `SwapChainPanel` the renderer's swap chain is shown in |

## Linkers

- **Windows:** the Kotlin/Native `mingwX64` toolchain linker for the core library; MSVC for the C# host.
- **Linux:** the Kotlin/Native `linuxX64` linker.
- **macOS:** Apple's native linker, used both by Kotlin/Native (`macosArm64`) and by the Swift package.
