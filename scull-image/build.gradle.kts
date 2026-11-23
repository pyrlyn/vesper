plugins {
    id("scull.library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.scullPng)
        }
    }
}
