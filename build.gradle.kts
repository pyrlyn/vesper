// build-logic carries every Gradle plugin the modules use. Naming one of its plugins here puts
// that single classpath on the root, so each module applies Kotlin, Compose and ktlint by id and
// the Kotlin Gradle plugin is loaded once.
plugins {
    id("scull.kmp") apply false
}
