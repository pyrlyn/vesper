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

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

ktlint {
    version.set(libs.findVersion("ktlint").get().requiredVersion)
}

// ktlint-cli 1.8.0, the latest release, still ships logback 1.3.16, which has
// GHSA-qqpg-mvqg-649v, GHSA-p47f-322f-whfh and GHSA-jhq6-gfmj-v8fx. ktlint runs in every
// `check`, so its classpath takes the patched 1.5 line until a ktlint release does (T900).
dependencies {
    constraints {
        for (alias in listOf("logback-classic", "logback-core")) {
            add("ktlint", libs.findLibrary(alias).get()) {
                because("logback before 1.5.34 has the advisories above")
            }
        }
    }
}

// Native test binaries have no resources; they read the shared fixtures from the repository
// (vesper.harness.readFixture).
val testsDir = rootDir.resolve("tests")

// The CI fuzz job's budget and seed (vesper.harness.fuzzCases). Read through providers so the
// configuration cache knows a change in them, and passed on explicitly rather than trusting the
// daemon's environment, which may be older than this build.
val fuzzSeconds: String? = providers.environmentVariable("VESPER_FUZZ_SECONDS").orNull
val fuzzSeed: String? = providers.environmentVariable("VESPER_FUZZ_SEED").orNull

tasks.withType<KotlinNativeTest>().configureEach {
    environment("VESPER_TESTS_DIR", testsDir.absolutePath)
    // The fixtures are read at run time, so Gradle must be told that editing one reruns the tests.
    inputs.dir(testsDir).withPropertyName("fixtures").withPathSensitivity(PathSensitivity.RELATIVE)
    fuzzSeconds?.let { environment("VESPER_FUZZ_SECONDS", it) }
    fuzzSeed?.let { environment("VESPER_FUZZ_SEED", it) }
    inputs.property("fuzzSeconds", fuzzSeconds.orEmpty())
    inputs.property("fuzzSeed", fuzzSeed.orEmpty())
    if (fuzzSeconds != null && fuzzSeed == null) {
        // A fresh seed draws new inputs on every run, so no earlier result stands for this one.
        outputs.upToDateWhen { false }
        outputs.cacheIf { false }
    }
    // The Windows tests run only on CI, where the log is all there is: a failure shows its message.
    testLogging.exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
}
