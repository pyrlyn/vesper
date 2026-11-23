plugins {
    id("scull.library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(libs.okio)
        }
    }
}
