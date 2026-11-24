package vesper.image

import kotlin.io.encoding.Base64

/** Standard base64; senders differ on padding, so it may be left out. */
internal val BASE64 = Base64.withPadding(Base64.PaddingOption.PRESENT_OPTIONAL)

/** Base64 characters that decode to at most [maxBytes]. */
private fun maxEncoded(maxBytes: Int): Long = (maxBytes.toLong() + 2) / 3 * 4

/** The base64 in [from, to) of [bytes], refused before decoding when it would pass [maxBytes]. */
internal fun decodeBase64(
    bytes: ByteArray,
    from: Int,
    to: Int,
    maxBytes: Int,
): ByteArray {
    if (to - from > maxEncoded(maxBytes)) throw ImageError.TooLarge((to - from).toLong() / 4 * 3, maxBytes.toLong())
    return try {
        BASE64.decode(bytes, from, to)
    } catch (e: IllegalArgumentException) {
        throw ImageError.Malformed("bad base64: ${e.message}")
    }
}

/**
 * Base64 that arrives in parts (iTerm2's multipart files, kitty's chunks), held encoded until the
 * last part and never past what decodes to [maxBytes]. The buffer doubles as parts arrive, so a
 * sender that announces nothing still cannot make it allocate past the cap.
 */
internal class Base64Parts(
    private val maxBytes: Int,
) {
    private var parts = ByteArray(0)
    private var size = 0

    /** Adds [from, to) of [bytes]; past the cap everything held is dropped. */
    fun append(
        bytes: ByteArray,
        from: Int,
        to: Int,
    ) {
        val needed = size.toLong() + to - from
        val max = maxEncoded(maxBytes)
        if (needed > max) {
            clear()
            throw ImageError.TooLarge(needed / 4 * 3, maxBytes.toLong())
        }
        if (needed > parts.size) parts = parts.copyOf(maxOf(needed.toInt(), minOf(parts.size * 2L, max).toInt()))
        bytes.copyInto(parts, size, from, to)
        size = needed.toInt()
    }

    /** Everything appended, decoded; the buffer is empty afterwards. */
    fun decode(): ByteArray =
        try {
            decodeBase64(parts, 0, size, maxBytes)
        } finally {
            clear()
        }

    fun clear() {
        parts = ByteArray(0)
        size = 0
    }
}
