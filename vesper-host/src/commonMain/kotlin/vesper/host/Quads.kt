package vesper.host

/** Ints per quad: the 32 bytes the shaders' `Quad` struct takes. */
public const val QUAD_INTS: Int = 8

/** A quad filled with its colour. */
public const val SOLID: Int = 0

/** Atlas coverage tinted with the quad's colour. */
public const val TINTED: Int = 1

/** An atlas texel's own colour (emoji), faded by the quad's alpha. */
public const val COLOR_GLYPH: Int = 2

/** An image texture's texels. */
public const val IMAGE: Int = 3

private const val HALF = 16
private const val SHORT = 0xFFFF
private const val BYTE = 0xFF

/**
 * Quads laid out as the shaders read them, so a row's list is copied into a GPU buffer as it is:
 * x, y, width, height as floats in pixels; the atlas texel as two 16-bit halves; RGBA with red in the
 * low byte; the kind; and for an image, the texels it spans.
 */
public class Quads(
    capacity: Int = 16,
) {
    public var data: IntArray = IntArray(capacity * QUAD_INTS)
        private set
    public var count: Int = 0
        private set

    public fun clear() {
        count = 0
    }

    public fun add(
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

    public fun x(index: Int): Float = Float.fromBits(data[index * QUAD_INTS])

    public fun y(index: Int): Float = Float.fromBits(data[index * QUAD_INTS + 1])

    public fun w(index: Int): Float = Float.fromBits(data[index * QUAD_INTS + 2])

    public fun h(index: Int): Float = Float.fromBits(data[index * QUAD_INTS + 3])

    public fun color(index: Int): Int = data[index * QUAD_INTS + 5]

    public fun kind(index: Int): Int = data[index * QUAD_INTS + 6]

    /** Appends quad [index] of [from], moved down by [dy] and recoloured. */
    public fun addCopy(
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
public fun shaderColor(
    rgb: Int,
    alpha: Int = BYTE,
): Int = red(rgb) or (green(rgb) shl 8) or (blue(rgb) shl HALF) or (alpha shl 24)
