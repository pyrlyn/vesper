import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    id("vesper.kmp")
}

kotlin {
    targets.withType<KotlinNativeTarget>().configureEach {
        binaries.executable {
            entryPoint = "vesper.bench.main"
            // The table is written relative to the working directory: the repository root.
            runTask?.workingDir(rootDir)
        }
    }

    // posix_spawn feeds a reference terminal on macOS and Linux; Windows has no headless feed yet.
    applyDefaultHierarchyTemplate()
    sourceSets {
        val posixMain by creating { dependsOn(nativeMain.get()) }
        macosMain.get().dependsOn(posixMain)
        linuxMain.get().dependsOn(posixMain)

        commonMain.dependencies {
            implementation(projects.vesperHarness)
            implementation(projects.vesperParser)
            implementation(projects.vesperGrid)
            implementation(projects.vesperTerm)
            implementation(libs.okio)
        }
    }
}
