plugins {
    id("vesper.library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.vesperParser)
            api(projects.vesperGrid)
            api(projects.vesperInput)
            api(projects.vesperUnicode)
            // Images live beside the grid, and the frame hands their pixels out.
            api(projects.vesperImage)
        }
        commonTest.dependencies {
            // Reads the conformance cases in tests/fixtures.
            implementation(projects.vesperHarness)
        }
    }
}
