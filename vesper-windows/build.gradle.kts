plugins {
    id("vesper.kmp")
}

// The DirectWrite headers in Kotlin/Native's mingw sysroot are hand-written and do not compile as
// C (a typo, and C++ overloads that collide as struct members). Zig ships mingw-w64's newer ones,
// generated from IDL with a C vtable for every interface, so cinterop reads those five from Zig and
// everything else from the sysroot. Only these are copied: Zig's windows.h would shadow the
// sysroot's and split the Win32 types from the platform.windows library's.
val zigHeaders = layout.buildDirectory.dir("zig-headers")
val copyZigHeaders by tasks.registering(Sync::class) {
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
    // Only the Windows target has code; the others build empty so the module sits in every gate.
    mingwX64 {
        compilations.getByName("main").cinterops.create("directx") {
            includeDirs(zigHeaders)
        }
    }

    sourceSets {
        all { languageSettings.optIn("kotlinx.cinterop.ExperimentalForeignApi") }
    }
}

tasks.matching { it.name == "cinteropDirectxMingwX64" }.configureEach { dependsOn(copyZigHeaders) }
