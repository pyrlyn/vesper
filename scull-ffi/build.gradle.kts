import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

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

