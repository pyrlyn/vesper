package vesper.windows

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.alloc
import kotlinx.cinterop.invoke
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import platform.posix._GUID
import platform.windows.IUnknown

/** A COM call that failed, with the HRESULT it returned. */
class ComException(
    val call: String,
    val hresult: Int,
) : RuntimeException("$call failed: HRESULT 0x${hresult.toUInt().toString(16).padStart(8, '0')}")

/** Throws unless [hresult] reports success; COM's failures are the negative values. */
internal fun checkHr(
    hresult: Int,
    call: String,
) {
    if (hresult < 0) throw ComException(call, hresult)
}

/** Releases a COM object through IUnknown, whatever interface [pointer] is typed as. */
internal fun release(pointer: CPointer<*>?) {
    val unknown = pointer?.reinterpret<IUnknown>() ?: return
    unknown.pointed.lpVtbl!!
        .pointed.Release!!
        .invoke(unknown)
}

/**
 * An interface id that lives as long as the process, as COM takes ids by pointer. The DirectWrite
 * ids are in no mingw import library, so they are spelled out from the headers.
 */
internal fun interfaceId(
    data1: UInt,
    data2: UShort,
    data3: UShort,
    data4: ULong,
): CPointer<_GUID> {
    val guid = nativeHeap.alloc<_GUID>()
    guid.Data1 = data1
    guid.Data2 = data2
    guid.Data3 = data3
    // Data4 is stored in the order the id is written, most significant byte first.
    for (i in 0 until 8) guid.Data4[i] = (data4 shr (56 - 8 * i)).toUByte()
    return guid.ptr
}
