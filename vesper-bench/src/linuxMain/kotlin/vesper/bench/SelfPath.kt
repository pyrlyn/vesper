@file:OptIn(ExperimentalForeignApi::class)

package vesper.bench

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.set
import kotlinx.cinterop.toKString
import platform.posix.PATH_MAX
import platform.posix.readlink

internal actual fun selfPath(): String? =
    memScoped {
        val buffer = allocArray<ByteVar>(PATH_MAX + 1)
        val n = readlink("/proc/self/exe", buffer, PATH_MAX.toULong())
        if (n <= 0) return null
        buffer[n] = 0.toByte()
        buffer.toKString()
    }
