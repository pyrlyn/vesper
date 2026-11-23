plugins {
    id("scull.library")
}

kotlin {
    sourceSets {
        all { languageSettings.optIn("kotlinx.cinterop.ExperimentalForeignApi") }
    }
}
