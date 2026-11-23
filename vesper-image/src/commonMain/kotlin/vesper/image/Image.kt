package vesper.image

import vesper.png.Png
import vesper.png.PngException
import vesper.png.PngTooLargeException

internal const val RGBA = 4

/** Decoded pixels: [width] by [height] of 8-bit sRGB RGBA, straight alpha, row after row. */
public class RgbaImage(
    public val width: Int,
    public val height: Int,
    public val rgba: ByteArray,
) {
    init {
        require(width > 0 && height > 0) { "empty image $width x $height" }
        require(rgba.size.toLong() == width.toLong() * height * RGBA) { "${rgba.size} bytes for $width x $height RGBA" }
    }

    /** What the pixels cost the store's quota. */
    public val bytes: Long get() = rgba.size.toLong()
}

/** Why an image was not decoded or not stored. Every decoder throws only these. */
public sealed class ImageError(
    message: String,
) : Exception(message) {
    /** The protocol sequence itself is wrong: a missing separator, bad base64. */
    public class Malformed(
        message: String,
    ) : ImageError(message)

    /** A file format the core does not decode. */
    public class Unsupported(
        public val format: String,
    ) : ImageError("unsupported image format: $format")

    /** The image data is corrupt. */
    public class Decode(
        message: String,
    ) : ImageError(message)

    /** The image would need [needed] bytes, past [limit]; nothing that large was allocated. */
    public class TooLarge(
        public val needed: Long,
        public val limit: Long,
    ) : ImageError("image needs $needed bytes, more than the $limit allowed")
}

/**
 * Decodes an image file sent whole (iTerm2, kitty `f=100`) with at most [maxPixelBytes] of
 * decoded pixels. Only PNG is decoded; the formats iTerm2 users send most besides it are named.
 */
public fun decodeFile(
    file: ByteArray,
    maxPixelBytes: Long,
): RgbaImage {
    fun startsWith(vararg prefix: Int) = file.size >= prefix.size && prefix.indices.all { file[it] == prefix[it].toByte() }
    return when {
        startsWith(0x89, 0x50, 0x4E, 0x47) -> decodePng(file, maxPixelBytes)
        startsWith(0xFF, 0xD8, 0xFF) -> throw ImageError.Unsupported("JPEG")
        startsWith(0x47, 0x49, 0x46, 0x38) -> throw ImageError.Unsupported("GIF")
        else -> throw ImageError.Unsupported("unknown")
    }
}

/** [png] decoded through the capped shared decoder, its errors as [ImageError]s. */
public fun decodePng(
    png: ByteArray,
    maxPixelBytes: Long,
): RgbaImage =
    try {
        Png.decode(png, maxPixelBytes).let { RgbaImage(it.width, it.height, it.rgba) }
    } catch (e: PngTooLargeException) {
        throw ImageError.TooLarge(e.needed, e.limit)
    } catch (e: PngException) {
        throw ImageError.Decode(e.message ?: "corrupt PNG")
    }
