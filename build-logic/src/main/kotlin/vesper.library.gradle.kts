// Core modules: everything public must be declared public on purpose, since the hosts call it directly.
plugins {
    id("vesper.kmp")
}

kotlin {
    explicitApi()
}
