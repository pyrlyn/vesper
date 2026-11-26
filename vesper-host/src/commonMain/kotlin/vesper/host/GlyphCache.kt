package vesper.host

/** The GPU texture behind a [GlyphCache]; each host backs it with its own graphics API. */
public interface AtlasTexture {
    /** Makes the texture [size] pixels square; what it held is gone. False when it cannot be made. */
    public fun resize(size: Int): Boolean

    /** Copies [bitmap] into the texture at [entry]'s place. */
    public fun upload(
        entry: AtlasEntry,
        bitmap: Bitmap,
    )
}

/** The glyphs and sprites rasterised so far, in one [texture] laid out by [AtlasLayout]. */
public class GlyphCache private constructor(
    private val texture: AtlasTexture,
    private val layout: AtlasLayout,
) {
    /** The texture's side. */
    public val size: Int get() = layout.size

    /** See [AtlasLayout.generation]: entries handed out under an older one are gone. */
    public val generation: Int get() = layout.generation

    /** The entry for [key], rasterised by [make] the first time; null when it fits nowhere. */
    public fun entry(
        key: Long,
        frame: Long,
        make: () -> Bitmap?,
    ): AtlasEntry? {
        layout.get(key, frame)?.let { return it }
        val bitmap = make()
        if (bitmap == null) {
            layout.putEmpty(key)
            return AtlasEntry.EMPTY
        }
        val size = layout.size
        val entry = layout.place(key, bitmap.width, bitmap.height, bitmap.left, bitmap.top, bitmap.isColor, frame) ?: return null
        if (layout.size != size && !texture.resize(layout.size)) return null
        texture.upload(entry, bitmap)
        return entry
    }

    /** Keeps [shelf] from eviction while rows drawn in [frame] still read it. */
    public fun touch(
        shelf: Int,
        frame: Long,
    ): Unit = layout.touch(shelf, frame)

    public companion object {
        /** A cache whose [texture] starts [size] pixels square; null when the texture cannot be made. */
        public fun create(
            texture: AtlasTexture,
            size: Int = 1024,
            maxSize: Int = 8192,
        ): GlyphCache? = if (texture.resize(size)) GlyphCache(texture, AtlasLayout(size, maxSize)) else null
    }
}
