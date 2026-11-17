import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    jvmToolchain(21)
    jvm()

    compilerOptions {
        optIn.addAll(
            "androidx.compose.ui.text.ExperimentalTextApi",
            "androidx.compose.ui.ExperimentalComposeUiApi",
            "androidx.compose.foundation.ExperimentalFoundationApi",
        )
    }

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }
        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutines.swing)
            implementation(libs.pty4j)
            implementation(libs.oshi.core)
            implementation(libs.anthropic.java)
        }
        jvmTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

compose.desktop {
    application {
        mainClass = "app.vesper.MainKt"
        jvmArgs += listOf("-Dapple.awt.application.appearance=system")

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb, TargetFormat.Rpm)
            packageName = "Vesper"
            packageVersion = "0.1.0"
            description = "A modern block-based terminal"
            vendor = "Vesper"
            modules("java.management", "jdk.unsupported")

            macOS {
                bundleID = "app.vesper.terminal"
                dockName = "Vesper"
            }
            windows {
                menuGroup = "Vesper"
                upgradeUuid = "8b7f3c1e-5d0a-4e7b-9a62-3f1c2d9e4b70"
            }
            linux {
                packageName = "vesper"
            }
        }
    }
}
