package vesper.host

/** A rasterised image before it enters the atlas: premultiplied BGRA rows, top row first, as the texture holds them. */
public class Bitmap(
    public val width: Int,
    public val height: Int,
    /** Pixels from the pen to the left edge. */
    public val left: Int,
    /** Pixels from the baseline up to the top edge. */
    public val top: Int,
    public val isColor: Boolean,
    public val pixels: ByteArray,
) {
    /** The BGRA bytes of pixel ([x], [y]). */
    public fun pixel(
        x: Int,
        y: Int,
    ): Int {
        val at = (y * width + x) * 4
        return (pixels[at].toInt() and 0xFF) or ((pixels[at + 1].toInt() and 0xFF) shl 8) or
            ((pixels[at + 2].toInt() and 0xFF) shl 16) or ((pixels[at + 3].toInt() and 0xFF) shl 24)
    }

    /** Each host adds its own rasterisers here as extensions. */
    public companion object {
        /** No glyph or sprite needs a larger side; anything larger is a broken font. */
        public const val MAX_SIDE: Int = 512
    }
}
