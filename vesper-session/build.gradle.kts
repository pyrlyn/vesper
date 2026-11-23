plugins {
    id("vesper.library")
}

kotlin {
    applyDefaultHierarchyTemplate()
    sourceSets {
        // The PTY's API is native-only, so the session is too.
        nativeMain.dependencies {
            api(projects.vesperTerm)
            api(projects.vesperPty)
        }
        // The tests drive a Unix shell; ConPTY itself is tested in vesper-pty.
        val posixTest by creating { dependsOn(nativeTest.get()) }
        macosTest.get().dependsOn(posixTest)
        linuxTest.get().dependsOn(posixTest)
    }
}
