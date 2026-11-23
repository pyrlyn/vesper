package vesper.macos

private const val FONT_SHIFT = 32
private const val SPRITE_TAG = 1L shl 62
private const val GLYPH_MASK = 0xFFFFL

// Shelf heights are rounded to this, so glyphs of nearly one height share shelves.
private const val SHELF_STEP = 4

/** The atlas key of glyph [glyph] of registered font [font]. */
internal fun glyphKey(
    font: Int,
    glyph: Int,
): Long = (font.toLong() shl FONT_SHIFT) or (glyph.toLong() and GLYPH_MASK)

/** The atlas key of the sprite for [code] (a code point, or an underline sprite past Unicode). */
internal fun spriteKey(code: Int): Long = SPRITE_TAG or code.toLong()

internal fun isSpriteKey(key: Long): Boolean = key and SPRITE_TAG != 0L

internal fun keyFont(key: Long): Int = (key ushr FONT_SHIFT).toInt()

internal fun keyGlyph(key: Long): Int = (key and GLYPH_MASK).toInt()

internal fun keySprite(key: Long): Int = (key and 0xFFFF_FFFFL).toInt()

/** Where an image sits in the atlas and how it sits against the pen; [width] 0 is nothing to draw. */
internal data class AtlasEntry(
    val x: Int = 0,
    val y: Int = 0,
    val width: Int = 0,
    val height: Int = 0,
    /** Pixels from the pen to the image's left edge. */
    val left: Int = 0,
    /** Pixels from the baseline up to the image's top edge. */
    val top: Int = 0,
    val isColor: Boolean = false,
    val shelf: Int = 0,
) {
    val isEmpty: Boolean get() = width == 0

    companion object {
        val EMPTY = AtlasEntry()
    }
}

/**
 * The layout of a square atlas texture in shelves. A terminal shows a few hundred distinct glyphs at
 * a time, so shelves of near-equal height pack it well, and a whole shelf is the unit of eviction:
 * cheap to track, and no free-rectangle bookkeeping. A shelf drawn within [framesInFlight] frames may
 * still be read by the GPU, so it is never emptied under it.
 */
internal class AtlasLayout(
    initialSize: Int = 1024,
    maxSize: Int = 8192,
    private val framesInFlight: Long = 3,
) {
    private class Shelf(
        val y: Int,
        val height: Int,
        var lastUsed: Long,
    ) {
        var x = 0
        val keys = ArrayList<Long>()
    }

    private val maxSize = maxOf(initialSize, maxSize)
    private val shelves = ArrayList<Shelf>()
    private val entries = HashMap<Long, AtlasEntry>()
    private var nextY = 0

    /** The texture's side; it doubles when nothing fits. */
    var size = initialSize
        private set

    /** Bumped when the atlas starts over at a larger size: every entry handed out before is gone. */
    var generation = 0
        private set

    /** The entry for [key], marking its shelf drawn in [frame]; null when not yet placed. */
    fun get(
        key: Long,
        frame: Long,
    ): AtlasEntry? {
        val entry = entries[key] ?: return null
        if (!entry.isEmpty) touch(entry.shelf, frame)
        return entry
    }

    /** Remembers that [key] draws nothing, so it is not rasterised again. */
    fun putEmpty(key: Long) {
        entries[key] = AtlasEntry.EMPTY
    }

    /** Keeps a shelf that rows reused from an earlier frame draw from. */
    fun touch(
        shelf: Int,
        frame: Long,
    ) {
        val it = shelves.getOrNull(shelf) ?: return
        it.lastUsed = maxOf(it.lastUsed, frame)
    }

    /**
     * Room for a [width] by [height] image under [key]. The atlas may grow on the way (see
     * [generation]); null when it fits nowhere even at the largest size.
     */
    fun place(
        key: Long,
        width: Int,
        height: Int,
        left: Int,
        top: Int,
        isColor: Boolean,
        frame: Long,
    ): AtlasEntry? {
        val slotWidth = width + 1
        val slotHeight = (height + 1 + SHELF_STEP - 1) / SHELF_STEP * SHELF_STEP
        while (slotWidth <= size && slotHeight <= size) {
            val index = shelfWithRoom(slotWidth, slotHeight) ?: newShelf(slotHeight, frame) ?: evict(slotHeight, frame)
            if (index != null) {
                val shelf = shelves[index]
                val entry = AtlasEntry(shelf.x, shelf.y, width, height, left, top, isColor, index)
                shelf.x += slotWidth
                shelf.lastUsed = frame
                shelf.keys.add(key)
                entries[key] = entry
                return entry
            }
            if (!grow()) break
        }
        return null
    }

    private fun shelfWithRoom(
        width: Int,
        height: Int,
    ): Int? {
        val index = shelves.indexOfFirst { it.height >= height && it.height <= height + height / 4 + SHELF_STEP && it.x + width <= size }
        return index.takeIf { it >= 0 }
    }

    private fun newShelf(
        height: Int,
        frame: Long,
    ): Int? {
        if (nextY + height > size) return null
        shelves.add(Shelf(nextY, height, frame))
        nextY += height
        return shelves.size - 1
    }

    /** Empties the least recently drawn shelf tall enough for [height]. */
    private fun evict(
        height: Int,
        frame: Long,
    ): Int? {
        var best = -1
        for (i in shelves.indices) {
            val shelf = shelves[i]
            if (shelf.height < height || shelf.lastUsed + framesInFlight >= frame) continue
            if (best < 0 || shelf.lastUsed < shelves[best].lastUsed) best = i
        }
        if (best < 0) return null
        val shelf = shelves[best]
        for (key in shelf.keys) entries.remove(key)
        shelf.keys.clear()
        shelf.x = 0
        return best
    }

    private fun grow(): Boolean {
        if (size * 2 > maxSize) return false
        size *= 2
        shelves.clear()
        entries.clear()
        nextY = 0
        generation++
        return true
    }
}
