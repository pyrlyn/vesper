plugins {
    id("vesper.library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(libs.okio)
        }
    }
}
