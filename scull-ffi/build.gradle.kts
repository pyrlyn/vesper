import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    id("scull.library")
}

kotlin {
    // The Swift package links the static library; the C# host loads the shared one.
    targets.withType<KotlinNativeTarget>().configureEach {
        binaries {
            sharedLib { baseName = "scull" }
            staticLib { baseName = "scull" }
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(projects.scullTerm)
            implementation(projects.scullPty)
        }
    }
}
