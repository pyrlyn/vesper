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

// The C programs in tests/c, built with clang against the host's debug shared library and run under
// ASan+UBSan and under TSan: they call through the header's own prototypes, which the Kotlin tests
// cannot. Windows has no TSan; its host is covered by the C# tests (T17).
val cHost =
    when {
        HostManager.hostIsMac -> "macosArm64"
        HostManager.hostIsLinux -> "linuxX64"
        else -> null
    }
if (cHost != null) {
    val link = tasks.named("linkDebugShared${cHost.replaceFirstChar(Char::uppercase)}")
    val libDir = layout.buildDirectory.dir("bin/$cHost/debugShared")
    val cTests = tasks.register("cTests") { group = "verification" }
    val sanitizers = mapOf("Asan" to "address,undefined", "Tsan" to "thread")
    for (program in listOf("status", "two_threads", "events")) {
        for ((suffix, sanitize) in sanitizers) {
            val name = program.split('_').joinToString("") { it.replaceFirstChar(Char::uppercase) } + suffix
            val exe = layout.buildDirectory.file("ctest/$program-${suffix.lowercase()}")
            val compile =
                tasks.register<Exec>("compileC$name") {
                    dependsOn(link)
                    val source = file("tests/c/$program.c")
                    inputs.file(source)
                    inputs.dir("include")
                    inputs.dir(libDir)
                    outputs.file(exe)
                    val lib = libDir.get().asFile.absolutePath
                    commandLine(
                        "clang",
                        "-std=c11",
                        "-Wall",
                        "-Wextra",
                        "-Werror",
                        "-g",
                        "-fno-omit-frame-pointer",
                        "-fsanitize=$sanitize",
                        "-fno-sanitize-recover=all",
                        "-I",
                        file("include").absolutePath,
                        source.absolutePath,
                        "-L",
                        lib,
                        "-lscull",
                        "-lpthread",
                        "-Wl,-rpath,$lib",
                        "-o",
                        exe.get().asFile.absolutePath,
                    )
                }
            val run =
                tasks.register<Exec>("runC$name") {
                    dependsOn(compile)
                    inputs.file("tests/c/lsan.supp")
                    // Native locks are freed by Kotlin/Native Cleaners, after a collection the test
                    // exits before; lsan.supp says why only they are suppressed.
                    environment("LSAN_OPTIONS", "suppressions=${file("tests/c/lsan.supp").absolutePath}:print_suppressions=0")
                    commandLine(exe.get().asFile.absolutePath)
                }
            cTests { dependsOn(run) }
        }
    }
    tasks.named("check") { dependsOn(cTests) }
}
