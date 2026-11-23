plugins {
    id("vesper.kmp")
}

kotlin {
    // The pure parts (palette, key map, cell geometry, launch environment) are common, so every
    // runner tests them; only the AppKit code and the app itself are macOS-only.
    macosArm64 {
        binaries.executable {
            entryPoint = "vesper.macos.main"
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(projects.vesperInput)
            implementation(projects.vesperGrid)
            implementation(projects.vesperTerm)
        }
        macosMain.dependencies {
            implementation(projects.vesperSession)
            implementation(projects.vesperUnicode)
        }
        all { languageSettings.optIn("kotlinx.cinterop.ExperimentalForeignApi") }
    }
}

// Where the first-light test leaves its rendering, so a run's screenshot can be looked at.
tasks.withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest>().configureEach {
    val reports = layout.buildDirectory.dir("reports/first-light")
    environment("VESPER_REPORTS_DIR", reports.get().asFile.absolutePath)
    doFirst { reports.get().asFile.mkdirs() }
}
