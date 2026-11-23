package vesper.image

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
    val store = ImageStore(quotaBytes = 64L * RGBA, maxImages = 5, maxPlacements = 8)
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

private const val FUZZ_SIXEL_SIDE = 64

/**
 * The sixel decoder's fuzz target: [data] as one DCS payload, decoded whole and cut into pieces
 * whose length comes from the data itself. Both must give the same image or the same error, only
 * [ImageError]s may escape, and the image must fit the caps. Throws [IllegalStateException] on a
 * violation.
 */
public fun fuzzSixel(data: ByteArray) {
    fun decode(step: Int): String =
        try {
            val decoder = SixelDecoder(data.isNotEmpty() && data[0] < 0, FUZZ_SIXEL_SIDE, FUZZ_SIXEL_SIDE, FUZZ_PIXEL_BYTES / 64)
            for (from in data.indices step step) decoder.put(data, from, minOf(from + step, data.size))
            val image = decoder.finish()
            check(image.width <= FUZZ_SIXEL_SIDE && image.height <= FUZZ_SIXEL_SIDE) { "image past the size cap" }
            "${image.width}x${image.height} ${image.rgba.contentHashCode()}"
        } catch (e: ImageError) {
            "${e::class.simpleName} ${e.message}"
        }
    val whole = decode(maxOf(data.size, 1))
    val step = if (data.isEmpty()) 1 else (data[data.size - 1].toInt() and 7) + 1
    check(decode(step) == whole) { "cutting the stream changed the image" }
}

private const val ESC = 0x1B.toByte()

/**
 * The kitty decoder's fuzz target: [data] cut at ESC into APC payloads, all fed to one decoder over
 * a small store. Nothing may escape [KittyGraphics.accept], every reply must be one APC, the
 * cursor may only move forward, and the store's and decoder's bookkeeping are rechecked after
 * each payload. Throws [IllegalStateException] on a violation.
 */
public fun fuzzKitty(data: ByteArray) {
    val store = ImageStore(quotaBytes = FUZZ_PIXEL_BYTES, maxImages = 6)
    val kitty = KittyGraphics(store, FUZZ_FILE_BYTES, FUZZ_PIXEL_BYTES / 4)
    val context = KittyContext(column = 3, line = 40, cellWidth = 4, cellHeight = 8, top = 38, columns = 20, rows = 6)
    var start = 0
    for (i in 0..data.size) {
        if (i < data.size && data[i] != ESC) continue
        val outcome = kitty.accept(data.copyOfRange(start, i), context)
        val reply = outcome.reply
        check(reply == null || (reply.startsWith("\u001b_G") && reply.endsWith("\u001b\\"))) { "not an APC reply: $reply" }
        check(outcome.cursorColumns >= 0 && outcome.cursorRows >= 0) { "the cursor moved back" }
        store.checkInvariants()
        kitty.checkInvariants()
        start = i + 1
    }
}
