import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    id("vesper.library")
}

kotlin {
    // sys: the system's locks, condition variables and threads.
    targets.withType<KotlinNativeTarget>().configureEach {
        compilations.getByName("main").cinterops { create("sys") }
    }
    sourceSets.all { languageSettings.optIn("kotlinx.cinterop.ExperimentalForeignApi") }
}
