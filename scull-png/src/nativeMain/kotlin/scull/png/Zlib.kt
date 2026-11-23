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

/** zlib streams, for the PNG encoder and for callers whose data arrives deflated (kitty's `o=z`). */
public object Zlib {
    /** [raw] as a zlib stream. */
    public fun deflate(raw: ByteArray): ByteArray {
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
     * The zlib stream [data] inflated, at most [maxBytes] of it. The output grows as zlib fills
     * it, so a small bomb fails at the cap instead of allocating what it claims. Bytes after the
     * end of the stream are ignored.
     *
     * @throws ZlibTooLargeException when the stream inflates past [maxBytes].
     * @throws ZlibException when it is corrupt or ends early.
     */
    public fun inflate(
        data: ByteArray,
        maxBytes: Int,
    ): ByteArray {
        require(maxBytes in 0 until MAX_OUTPUT) { "cap of $maxBytes bytes" }
        val stream = nativeHeap.alloc<z_stream>()
        if (inflateInit_(stream.ptr, ZLIB_VERSION, sizeOf<z_stream>().toInt()) != Z_OK) {
            nativeHeap.free(stream)
            throw ZlibException("zlib failed to start")
        }
        try {
            // One byte past the cap tells a stream that ends exactly at it from one that goes on.
            val limit = maxBytes + 1
            var out = ByteArray(minOf(limit.toLong(), maxOf(data.size.toLong() * 4, 256)).toInt())
            var size = 0
            var result = Z_OK
            data.usePinned { input ->
                stream.next_in = if (data.isEmpty()) null else input.addressOf(0).reinterpret()
                stream.avail_in = data.size.convert()
                while (result != Z_STREAM_END) {
                    if (size == out.size) {
                        if (size == limit) throw ZlibTooLargeException(maxBytes)
                        out = out.copyOf(minOf(size * 2L, limit.toLong()).toInt())
                    }
                    result =
                        out.usePinned { output ->
                            stream.next_out = output.addressOf(size).reinterpret()
                            stream.avail_out = (out.size - size).convert()
                            zinflate(stream.ptr, Z_NO_FLUSH).also { size = out.size - stream.avail_out.convert<Long>().toInt() }
                        }
                    when (result) {
                        Z_OK, Z_STREAM_END -> {}

                        // With room left to write, no progress means the input ran out.
                        Z_BUF_ERROR -> {
                            throw ZlibException("zlib stream ends early")
                        }

                        else -> {
                            throw ZlibException("corrupt zlib stream")
                        }
                    }
                }
            }
            if (size > maxBytes) throw ZlibTooLargeException(maxBytes)
            return out.copyOf(size)
        } finally {
            inflateEnd(stream.ptr)
            nativeHeap.free(stream)
        }
    }
}

/** A zlib stream that is corrupt or ends early. */
public open class ZlibException(
    message: String,
) : Exception(message)

/** A zlib stream that inflates past the caller's [limit]; nothing past it was allocated. */
public class ZlibTooLargeException(
    public val limit: Int,
) : ZlibException("zlib stream inflates past $limit bytes")

// A Kotlin array holds at most Int.MAX_VALUE bytes, and the cap needs one more.
private const val MAX_OUTPUT = Int.MAX_VALUE - 8

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
