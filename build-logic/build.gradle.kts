plugins {
    `kotlin-dsl`
}

dependencies {
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.ktlint.gradle.plugin)
    implementation(libs.kotlin.serialization.plugin)
}
