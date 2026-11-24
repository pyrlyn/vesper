package vesper.macos

/** Ints per quad: the 32 bytes the shaders' `Quad` struct takes. */
internal const val QUAD_INTS = 8

/** A quad filled with its colour. */
internal const val SOLID = 0

/** Atlas coverage tinted with the quad's colour. */
internal const val TINTED = 1

/** An atlas texel's own colour (emoji), faded by the quad's alpha. */
internal const val COLOR_GLYPH = 2

/** An image texture's texels. */
internal const val IMAGE = 3

private const val HALF = 16
private const val SHORT = 0xFFFF
private const val BYTE = 0xFF

/**
 * Quads laid out as the shaders read them, so a row's list is copied into a Metal buffer as it is:
 * x, y, width, height as floats in pixels; the atlas texel as two 16-bit halves; RGBA with red in the
 * low byte; the kind; and for an image, the texels it spans.
 */
internal class Quads(
    capacity: Int = 16,
) {
    var data = IntArray(capacity * QUAD_INTS)
        private set
    var count = 0
        private set

    fun clear() {
        count = 0
    }

    fun add(
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        color: Int,
        kind: Int = SOLID,
        u: Int = 0,
        v: Int = 0,
        spanU: Int = 0,
        spanV: Int = 0,
    ) {
        if ((count + 1) * QUAD_INTS > data.size) data = data.copyOf(data.size * 2)
        val at = count * QUAD_INTS
        data[at] = x.toRawBits()
        data[at + 1] = y.toRawBits()
        data[at + 2] = w.toRawBits()
        data[at + 3] = h.toRawBits()
        data[at + 4] = (u and SHORT) or (v shl HALF)
        data[at + 5] = color
        data[at + 6] = kind
        data[at + 7] = (spanU and SHORT) or (spanV shl HALF)
        count++
    }

    fun x(index: Int): Float = Float.fromBits(data[index * QUAD_INTS])

    fun y(index: Int): Float = Float.fromBits(data[index * QUAD_INTS + 1])

    fun w(index: Int): Float = Float.fromBits(data[index * QUAD_INTS + 2])

    fun h(index: Int): Float = Float.fromBits(data[index * QUAD_INTS + 3])

    fun color(index: Int): Int = data[index * QUAD_INTS + 5]

    fun kind(index: Int): Int = data[index * QUAD_INTS + 6]

    /** Appends quad [index] of [from], moved down by [dy] and recoloured. */
    fun addCopy(
        from: Quads,
        index: Int,
        dy: Float,
        color: Int,
    ) {
        val at = index * QUAD_INTS
        val texel = from.data[at + 4]
        val span = from.data[at + 7]
        add(
            from.x(index),
            from.y(index) + dy,
            from.w(index),
            from.h(index),
            color,
            from.kind(index),
            texel and SHORT,
            texel ushr HALF,
            span and SHORT,
            span ushr HALF,
        )
    }
}

/** [rgb] (0xRRGGBB) with [alpha] as the shaders unpack it: red in the low byte. */
internal fun shaderColor(
    rgb: Int,
    alpha: Int = BYTE,
): Int = red(rgb) or (green(rgb) shl 8) or (blue(rgb) shl HALF) or (alpha shl 24)
