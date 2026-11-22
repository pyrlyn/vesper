import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest

plugins {
    id("scull.library")
}

kotlin {
    sourceSets {
        commonTest.dependencies {
            implementation(libs.okio)
        }
    }
}

// Native test binaries have no resources; they read the shared fixtures from the repository.
val testsDir = rootDir.resolve("tests").absolutePath
tasks.withType<KotlinNativeTest>().configureEach {
    environment("SCULL_TESTS_DIR", testsDir)
}
