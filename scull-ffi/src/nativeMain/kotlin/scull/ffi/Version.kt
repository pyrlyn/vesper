@file:OptIn(ExperimentalNativeApi::class)

package scull.ffi

import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.CName

/** `major << 16 | minor` of the C ABI; T10 defines the real header and its first version. */
public const val ABI_VERSION: UInt = 0u

/**
 * The C ABI version this library was built with. A host compares it with the version in its
 * `scull.h` before any other call, so it must stay the first export and never change shape.
 */
@CName("scull_abi_version")
public fun abiVersion(): UInt = ABI_VERSION
