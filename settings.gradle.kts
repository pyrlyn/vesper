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
    ":scull-unicode",
    ":scull-parser",
    ":scull-grid",
    ":scull-input",
    ":scull-pty",
    ":scull-term",
    // PNG decode with a caller-set allocation cap, for the image protocols (T19).
    ":scull-png",
    // Images and their placements under a byte quota, and the protocol decoders (T19).
    ":scull-image",
    // Test and measurement tools; nothing in the shipped library depends on them.
    ":scull-harness",
    ":scull-bench",
)
