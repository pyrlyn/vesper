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

    // macOS and Linux launch the reference terminals with fork and exec; Windows launches none.
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
