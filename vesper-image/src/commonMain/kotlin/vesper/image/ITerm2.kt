package vesper.image

// iTerm2's inline images, from https://iterm2.com/documentation-images.html:
//   OSC 1337 ; File=<key=value;...> : <base64 file> ST
//   OSC 1337 ; MultipartFile=<key=value;...> ST, then FilePart=<base64> ..., then FileEnd
// The multipart form (iTerm2 3.5) exists for tmux, which cuts long sequences.

/** One side of the size an inline image asks for. */
public sealed interface Extent {
    /** The image's own size decides. */
    public data object Auto : Extent

    public data class Cells(
        val count: Int,
    ) : Extent

    public data class Pixels(
        val count: Int,
    ) : Extent

    /** Percent of the session's width or height. */
    public data class Percent(
        val percent: Int,
    ) : Extent
}

/** An inline image ready to place: its pixels and the size its sender asked for. */
public class InlineImage(
    public val pixels: RgbaImage,
    /** The file name the sender gave, if any; not trusted for anything but display. */
    public val name: String?,
    public val width: Extent,
    public val height: Extent,
    /** When false the image stretches to fill [width] and [height]. */
    public val preserveAspectRatio: Boolean,
)

private val PREFIX = "1337;".encodeToByteArray()
private val FILE = "File=".encodeToByteArray()
private val MULTIPART = "MultipartFile=".encodeToByteArray()
private val PART = "FilePart=".encodeToByteArray()
private val END = "FileEnd".encodeToByteArray()
private const val COLON = ':'.code.toByte()

/** Arguments of `File=` and `MultipartFile=`. */
private class Args(
    val name: String?,
    val size: Long?,
    val width: Extent,
    val height: Extent,
    val preserveAspectRatio: Boolean,
    val inline: Boolean,
)

/**
 * Decodes iTerm2 inline images from OSC 1337 payloads. A file is at most [maxFileBytes] and
 * decodes to at most [maxPixelBytes]; both are checked before anything that large is allocated.
 * Holds the state of one multipart transfer, so one instance serves one terminal.
 */
public class ITerm2Images(
    private val maxFileBytes: Int,
    private val maxPixelBytes: Long,
) {
    private var pending: Args? = null
    private val parts = Base64Parts(maxFileBytes)

    /**
     * Takes the whole payload of one OSC. Returns the image a `File=` or `FileEnd` completes;
     * null for any other OSC, a multipart step, or a file sent to download (`inline=0`), which
     * the core does not save.
     *
     * @throws ImageError when the sequence is malformed, too large or not decodable. A multipart
     *   transfer that fails is dropped.
     */
    public fun accept(payload: ByteArray): InlineImage? {
        if (!payload.startsWith(PREFIX, 0)) return null
        val at = PREFIX.size
        return when {
            payload.startsWith(FILE, at) -> {
                pending = null
                val colon = payload.indexOf(COLON, at + FILE.size)
                if (colon < 0) throw ImageError.Malformed("no ':' before the file")
                val args = parseArgs(payload, at + FILE.size, colon)
                checkSize(args)
                if (!args.inline) null else image(args, decodeBase64(payload, colon + 1, payload.size, maxFileBytes))
            }

            payload.startsWith(MULTIPART, at) -> {
                pending = null
                val args = parseArgs(payload, at + MULTIPART.size, payload.size)
                checkSize(args)
                pending = args
                parts.clear()
                null
            }

            payload.startsWith(PART, at) -> {
                if (pending != null) {
                    try {
                        parts.append(payload, at + PART.size, payload.size)
                    } catch (e: ImageError) {
                        pending = null
                        throw e
                    }
                }
                null
            }

            payload.size == at + END.size && payload.startsWith(END, at) -> {
                val args = pending ?: return null
                pending = null
                val file = parts.decode()
                if (!args.inline) null else image(args, file)
            }

            else -> {
                null
            }
        }
    }

    private fun checkSize(args: Args) {
        val size = args.size ?: return
        if (size > maxFileBytes) throw ImageError.TooLarge(size, maxFileBytes.toLong())
    }

    private fun image(
        args: Args,
        file: ByteArray,
    ): InlineImage = InlineImage(decodeFile(file, maxPixelBytes), args.name, args.width, args.height, args.preserveAspectRatio)
}

/** `key=value` pairs separated by `;` in [from, to); unknown keys and bad values fall back to defaults. */
private fun parseArgs(
    bytes: ByteArray,
    from: Int,
    to: Int,
): Args {
    var name: String? = null
    var size: Long? = null
    var width: Extent = Extent.Auto
    var height: Extent = Extent.Auto
    var preserve = true
    var inline = false
    for (pair in bytes.decodeToString(from, to).split(';')) {
        val eq = pair.indexOf('=')
        if (eq < 0) continue
        val value = pair.substring(eq + 1)
        when (pair.substring(0, eq)) {
            "name" -> name = runCatching { BASE64.decode(value).decodeToString() }.getOrNull()
            "size" -> size = value.toLongOrNull()?.takeIf { it >= 0 }
            "width" -> width = extent(value)
            "height" -> height = extent(value)
            "preserveAspectRatio" -> preserve = value != "0"
            "inline" -> inline = value == "1"
        }
    }
    return Args(name, size, width, height, preserve, inline)
}

private fun extent(value: String): Extent {
    fun count(digits: String) = digits.toIntOrNull()?.takeIf { it > 0 }
    return when {
        value.endsWith("px") -> count(value.dropLast(2))?.let { Extent.Pixels(it) }
        value.endsWith("%") -> count(value.dropLast(1))?.let { Extent.Percent(it) }
        else -> count(value)?.let { Extent.Cells(it) }
    } ?: Extent.Auto
}

private fun ByteArray.startsWith(
    prefix: ByteArray,
    at: Int,
): Boolean = size - at >= prefix.size && prefix.indices.all { this[at + it] == prefix[it] }

private fun ByteArray.indexOf(
    byte: Byte,
    from: Int,
): Int {
    for (i in from until size) if (this[i] == byte) return i
    return -1
}
