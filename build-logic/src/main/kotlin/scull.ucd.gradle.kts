// Tasks that generate scull-unicode's tables from the pinned Unicode data files (scull/ucd/Ucd.kt).
// Kept out of `check`: an offline local gate should not need the Unicode host. T25 runs checkUcd in CI.
import scull.ucd.UcdTask

tasks.register<UcdTask>("generateUcd") {
    group = "unicode"
    description = "Regenerates the Unicode tables and the grapheme-break cases from the pinned data files."
    check.set(false)
    cacheDir.set(layout.buildDirectory.dir("ucd"))
    moduleDir.set(layout.projectDirectory)
}

tasks.register<UcdTask>("checkUcd") {
    group = "verification"
    description = "Fails when the committed Unicode tables differ from what the pinned data files produce."
    check.set(true)
    cacheDir.set(layout.buildDirectory.dir("ucd"))
    moduleDir.set(layout.projectDirectory)
}
