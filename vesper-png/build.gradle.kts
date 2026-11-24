plugins {
    id("vesper.library")
}

kotlin {
    sourceSets {
        all { languageSettings.optIn("kotlinx.cinterop.ExperimentalForeignApi") }
    }
}
