plugins {
    id("vesper.library")
    id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // ktoml decodes the file into @Serializable types and refuses keys they do not have.
            implementation(libs.ktoml.core)
            implementation(libs.kotlinx.serialization.core)
            implementation(libs.okio)
        }
    }
}
