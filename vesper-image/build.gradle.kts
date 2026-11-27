plugins {
    id("vesper.library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.vesperPng)
        }
        commonTest.dependencies {
            // fuzzCases: the seeded corpora that the CI fuzz job runs for a time budget.
            implementation(projects.vesperHarness)
        }
    }
}
