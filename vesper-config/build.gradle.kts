import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest

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
            // Chords are vesper-input's keys and modifiers, which hosts already build from key events.
            api(projects.vesperInput)
        }
        commonTest.dependencies {
            // Finds docs/config.schema.json beside the tests directory.
            implementation(projects.vesperHarness)
            // Renders the JSON Schema the staleness test compares with the committed file.
            implementation(libs.kotlinx.serialization.json)
        }
    }
}

// SchemaTest reads the committed schema at run time, so editing it must rerun the tests; with
// VESPER_WRITE_SCHEMA set it rewrites the file from the model instead of failing on drift.
val writeSchema: String? = providers.environmentVariable("VESPER_WRITE_SCHEMA").orNull

tasks.withType<KotlinNativeTest>().configureEach {
    inputs
        .file(rootDir.resolve("docs/config.schema.json"))
        .withPropertyName("schema")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.property("writeSchema", writeSchema.orEmpty())
    writeSchema?.let { environment("VESPER_WRITE_SCHEMA", it) }
}
