plugins {
    id("scull.library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.scullParser)
            api(projects.scullGrid)
            api(projects.scullInput)
        }
    }
}
