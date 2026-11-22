// Core modules: everything public must be declared public on purpose, since it can reach the C ABI.
plugins {
    id("scull.kmp")
}

kotlin {
    explicitApi()
}
