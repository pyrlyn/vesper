plugins {
    id("scull.library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.scullParser)
            api(projects.scullGrid)
            api(projects.scullInput)
            api(projects.scullUnicode)
            // Images live beside the grid, and the frame hands their pixels out.
            api(projects.scullImage)
        }
        commonTest.dependencies {
            // Reads the conformance cases in tests/fixtures.
            implementation(projects.scullHarness)
        }
    }
}
