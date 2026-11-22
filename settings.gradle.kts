pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
        google()
    }
}

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

rootProject.name = "vesper"

// The Compose desktop app.
include(":composeApp")

// The Kotlin/Native core: seven modules, layered as PLAN.md section 3 says.
include(
    ":scull-unicode",
    ":scull-parser",
    ":scull-grid",
    ":scull-input",
    ":scull-pty",
    ":scull-term",
    ":scull-ffi",
    // Test and measurement tools; nothing in the shipped library depends on them.
    ":scull-harness",
    ":scull-bench",
)
