package vesper.harness

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv

@OptIn(ExperimentalForeignApi::class)
public actual fun environment(name: String): String? = getenv(name)?.toKString()
