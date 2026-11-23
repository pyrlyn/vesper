package scull.ffi

import scull.ffi.c.TT_ABI_VERSION
import kotlin.native.CName

/** `major << 16 | minor` of the C ABI, from `scull.h`. */
public val ABI_VERSION: UInt = TT_ABI_VERSION.toUInt()

private const val MAJOR_SHIFT = 16
private const val MINOR_MASK = 0xFFFFu

/**
 * The C ABI version this library was built with. A host compares it with the version in its
 * `scull.h` before any other call, so it must stay the first export and never change shape.
 */
@CName("tt_abi_version")
public fun abiVersion(): UInt = ABI_VERSION

/** Whether a host built against [host] may use this library: the same major, and while it is 0 the same minor. */
internal fun abiCompatible(host: UInt): Boolean {
    val major = ABI_VERSION shr MAJOR_SHIFT
    if (host shr MAJOR_SHIFT != major) return false
    val hostMinor = host and MINOR_MASK
    val minor = ABI_VERSION and MINOR_MASK
    return if (major == 0u) hostMinor == minor else minor >= hostMinor
}
