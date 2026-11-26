// What both apps draw with that no graphics API owns: the palette, the frame turned into quads, the
// glyph atlas's layout and cache, and the bitmaps that fill it.
plugins {
    id("vesper.library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.vesperTerm)
            implementation(projects.vesperUnicode)
        }
    }
}
