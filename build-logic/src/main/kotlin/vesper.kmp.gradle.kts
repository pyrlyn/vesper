// Kotlin Multiplatform with Vesper's native targets, ktlint and kotlin.test; applied by every module.
import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jlleitschuh.gradle.ktlint")
}

kotlin {
    macosArm64()
    linuxX64()
    // The Windows host loads the core as a DLL built for this target (PLAN.md section 3).
    mingwX64()

    compilerOptions {
        allWarningsAsErrors.set(true)
    }

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

ktlint {
    version.set(
        extensions
            .getByType<VersionCatalogsExtension>()
            .named("libs")
            .findVersion("ktlint")
            .get()
            .requiredVersion,
    )
}

// Native test binaries have no resources; they read the shared fixtures from the repository
// (vesper.harness.readFixture).
val testsDir = rootDir.resolve("tests")
tasks.withType<KotlinNativeTest>().configureEach {
    environment("VESPER_TESTS_DIR", testsDir.absolutePath)
    // The fixtures are read at run time, so Gradle must be told that editing one reruns the tests.
    inputs.dir(testsDir).withPropertyName("fixtures").withPathSensitivity(PathSensitivity.RELATIVE)
}
