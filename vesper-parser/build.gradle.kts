plugins {
    id("vesper.library")
}

kotlin {
    sourceSets {
        commonTest.dependencies {
            // fuzzCases: the seeded corpus that the CI fuzz job runs for a time budget.
            implementation(projects.vesperHarness)
        }
    }
}
