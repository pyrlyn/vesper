package scull.image

/** Most images a store keeps by default, whatever their size, so tiny images cannot pile up. */
public const val DEFAULT_MAX_IMAGES: Int = 4096

/**
 * Pixels the store holds under [id]. Ids are never reused, so an id names one immutable buffer
 * and a texture cached by id never goes stale.
 */
public class Image internal constructor(
    public val id: Long,
    public val pixels: RgbaImage,
) {
    internal var lastUse = 0L
}

/** The part of an image a placement shows, in image pixels. */
public class Crop(
    public val x: Int,
    public val y: Int,
    public val width: Int,
    public val height: Int,
) {
    init {
        require(x >= 0 && y >= 0 && width > 0 && height > 0) { "bad crop $x,$y ${width}x$height" }
    }
}

/**
 * Where an image is shown: its top-left cell at [column] of absolute [line] (rows ever evicted
 * plus the ring index, so scrolling does not move it), scaled to [columns] by [rows] cells, drawn
 * by [z] (below text when negative) and, for equal [z], in placement order. [offsetX] and
 * [offsetY] move it right and down inside its first cell, in pixels.
 */
public class Placement(
    public val imageId: Long,
    public val column: Int,
    public val line: Long,
    public val columns: Int,
    public val rows: Int,
    public val crop: Crop? = null,
    public val z: Int = 0,
    public val offsetX: Int = 0,
    public val offsetY: Int = 0,
) {
    init {
        require(column >= 0 && columns > 0 && rows > 0) { "bad placement at $column over ${columns}x$rows cells" }
        require(offsetX >= 0 && offsetY >= 0) { "bad offset $offsetX,$offsetY" }
    }
}

/**
 * Images and their placements under a byte quota on the pixels. When a new image does not fit,
 * the least recently used images go first, those no placement shows before those it does; an
 * evicted image takes its placements with it, so a full scrollback never blocks new images.
 */
public class ImageStore(
    public val quotaBytes: Long,
    public val maxImages: Int = DEFAULT_MAX_IMAGES,
) {
    init {
        require(quotaBytes > 0 && maxImages > 0) { "quota $quotaBytes bytes, $maxImages images" }
    }

    private val images = HashMap<Long, Image>()
    private val placed = LinkedHashMap<Long, Placement>()
    private val uses = HashMap<Long, Int>()
    private var nextImage = 1L
    private var nextPlacement = 1L
    private var clock = 0L

    /** Pixel bytes held, at most [quotaBytes]. */
    public var usedBytes: Long = 0
        private set

    public val imageCount: Int get() = images.size

    /** Placements by id, in placement order. */
    public val placements: Map<Long, Placement> get() = placed

    /**
     * Stores [pixels], evicting what it must, and returns the new image.
     *
     * @throws ImageError.TooLarge when [pixels] alone pass the quota.
     */
    public fun add(pixels: RgbaImage): Image {
        if (pixels.bytes > quotaBytes) throw ImageError.TooLarge(pixels.bytes, quotaBytes)
        while (usedBytes + pixels.bytes > quotaBytes || images.size >= maxImages) evictOne()
        val image = Image(nextImage++, pixels)
        image.lastUse = ++clock
        images[image.id] = image
        usedBytes += pixels.bytes
        return image
    }

    /** The image [id], marked as just used; null when it was deleted or evicted. */
    public operator fun get(id: Long): Image? = images[id]?.also { it.lastUse = ++clock }

    /** Whether image [id] is held, without marking it used. */
    public fun hasImage(id: Long): Boolean = id in images

    /** Deletes image [id] and its placements; false when there was none. */
    public fun delete(id: Long): Boolean {
        val image = images.remove(id) ?: return false
        usedBytes -= image.pixels.bytes
        if (uses.remove(id) != null) placed.entries.removeAll { it.value.imageId == id }
        return true
    }

    /**
     * Shows [placement] and returns its id; null when its image is gone or its crop falls
     * outside the image.
     */
    public fun place(placement: Placement): Long? {
        val image = get(placement.imageId) ?: return null
        val crop = placement.crop
        if (crop != null && (crop.x.toLong() + crop.width > image.pixels.width || crop.y.toLong() + crop.height > image.pixels.height)) {
            return null
        }
        val id = nextPlacement++
        placed[id] = placement
        uses[placement.imageId] = (uses[placement.imageId] ?: 0) + 1
        return id
    }

    /** Removes placement [id]; its image stays until evicted or deleted. False when there was none. */
    public fun unplace(id: Long): Boolean {
        val placement = placed.remove(id) ?: return false
        release(placement.imageId)
        return true
    }

    /**
     * Moves placement [id]'s top-left cell to [column] of absolute [line], keeping its id and its
     * place in the drawing order, which a reflow must not change. False when there was none.
     */
    public fun move(
        id: Long,
        column: Int,
        line: Long,
    ): Boolean {
        val p = placed[id] ?: return false
        placed[id] = Placement(p.imageId, column, line, p.columns, p.rows, p.crop, p.z, p.offsetX, p.offsetY)
        return true
    }

    /** Removes every placement [predicate] matches and returns how many. */
    public fun removePlacements(predicate: (Placement) -> Boolean): Int {
        val gone = placed.filterValues(predicate).toList()
        for ((id, placement) in gone) {
            placed.remove(id)
            release(placement.imageId)
        }
        return gone.size
    }

    private fun release(imageId: Long) {
        val left = (uses[imageId] ?: return) - 1
        if (left == 0) uses.remove(imageId) else uses[imageId] = left
    }

    private fun evictOne() {
        // Only reached with images held: add refused anything larger than the whole quota.
        val victim =
            images.values.filter { it.id !in uses }.minByOrNull { it.lastUse }
                ?: images.values.minBy { it.lastUse }
        delete(victim.id)
    }

    /** Checks the bookkeeping against a recount; for the fuzz entry and tests. */
    internal fun checkInvariants() {
        check(usedBytes == images.values.sumOf { it.pixels.bytes }) { "used bytes drifted" }
        check(usedBytes <= quotaBytes && images.size <= maxImages) { "over the quota" }
        check(placed.values.all { it.imageId in images }) { "a placement outlived its image" }
        check(uses == placed.values.groupingBy { it.imageId }.eachCount()) { "use counts drifted" }
    }
}
