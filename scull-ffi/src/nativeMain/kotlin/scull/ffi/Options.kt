package scull.ffi

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CStructVar
import kotlinx.cinterop.MemScope
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.get
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.value
import platform.posix.memcpy
import platform.posix.memset
import scull.ffi.c.TT_INVALID
import scull.ffi.c.tt_str
import scull.ffi.c.tt_term_options

// Reading what the host passes in. Every struct starts with its size as the host knows it: the core
// reads only that much and sees zeros past it, so an older host's shorter struct still works and a
// newer host's longer one is read as far as this build knows.

/** Bytes of `tt_term_options` that `tt_term_new` needs: up to and including `scrollback`. */
internal const val TERM_OPTIONS_MIN = 16

/** Thrown for a call the core refuses: the guard answers [status] and poisons nothing. */
internal open class Refused(
    val status: UInt,
) : Exception()

/** Input a call refuses with `TT_INVALID`. */
internal class Invalid : Refused(TT_INVALID)

/** The `struct_size` a host wrote at [ptr]. */
internal fun structSize(ptr: CPointer<*>): Int =
    ptr
        .reinterpret<UIntVar>()
        .pointed.value
        .coerceAtMost(Int.MAX_VALUE.toUInt())
        .toInt()

/** A zeroed [T] in [scope] holding the first `min(struct_size, sizeof)` bytes of the host's struct; null when it is shorter than [min]. */
internal inline fun <reified T : CStructVar> MemScope.readStruct(
    ptr: CPointer<T>,
    min: Int,
): T? {
    val size = structSize(ptr)
    if (size < min) return null
    val local = alloc<T>()
    val full = sizeOf<T>()
    memset(local.ptr, 0, full.toULong())
    memcpy(local.ptr, ptr, minOf(size.toLong(), full).toULong())
    return local
}

/** Copies `min(struct_size, sizeof)` bytes of [local] over the host's struct at [out] and records that count in its `struct_size`; false when the host's is shorter than [min]. */
internal inline fun <reified T : CStructVar> writeStruct(
    local: T,
    out: CPointer<T>,
    min: Int,
): Boolean {
    val size = structSize(out)
    if (size < min) return false
    val count = minOf(size.toLong(), sizeOf<T>())
    local.ptr
        .reinterpret<UIntVar>()
        .pointed.value = count.toUInt()
    memcpy(out, local.ptr, count.toULong())
    return true
}

/** A byte count from C that a Kotlin array can hold. */
internal fun ULong.toLength(): Int = if (this > Int.MAX_VALUE.toULong()) throw Invalid() else toInt()

/** The host's [len] bytes at [ptr], copied; `NULL` is allowed only for none. */
internal fun bytesAt(
    ptr: CPointer<*>?,
    len: ULong,
): ByteArray {
    val count = len.toLength()
    if (count == 0) return ByteArray(0)
    if (ptr == null) throw Invalid()
    return ptr.reinterpret<ByteVar>().readBytes(count)
}

/** [bytes] as text; bytes that are not UTF-8 are refused. */
internal fun utf8(bytes: ByteArray): String =
    try {
        bytes.decodeToString(throwOnInvalidSequence = true)
    } catch (_: CharacterCodingException) {
        throw Invalid()
    }

/** The UTF-8 text of [str]; text that is not UTF-8 is refused. */
internal fun tt_str.text(): String = utf8(bytesAt(ptr, len))

/** [count] strings of an array the host owns. */
internal fun texts(
    array: CPointer<tt_str>?,
    count: ULong,
): List<String> {
    val n = count.toLength()
    if (n == 0) return emptyList()
    if (array == null) throw Invalid()
    return List(n) { array[it].text() }
}
