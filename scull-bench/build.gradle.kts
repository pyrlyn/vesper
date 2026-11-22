import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    id("scull.kmp")
}

kotlin {
    targets.withType<KotlinNativeTarget>().configureEach {
        binaries.executable {
            entryPoint = "scull.bench.main"
            // The table is written relative to the working directory: the repository root.
            runTask?.workingDir(rootDir)
        }
    }

    // posix_spawn feeds a reference terminal on macOS and Linux; Windows has its own bench in
    // windows/Scull.Bench.
    applyDefaultHierarchyTemplate()
    sourceSets {
        val posixMain by creating { dependsOn(nativeMain.get()) }
        macosMain.get().dependsOn(posixMain)
        linuxMain.get().dependsOn(posixMain)

        commonMain.dependencies {
            implementation(projects.scullHarness)
            implementation(projects.scullParser)
            implementation(projects.scullGrid)
            implementation(libs.okio)
        }
    }
}
