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
    ":vesper-pty",
    ":vesper-term",
    // PNG decode with a caller-set allocation cap, for the image protocols (T19).
    ":vesper-png",
    // Images and their placements under a byte quota, and the protocol decoders (T19).
    ":vesper-image",
    // A terminal and the child it runs: what each host window holds (T14).
    ":vesper-session",
    // The macOS app: Kotlin/Native over AppKit and CoreText (T14).
    ":vesper-macos",
    // Test and measurement tools; nothing in the shipped library depends on them.
    ":vesper-harness",
    ":vesper-bench",
)
