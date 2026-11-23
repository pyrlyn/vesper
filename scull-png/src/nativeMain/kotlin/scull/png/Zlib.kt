package scull.png

import kotlinx.cinterop.Pinned
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.free
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.pin
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import platform.zlib.ZLIB_VERSION
import platform.zlib.Z_BUF_ERROR
import platform.zlib.Z_DEFAULT_COMPRESSION
import platform.zlib.Z_FINISH
import platform.zlib.Z_NO_FLUSH
import platform.zlib.Z_OK
import platform.zlib.Z_STREAM_END
import platform.zlib.deflateEnd
import platform.zlib.deflateInit_
import platform.zlib.inflateEnd
import platform.zlib.inflateInit_
import platform.zlib.z_stream
import platform.zlib.deflate as zdeflate
import platform.zlib.inflate as zinflate

// zlib from the Kotlin/Native platform libraries: the system's on macOS and Linux, a static copy on
// Windows. Its window and state are a fixed few tens of KiB whatever the image.

// zlib's crc32 and deflateBound take and return uLong, 32 bits on Windows and 64 elsewhere, which
// shared native code cannot convert to; so the CRC is computed here and the bound is compressBound's.

private val CRC_TABLE =
    IntArray(256) { n ->
        var c = n
        repeat(8) { c = if (c and 1 != 0) 0xEDB88320.toInt() xor (c ushr 1) else c ushr 1 }
        c
    }

/** CRC-32 of [len] bytes of [bytes] from [from]. */
internal fun crc32(
    bytes: ByteArray,
    from: Int,
    len: Int,
): UInt {
    var c = -1
    for (i in from until from + len) c = CRC_TABLE[(c xor bytes[i].toInt()) and 0xFF] xor (c ushr 8)
    return c.inv().toUInt()
}

/** [raw] as a zlib stream. */
internal fun deflate(raw: ByteArray): ByteArray {
    val stream = nativeHeap.alloc<z_stream>()
    try {
        check(deflateInit_(stream.ptr, Z_DEFAULT_COMPRESSION, ZLIB_VERSION, sizeOf<z_stream>().toInt()) == Z_OK)
        // zlib's compressBound: the worst case of stored blocks plus the header and trailer.
        val n = raw.size.toLong()
        val out = ByteArray((n + (n shr 12) + (n shr 14) + (n shr 25) + 13).toInt())
        raw.usePinned { input ->
            out.usePinned { output ->
                stream.next_in = if (raw.isEmpty()) null else input.addressOf(0).reinterpret()
                stream.avail_in = raw.size.convert()
                stream.next_out = output.addressOf(0).reinterpret()
                stream.avail_out = out.size.convert()
                check(zdeflate(stream.ptr, Z_FINISH) == Z_STREAM_END)
            }
        }
        val left = stream.avail_out.convert<Long>().toInt()
        return out.copyOf(out.size - left)
    } finally {
        deflateEnd(stream.ptr)
        nativeHeap.free(stream)
    }
}

/**
 * Inflates the image data, the IDAT chunks from [first] on, on demand: the decoder asks for one
 * scanline at a time, so the raw image is never held whole and data past it is never inflated.
 */
internal class Inflater(
    private val chunks: Chunks,
    first: Chunk,
) : AutoCloseable {
    private val stream = nativeHeap.alloc<z_stream>()
    private val input: Pinned<ByteArray> = chunks.png.pin()
    private var next: Chunk? = first

    init {
        if (inflateInit_(stream.ptr, ZLIB_VERSION, sizeOf<z_stream>().toInt()) != Z_OK) {
            input.unpin()
            nativeHeap.free(stream)
            throw PngException("zlib failed to start")
        }
    }

    /** Fills the first [len] bytes of [out] with image data. */
    fun fill(
        out: ByteArray,
        len: Int,
    ) {
        out.usePinned { pinned ->
            stream.next_out = pinned.addressOf(0).reinterpret()
            stream.avail_out = len.convert()
            while (stream.avail_out.convert<Long>() > 0) {
                if (stream.avail_in.convert<Long>() == 0L) feedNext()
                when (zinflate(stream.ptr, Z_NO_FLUSH)) {
                    Z_OK -> {}

                    // No progress until more input arrives; the next round feeds it.
                    Z_BUF_ERROR -> {
                        if (stream.avail_in.convert<Long>() != 0L) throw PngException("corrupt image data")
                    }

                    Z_STREAM_END -> {
                        if (stream.avail_out.convert<Long>() > 0) throw PngException("image data ends early")
                    }

                    else -> {
                        throw PngException("corrupt image data")
                    }
                }
            }
        }
    }

    /** Points zlib at the next IDAT chunk's data. */
    private fun feedNext() {
        val chunk = next ?: chunks.next()?.takeIf { it.type == IDAT } ?: throw PngException("image data ends early")
        next = null
        chunks.verify(chunk)
        if (chunk.length == 0) return
        stream.next_in = input.addressOf(chunk.start).reinterpret()
        stream.avail_in = chunk.length.convert()
    }

    override fun close() {
        inflateEnd(stream.ptr)
        input.unpin()
        nativeHeap.free(stream)
    }
}
