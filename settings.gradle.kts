pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
    }
}

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

rootProject.name = "vesper"

// The Kotlin/Native core, layered as PLAN.md section 3 says.
include(
    ":vesper-unicode",
    ":vesper-parser",
    ":vesper-grid",
    ":vesper-input",
    // Locks and threads for the modules that run their own (T20).
    ":vesper-sync",
    ":vesper-pty",
    ":vesper-term",
    // PNG decode with a caller-set allocation cap, for the image protocols (T19).
    ":vesper-png",
    // Images and their placements under a byte quota, and the protocol decoders (T19).
    ":vesper-image",
    // A terminal and the child it runs: what each host window holds (T14).
    ":vesper-session",
    // The config file: parse, validate, edit, live reload (T20).
    ":vesper-config",
    // What both apps draw with that no graphics API owns: the glyph atlas and its bitmaps (T17).
    ":vesper-host",
    // The macOS app: Kotlin/Native over AppKit and CoreText (T14).
    ":vesper-macos",
    // The Windows app: Kotlin/Native over Win32, Direct3D 11 and DirectWrite (T17).
    ":vesper-windows",
    // Test and measurement tools; nothing in the shipped library depends on them.
    ":vesper-harness",
    ":vesper-bench",
)
