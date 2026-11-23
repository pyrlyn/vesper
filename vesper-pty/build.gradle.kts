import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.konan.target.Family

plugins {
    id("vesper.library")
}

kotlin {
    // sys: locks and threads everywhere; posix or conpty: the pseudo-terminal of the target.
    targets.withType<KotlinNativeTarget>().configureEach {
        compilations.getByName("main").cinterops {
            create("sys")
            create(if (konanTarget.family == Family.MINGW) "conpty" else "posix")
        }
    }

    applyDefaultHierarchyTemplate()
    sourceSets {
        val posixMain by creating { dependsOn(nativeMain.get()) }
        macosMain.get().dependsOn(posixMain)
        linuxMain.get().dependsOn(posixMain)
        val posixTest by creating { dependsOn(nativeTest.get()) }
        macosTest.get().dependsOn(posixTest)
        linuxTest.get().dependsOn(posixTest)

        all { languageSettings.optIn("kotlinx.cinterop.ExperimentalForeignApi") }
    }
}

tasks.withType<org.gradle.api.tasks.testing.AbstractTestTask>().configureEach {
    testLogging {
        events("failed", "standard_error")
        showExceptions = true
        showCauses = true
        showStackTraces = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
