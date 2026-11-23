import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.konan.target.HostManager

plugins {
    id("scull.library")
}

kotlin {
    targets.withType<KotlinNativeTarget>().configureEach {
        // The Swift package links the static library; the C# host loads the shared one.
        binaries {
            sharedLib { baseName = "scull" }
            staticLib { baseName = "scull" }
        }
        compilations.getByName("main").cinterops {
            create("scull") { includeDirs(project.file("include")) }
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(projects.scullTerm)
            implementation(projects.scullPty)
        }
        all {
            languageSettings.optIn("kotlinx.cinterop.ExperimentalForeignApi")
            languageSettings.optIn("kotlin.experimental.ExperimentalNativeApi")
            languageSettings.optIn("kotlin.concurrent.atomics.ExperimentalAtomicApi")
        }
    }
}

// The host programs in tests/zig, built against the host's debug shared library and run under ASan
// and under TSan: they call through the header's own prototypes, which the Kotlin tests cannot.
// Zig instruments its own code for TSan but has no ASan, so the ASan build is a Zig object that
// clang links with the ASan runtime; that runtime then owns malloc and free, which is what the leak,
// double-free and bad-free checks on the library need. Windows has no TSan; its host is covered by
// the C# tests (T17).
val unixHost =
    when {
        HostManager.hostIsMac -> "macosArm64"
        HostManager.hostIsLinux -> "linuxX64"
        else -> null
    }
if (unixHost != null) {
    val link = tasks.named("linkDebugShared${unixHost.replaceFirstChar(Char::uppercase)}")
    val lib =
        layout.buildDirectory
            .dir("bin/$unixHost/debugShared")
            .get()
            .asFile.absolutePath
    val out =
        layout.buildDirectory
            .dir("hosttest")
            .get()
            .asFile
    val header = out.resolve("scull.zig").absolutePath
    val sources = fileTree("tests/zig") { include("*.zig") }
    val lsan = file("tests/zig/lsan.supp")
    val tsan = file("tests/zig/tsan.supp")

    // Zig 0.17 dropped @cImport: the header becomes a Zig module once, before the programs build.
    // The shell writes it to the file because zig 0.17.1 spins forever when its stdout is a pipe,
    // which is what Exec would give it.
    val translate =
        tasks.register<Exec>("translateHeaderToZig") {
            inputs.dir("include")
            outputs.file(header)
            doFirst { out.mkdirs() }
            commandLine(
                "sh",
                "-c",
                "exec zig translate-c -lc -I \"$1\" \"$2\" > \"$3\"",
                "sh",
                file("include").absolutePath,
                file("include/scull.h").absolutePath,
                header,
            )
        }
    // Zig builds for the running macOS by default, a point release past the SDK that clang links
    // against, and ld warns about every object; any version the SDK covers keeps it quiet.
    val objectTarget = if (HostManager.hostIsMac) listOf("-target", "aarch64-macos.14.0") else emptyList()
    // Debug builds for x86_64 default to Zig's own backend, whose programs crash at start-up under
    // the amd64 emulation Linux runs in on an Apple Silicon host; LLVM's run there and everywhere.
    val debug = listOf("-O", "Debug", "-fllvm")

    val hostTests = tasks.register("hostTests") { group = "verification" }
    for (program in listOf("status", "two_threads", "events")) {
        val name = program.split('_').joinToString("") { it.replaceFirstChar(Char::uppercase) }
        val path = { suffix: String -> out.resolve("$program$suffix").absolutePath }
        val module = listOf("--dep", "scull", "-Mroot=${file("tests/zig/$program.zig").absolutePath}", "-Mscull=$header")
        val buildTsan =
            tasks.register<Exec>("buildHost${name}Tsan") {
                dependsOn(link)
                inputs.files(sources, translate)
                inputs.dir(lib)
                outputs.file(path("-tsan"))
                commandLine(
                    listOf("zig", "build-exe") + debug + listOf("-fsanitize-thread", "-lc", "-L", lib, "-lscull", "-rpath", lib) +
                        module + "-femit-bin=${path("-tsan")}",
                )
            }
        val compileAsan =
            tasks.register<Exec>("compileHost${name}Asan") {
                inputs.files(sources, translate)
                outputs.file(path(".o"))
                commandLine(listOf("zig", "build-obj") + debug + listOf("-lc") + objectTarget + module + "-femit-bin=${path(".o")}")
            }
        val buildAsan =
            tasks.register<Exec>("buildHost${name}Asan") {
                dependsOn(link)
                inputs.files(compileAsan)
                inputs.dir(lib)
                outputs.file(path("-asan"))
                commandLine(
                    "clang",
                    "-g",
                    "-fsanitize=address",
                    path(".o"),
                    "-L",
                    lib,
                    "-lscull",
                    "-lpthread",
                    "-Wl,-rpath,$lib",
                    "-o",
                    path("-asan"),
                )
            }
        for ((suffix, build) in listOf("Asan" to buildAsan, "Tsan" to buildTsan)) {
            val run =
                tasks.register<Exec>("runHost$name$suffix") {
                    inputs.files(build, lsan, tsan)
                    // Native locks are freed by Kotlin/Native Cleaners, after a collection the test
                    // exits before, and the runtime's collector is not instrumented for TSan; the
                    // .supp files say why only those reports are suppressed.
                    environment("LSAN_OPTIONS", "suppressions=${lsan.absolutePath}:print_suppressions=0")
                    environment("TSAN_OPTIONS", "suppressions=${tsan.absolutePath}")
                    commandLine(path("-${suffix.lowercase()}"))
                }
            hostTests { dependsOn(run) }
        }
    }
    tasks.named("check") { dependsOn(hostTests) }
}
