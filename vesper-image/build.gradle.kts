plugins {
    id("vesper.library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.vesperPng)
        }
    }
}
