package scull.harness

import okio.FileSystem
import okio.Path.Companion.toPath
import okio.SYSTEM

/** The variable Gradle sets to the repository's `tests` directory for every native test task. */
public const val TESTS_DIR_VARIABLE: String = "SCULL_TESTS_DIR"

/** The value of the environment variable [name], or null when it is not set. */
public expect fun environment(name: String): String?

/**
 * Reads `tests/<relative>` from the repository. Native test binaries have no resources, so every
 * module's tests read the shared fixtures through this one door.
 */
public fun readFixture(relative: String): String {
    val root = checkNotNull(environment(TESTS_DIR_VARIABLE)) { "$TESTS_DIR_VARIABLE is not set; run the tests through Gradle" }
    return FileSystem.SYSTEM.read(root.toPath() / relative) { readUtf8() }
}
