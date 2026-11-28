plugins {
    id("vesper.kmp")
}

// The DirectWrite headers in Kotlin/Native's mingw sysroot are hand-written and do not compile as
// C (a typo, and C++ overloads that collide as struct members). Zig ships mingw-w64's newer ones,
// generated from IDL with a C vtable for every interface, so cinterop reads those five from Zig and
// everything else from the sysroot. Only these are copied: Zig's windows.h would shadow the
// sysroot's and split the Win32 types from the platform.windows library's.
val zigHeaders = layout.buildDirectory.dir("zig-headers")
val copyZigHeaders =
    tasks.register<Sync>("copyZigHeaders") {
        val libDir =
            providers.exec { commandLine("zig", "env") }.standardOutput.asText.map { env ->
                val path =
                    Regex("""\.lib_dir = "((?:[^"\\]|\\.)*)"""").find(env)?.groupValues?.get(1)
                        ?: error("`zig env` printed no lib_dir; is Zig from mise.toml installed?")
                path.replace("\\\\", "\\")
            }
        from(libDir.map { "$it/libc/include/any-windows-any" }) {
            include("dcommon.h", "dwrite.h", "dwrite_1.h", "dwrite_2.h", "dwrite_3.h")
        }
        into(zigHeaders)
    }

kotlin {
    // The input policy is common code, so every runner tests it; the Win32 code is Windows-only.
    mingwX64 {
        compilations.getByName("main").cinterops.create("directx") {
            includeDirs(zigHeaders)
        }
        binaries.executable {
            entryPoint = "vesper.windows.main"
            // A GUI program: no console window opens beside it.
            linkerOpts("-Wl,--subsystem,windows")
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(projects.vesperInput)
        }
        mingwMain.dependencies {
            implementation(projects.vesperConfig)
            implementation(projects.vesperHost)
            implementation(projects.vesperSession)
            implementation(projects.vesperUnicode)
        }
        mingwTest.dependencies { implementation(libs.okio) }
        all { languageSettings.optIn("kotlinx.cinterop.ExperimentalForeignApi") }
    }
}

tasks.matching { it.name == "cinteropDirectxMingwX64" }.configureEach { dependsOn(copyZigHeaders) }
