package scull.image

private const val FUZZ_FILE_BYTES = 1 shl 16
private const val FUZZ_PIXEL_BYTES = 1L shl 20
private const val BEL = 7.toByte()

/**
 * The iTerm2 decoder's fuzz target: [data] cut at BEL into OSC payloads, all fed to one decoder.
 * Only an [ImageError] may escape a payload, and every image must fit the caps. Throws
 * [IllegalStateException] on a violation. The tests run it over a seeded mutated corpus.
 */
public fun fuzzInlineImages(data: ByteArray) {
    val decoder = ITerm2Images(FUZZ_FILE_BYTES, FUZZ_PIXEL_BYTES)
    var start = 0
    for (i in 0..data.size) {
        if (i < data.size && data[i] != BEL) continue
        try {
            val image = decoder.accept(data.copyOfRange(start, i))
            if (image != null) check(image.pixels.bytes <= FUZZ_PIXEL_BYTES) { "image past the pixel cap" }
        } catch (_: ImageError) {
            // Refusing bad input is the job; anything else escaping is the bug this looks for.
        }
        start = i + 1
    }
}

/**
 * The store's fuzz target: [data] read as a list of operations (add, get, delete, place,
 * unplace, remove placements) on a small store, its bookkeeping rechecked after each one.
 * Throws [IllegalStateException] on a violation.
 */
public fun fuzzImageStore(data: ByteArray) {
    val store = ImageStore(quotaBytes = 64L * RGBA, maxImages = 5)
    val ids = ArrayList<Long>()
    val placements = ArrayList<Long>()
    var i = 0

    fun next(): Int = if (i < data.size) data[i++].toInt() and 0xFF else 0
    while (i < data.size) {
        when (next() % 6) {
            0 -> {
                val w = next() % 9 + 1
                val h = next() % 9 + 1
                try {
                    ids += store.add(RgbaImage(w, h, ByteArray(w * h * RGBA))).id
                } catch (e: ImageError.TooLarge) {
                    check(e.needed > store.quotaBytes) { "refused an image that fits" }
                }
            }

            1 -> {
                if (ids.isNotEmpty()) store[ids[next() % ids.size]]
            }

            2 -> {
                if (ids.isNotEmpty()) store.delete(ids[next() % ids.size])
            }

            3 -> {
                if (ids.isNotEmpty()) {
                    val id = ids[next() % ids.size]
                    val placed = store.place(Placement(id, next() % 4, next().toLong(), next() % 3 + 1, next() % 3 + 1, z = next() - 128))
                    if (placed != null) placements += placed
                    check((placed != null) == (id in store.placements.values.map { it.imageId })) { "place disagrees with the store" }
                }
            }

            4 -> {
                if (placements.isNotEmpty()) store.unplace(placements[next() % placements.size])
            }

            else -> {
                val line = next().toLong()
                store.removePlacements { it.line == line }
            }
        }
        store.checkInvariants()
    }
}
