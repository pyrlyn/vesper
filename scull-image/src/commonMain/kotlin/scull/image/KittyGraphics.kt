package scull.image

import scull.png.Zlib
import scull.png.ZlibException
import scull.png.ZlibTooLargeException

// The kitty graphics protocol, from https://sw.kovidgoyal.net/kitty/graphics-protocol/:
//   APC G <key=value,...> ; <base64 payload> ST
// kitty itself is GPL-3.0-only, so this follows the specification alone.

/** What the terminal knows when a command arrives. */
public class KittyContext(
    /** The cursor's column, from 0. */
    public val column: Int,
    /** The cursor's absolute line, counted as [Placement.line] counts. */
    public val line: Long,
    /** A cell's size in pixels. */
    public val cellWidth: Int,
    public val cellHeight: Int,
    /** The absolute line of the screen's top row. */
    public val top: Long,
    /** The screen's size in cells. */
    public val columns: Int,
    public val rows: Int,
) {
    init {
        require(column >= 0 && columns > 0 && rows > 0) { "cursor column $column on a ${columns}x$rows screen" }
        require(cellWidth in 1..MAX_CELL_PIXELS && cellHeight in 1..MAX_CELL_PIXELS) { "cell of ${cellWidth}x$cellHeight pixels" }
    }
}

/** What a command asks of the terminal: a [reply] to send the program, and how far to move the cursor. */
public class KittyOutcome(
    /** A whole APC sequence, or null when the command wants none. */
    public val reply: String?,
    public val cursorColumns: Int = 0,
    public val cursorRows: Int = 0,
)

/**
 * Decodes kitty graphics commands into [store]: transmissions (raw RGB, RGBA and PNG, deflated
 * or not, whole or in chunks), placements and deletes. The data of one command is at most
 * [maxFileBytes] after base64 and again after zlib, and decodes to at most [maxPixelBytes]; both
 * are checked before anything that large is allocated. It keeps the client's image ids, image
 * numbers and placement ids, and one chunked upload, so one instance serves one terminal.
 *
 * Only data sent in the sequence is read: a command naming a file, a temporary file or shared
 * memory is refused, as a terminal must not open local paths a remote program names. Animation,
 * Unicode placeholders and placements relative to other placements are refused too.
 */
public class KittyGraphics(
    private val store: ImageStore,
    private val maxFileBytes: Int,
    private val maxPixelBytes: Long,
) {
    /** Client image id to store image id. */
    private val ids = HashMap<Long, Long>()

    /** Image number to the client id of the newest image with it. */
    private val numbers = HashMap<Long, Long>()

    /** Client image id and placement id, as [placementKey], to store placement id. */
    private val placementIds = HashMap<Long, Long>()

    private var nextAutoId = U32_MAX
    private var upload: Keys? = null
    private var uploadQuiet: String? = null
    private val parts = Base64Parts(maxFileBytes)

    /** After a chunk failed, the rest of its upload is dropped without a word. */
    private var discarding = false

    /** The id the terminal picked for an `I` command, which its reply names. */
    private var assigned = 0L

    /**
     * Takes the whole payload of one APC, without the `ESC _` and the terminator. Anything that
     * is not a graphics command (`G`) is ignored. Never throws: every refusal is a reply, or
     * silence where the protocol wants none.
     */
    public fun accept(
        apc: ByteArray,
        context: KittyContext,
    ): KittyOutcome {
        if (apc.isEmpty() || apc[0] != G) return NOTHING
        return command(apc, context).also { prune() }
    }

    private fun command(
        apc: ByteArray,
        context: KittyContext,
    ): KittyOutcome {
        assigned = 0
        var semicolon = 1
        while (semicolon < apc.size && apc[semicolon] != SEMICOLON) semicolon++
        val keys = Keys.parse(apc.decodeToString(1, semicolon))
        val payload = minOf(semicolon + 1, apc.size)
        if (upload != null || discarding) {
            if (keys.raw('a') != "d") return chunk(keys, apc, payload, context)
            // A delete ends the upload it interrupts, then runs.
            abortUpload()
        }
        val action = keys.raw('a') ?: "t"
        if (keys.raw('m') == "1" && action in UPLOADS) return startUpload(keys, apc, payload)
        return execute(keys, keys.raw('q'), context) { decodeBase64(apc, payload, apc.size, maxFileBytes) }
    }

    private fun startUpload(
        keys: Keys,
        apc: ByteArray,
        payload: Int,
    ): KittyOutcome {
        upload = keys
        uploadQuiet = null
        parts.clear()
        return try {
            parts.append(apc, payload, apc.size)
            NOTHING
        } catch (e: ImageError) {
            abortUpload()
            discarding = true
            refusedReply(keys, keys.raw('q'), refusal(e))
        }
    }

    /** A later chunk: only `m` and `q` count, the first chunk's keys say the rest. */
    private fun chunk(
        keys: Keys,
        apc: ByteArray,
        payload: Int,
        context: KittyContext,
    ): KittyOutcome {
        val more = keys.raw('m') == "1"
        if (discarding) {
            discarding = more
            return NOTHING
        }
        val first = checkNotNull(upload)
        keys.raw('q')?.let { uploadQuiet = it }
        val quiet = uploadQuiet ?: first.raw('q')
        try {
            parts.append(apc, payload, apc.size)
        } catch (e: ImageError) {
            abortUpload()
            discarding = more
            return refusedReply(first, quiet, refusal(e))
        }
        if (more) return NOTHING
        upload = null
        return execute(first, quiet, context) { parts.decode() }
    }

    private fun abortUpload() {
        upload = null
        uploadQuiet = null
        parts.clear()
        discarding = false
    }

    private fun execute(
        keys: Keys,
        quiet: String?,
        context: KittyContext,
        data: () -> ByteArray,
    ): KittyOutcome =
        try {
            when (val action = keys.char('a', 't')) {
                't', 'T', 'q' -> {
                    transmit(keys, action, context, quiet, data)
                }

                'p' -> {
                    val (client, image) = resolve(keys)
                    refuseUnsupported(keys)
                    place(keys, client, image, context, quiet)
                }

                'd' -> {
                    try {
                        delete(keys, context)
                    } catch (_: Refused) {
                        // Deletes never reply, so a bad one is simply not done.
                    }
                    NOTHING
                }

                'f', 'a', 'c' -> {
                    throw Refused("EINVAL", "animation is not supported")
                }

                else -> {
                    throw Refused("EINVAL", "unknown action $action")
                }
            }
        } catch (e: Refused) {
            refusedReply(keys, quiet, e)
        }

    private fun transmit(
        keys: Keys,
        action: Char,
        context: KittyContext,
        quiet: String?,
        data: () -> ByteArray,
    ): KittyOutcome {
        val id = keys.unsigned('i')
        val number = keys.unsigned('I')
        if (id != 0L && number != 0L) throw Refused("EINVAL", "i and I together")
        if (action == 'T') refuseUnsupported(keys)
        when (keys.char('t', 'd')) {
            'd' -> {}

            'f', 't', 's' -> {
                throw Refused("EBADF", "only data sent in the sequence is read")
            }

            else -> {
                throw Refused("EINVAL", "unknown transmission medium")
            }
        }
        val bytes =
            try {
                data()
            } catch (e: ImageError) {
                throw refusal(e)
            }
        val pixels = pixels(keys, bytes)
        if (action == 'q') return KittyOutcome(reply(keys, quiet, "OK", ok = true))
        val client =
            if (id != 0L) {
                id
            } else if (number != 0L) {
                autoId()
            } else {
                0L
            }
        if (client != 0L) forget(client)
        val image =
            try {
                store.add(pixels)
            } catch (_: ImageError.TooLarge) {
                throw Refused("ENOSPC", "the image is larger than the whole image store")
            }
        if (client != 0L) ids[client] = image.id
        if (number != 0L) {
            numbers[number] = client
            assigned = client
        }
        if (action == 'T') return place(keys, client, image, context, quiet)
        return KittyOutcome(reply(keys, quiet, "OK", ok = true))
    }

    private fun pixels(
        keys: Keys,
        data: ByteArray,
    ): RgbaImage {
        val compressed =
            when (keys.raw('o')) {
                null -> false
                "z" -> true
                else -> throw Refused("EINVAL", "unknown compression")
            }
        return when (val format = keys.unsigned('f', DEFAULT_FORMAT)) {
            RGB_FORMAT, RGBA_FORMAT -> {
                rawPixels(keys, if (format == RGB_FORMAT) 3 else RGBA, data, compressed)
            }

            PNG_FORMAT -> {
                val file = if (compressed) inflate(data, maxFileBytes, "EFBIG") else data
                try {
                    decodePng(file, maxPixelBytes)
                } catch (e: ImageError) {
                    throw refusal(e)
                }
            }

            else -> {
                throw Refused("EINVAL", "format $format is not supported")
            }
        }
    }

    private fun rawPixels(
        keys: Keys,
        channels: Int,
        data: ByteArray,
        compressed: Boolean,
    ): RgbaImage {
        val width = keys.unsigned('s')
        val height = keys.unsigned('v')
        if (width == 0L || height == 0L) throw Refused("EINVAL", "raw pixels need s and v")
        // Divided rather than multiplied: two 32-bit sides overflow a Long times four.
        val maxPixels = minOf(maxPixelBytes, MAX_ARRAY) / RGBA
        if (width > maxPixels / height) throw Refused("EFBIG", "${width}x$height pixels are past the limit")
        val expected = (width * height * channels).toInt()
        val bytes = if (compressed) inflate(data, expected, "ENODATA") else data
        if (bytes.size != expected) throw Refused("ENODATA", "${bytes.size} bytes for ${width}x$height pixels")
        val rgba =
            if (channels == RGBA) {
                bytes
            } else {
                ByteArray((width * height * RGBA).toInt()).also { out ->
                    for (p in 0 until (width * height).toInt()) {
                        bytes.copyInto(out, p * RGBA, p * channels, p * channels + channels)
                        out[p * RGBA + 3] = OPAQUE
                    }
                }
            }
        return RgbaImage(width.toInt(), height.toInt(), rgba)
    }

    /** [data] inflated to at most [maxBytes]; past it the command fails with [tooLarge]. */
    private fun inflate(
        data: ByteArray,
        maxBytes: Int,
        tooLarge: String,
    ): ByteArray =
        try {
            Zlib.inflate(data, maxBytes)
        } catch (_: ZlibTooLargeException) {
            throw Refused(tooLarge, "the data inflates past $maxBytes bytes")
        } catch (e: ZlibException) {
            throw Refused("EINVAL", e.message ?: "corrupt zlib data")
        }

    /** The client id and image a put names by `i` or `I`. */
    private fun resolve(keys: Keys): Pair<Long, Image> {
        val id = keys.unsigned('i')
        val number = keys.unsigned('I')
        if (id != 0L && number != 0L) throw Refused("EINVAL", "i and I together")
        val client =
            when {
                id != 0L -> id
                number != 0L -> numbers[number] ?: throw Refused("ENOENT", "no image with number $number")
                else -> throw Refused("EINVAL", "no image id")
            }
        if (number != 0L) assigned = client
        val image = ids[client]?.let { store[it] } ?: throw Refused("ENOENT", "no image with id $client")
        return client to image
    }

    private fun refuseUnsupported(keys: Keys) {
        if (keys.unsigned('U') != 0L) throw Refused("EINVAL", "Unicode placeholders are not supported")
        if ('P' in keys || 'Q' in keys) throw Refused("EINVAL", "relative placements are not supported")
    }

    private fun place(
        keys: Keys,
        client: Long,
        image: Image,
        context: KittyContext,
        quiet: String?,
    ): KittyOutcome {
        val width = image.pixels.width.toLong()
        val height = image.pixels.height.toLong()
        val x = keys.unsigned('x')
        val y = keys.unsigned('y')
        if (x >= width || y >= height) throw Refused("EINVAL", "the source rectangle starts outside the image")
        val w = keys.unsigned('w').let { if (it == 0L) width - x else minOf(it, width - x) }
        val h = keys.unsigned('h').let { if (it == 0L) height - y else minOf(it, height - y) }
        val crop = if (w == width && h == height) null else Crop(x.toInt(), y.toInt(), w.toInt(), h.toInt())
        val offsetX = minOf(keys.unsigned('X'), context.cellWidth - 1L)
        val offsetY = minOf(keys.unsigned('Y'), context.cellHeight - 1L)
        val cellWidth = context.cellWidth.toLong()
        val cellHeight = context.cellHeight.toLong()
        var columns = keys.unsigned('c')
        var rows = keys.unsigned('r')
        if (columns > MAX_CELLS || rows > MAX_CELLS) throw Refused("EINVAL", "${columns}x$rows cells are too many")
        // One side alone keeps the image's aspect ratio; neither shows it at its own size.
        when {
            columns == 0L && rows == 0L -> {
                columns = ceilDiv(offsetX + w, cellWidth)
                rows = ceilDiv(offsetY + h, cellHeight)
            }

            rows == 0L -> {
                rows = ceilDiv(columns * cellWidth * h, w * cellHeight)
            }

            columns == 0L -> {
                columns = ceilDiv(rows * cellHeight * w, h * cellWidth)
            }
        }
        columns = columns.coerceIn(1L, MAX_CELLS)
        rows = rows.coerceIn(1L, MAX_CELLS)
        // A placement id names a placement only together with a client image id.
        val key = if (client != 0L) keys.unsigned('p').takeIf { it != 0L }?.let { placementKey(client, it) } else null
        key?.let { placementIds.remove(it) }?.let { store.unplace(it) }
        val placement =
            Placement(
                image.id,
                context.column,
                context.line,
                columns.toInt(),
                rows.toInt(),
                crop,
                keys.signed('z'),
                offsetX.toInt(),
                offsetY.toInt(),
            )
        val placed = store.place(placement) ?: throw Refused("ENOENT", "the image is gone")
        if (key != null) placementIds[key] = placed
        val reply = reply(keys, quiet, "OK", ok = true)
        return if (keys.unsigned('C') == 1L) KittyOutcome(reply) else KittyOutcome(reply, columns.toInt(), rows.toInt())
    }

    private fun delete(
        keys: Keys,
        context: KittyContext,
    ) {
        val what = keys.char('d', 'a')
        val free = what.isUpperCase()
        when (what.lowercaseChar()) {
            'a' -> {
                removeWhere(free) { it.line < context.top + context.rows && it.line + it.rows > context.top }
            }

            'i' -> {
                deleteClient(keys.unsigned('i'), keys.unsigned('p'), free)
            }

            'n' -> {
                numbers[keys.unsigned('I')]?.let { deleteClient(it, keys.unsigned('p'), free) }
            }

            'c' -> {
                removeWhere(free) { it.covers(context.column, context.line) }
            }

            'p' -> {
                val column = cell(keys, 'x')
                val line = context.top + cell(keys, 'y')
                removeWhere(free) { it.covers(column, line) }
            }

            'q' -> {
                val column = cell(keys, 'x')
                val line = context.top + cell(keys, 'y')
                val z = keys.signed('z')
                removeWhere(free) { it.z == z && it.covers(column, line) }
            }

            'r' -> {
                val from = keys.unsigned('x')
                val to = keys.unsigned('y')
                if (from == 0L || to < from) throw Refused("EINVAL", "bad id range $from..$to")
                for (client in ids.keys.filter { it in from..to }) deleteClient(client, 0, free)
            }

            'x' -> {
                val column = cell(keys, 'x')
                removeWhere(free) { column >= it.column && column < it.column + it.columns }
            }

            'y' -> {
                val line = context.top + cell(keys, 'y')
                removeWhere(free) { line >= it.line && line < it.line + it.rows }
            }

            'z' -> {
                val z = keys.signed('z')
                removeWhere(free) { it.z == z }
            }

            else -> {
                throw Refused("EINVAL", "unknown delete $what")
            }
        }
    }

    /** A 1-based screen coordinate as a 0-based one. */
    private fun cell(
        keys: Keys,
        key: Char,
    ): Int {
        val value = keys.unsigned(key)
        if (value == 0L || value > Int.MAX_VALUE) throw Refused("EINVAL", "bad cell $key=$value")
        return (value - 1).toInt()
    }

    /** Removes the placements [predicate] matches; with [free], also the images nothing shows any more. */
    private fun removeWhere(
        free: Boolean,
        predicate: (Placement) -> Boolean,
    ) {
        val hit = store.placements.filterValues(predicate)
        for (id in hit.keys) store.unplace(id)
        if (free) freeUnused(hit.values.mapTo(HashSet()) { it.imageId })
    }

    /** Removes placement [placementId] of [client], or all of its placements when it is 0. */
    private fun deleteClient(
        client: Long,
        placementId: Long,
        free: Boolean,
    ) {
        val image = ids[client] ?: return
        if (placementId != 0L) {
            placementIds.remove(placementKey(client, placementId))?.let { store.unplace(it) }
        } else {
            store.removePlacements { it.imageId == image }
        }
        if (free) freeUnused(setOf(image))
    }

    private fun freeUnused(images: Set<Long>) {
        val shown = store.placements.values.mapTo(HashSet()) { it.imageId }
        for (image in images) if (image !in shown) store.delete(image)
    }

    /** Drops [client]'s image, placements and numbers, before it is sent again. */
    private fun forget(client: Long) {
        ids.remove(client)?.let { store.delete(it) }
        placementIds.keys.removeAll { it ushr 32 == client }
        numbers.values.removeAll { it == client }
    }

    /** An id for an `I` command, counting down from the top so it misses the small ids clients pick. */
    private fun autoId(): Long {
        while (true) {
            val id = nextAutoId
            nextAutoId = if (id == 1L) U32_MAX else id - 1
            if (ids[id]?.let { store.hasImage(it) } != true) return id
        }
    }

    /**
     * Drops map entries whose images or placements the store evicted or a delete removed. It runs
     * only once the maps hold well past what the store does, so its cost spreads over the
     * commands that grew them.
     */
    private fun prune() {
        if (entries() <= pruneLimit()) return
        ids.entries.removeAll { !store.hasImage(it.value) }
        numbers.entries.removeAll { it.value !in ids }
        placementIds.entries.removeAll { it.value !in store.placements }
    }

    private fun entries(): Int = ids.size + numbers.size + placementIds.size

    private fun pruneLimit(): Int = 4 * (store.imageCount + store.placements.size) + PRUNE_SLACK

    private fun refusedReply(
        keys: Keys,
        quiet: String?,
        refused: Refused,
    ): KittyOutcome = KittyOutcome(reply(keys, quiet, "${refused.code}:${refused.detail}", ok = false))

    /** The reply to a command that named an image, unless its quiet level drops it. */
    private fun reply(
        keys: Keys,
        quiet: String?,
        message: String,
        ok: Boolean,
    ): String? {
        val level =
            when (quiet) {
                "1" -> 1
                "2" -> 2
                else -> 0
            }
        if ((ok && level >= 1) || (!ok && level >= 2)) return null
        val id = if (assigned != 0L) assigned else keys.id('i')
        val number = keys.id('I')
        if (id == 0L && number == 0L) return null
        val ids = ArrayList<String>(3)
        if (id != 0L) ids += "i=$id"
        if (number != 0L) ids += "I=$number"
        keys.id('p').takeIf { it != 0L && id != 0L }?.let { ids += "p=$it" }
        return "\u001b_G${ids.joinToString(",")};$message\u001b\\"
    }

    /** Checks that the maps stay bounded by what the store holds; for the fuzz entry and tests. */
    internal fun checkInvariants() {
        check(entries() <= pruneLimit()) { "the id maps outgrew the store" }
        check(upload == null || !discarding) { "uploading and discarding at once" }
    }
}

/** A command refused with a reply code, as `CODE:detail`. */
private class Refused(
    val code: String,
    val detail: String,
) : Exception(detail)

private fun refusal(e: ImageError): Refused =
    when (e) {
        is ImageError.TooLarge -> Refused("EFBIG", e.message ?: "too large")
        else -> Refused("EINVAL", e.message ?: "bad image")
    }

/** The control data of one command: one-character keys and their values as sent. */
private class Keys(
    private val values: Map<Char, String>,
) {
    operator fun contains(key: Char): Boolean = key in values

    fun raw(key: Char): String? = values[key]

    /** An unsigned 32-bit value, [default] when absent. */
    fun unsigned(
        key: Char,
        default: Long = 0,
    ): Long {
        val value = values[key] ?: return default
        if (value.isEmpty() || value.length > MAX_DIGITS || !value.all { it in '0'..'9' }) throw invalid(key)
        return value.toLong().takeIf { it <= U32_MAX } ?: throw invalid(key)
    }

    /** A signed 32-bit value, 0 when absent. */
    fun signed(key: Char): Int {
        val value = values[key] ?: return 0
        val digits = value.removePrefix("-")
        if (digits.isEmpty() || digits.length > MAX_DIGITS || !digits.all { it in '0'..'9' }) throw invalid(key)
        return value.toLong().takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt() ?: throw invalid(key)
    }

    fun char(
        key: Char,
        default: Char,
    ): Char {
        val value = values[key] ?: return default
        if (value.length != 1) throw invalid(key)
        return value[0]
    }

    /** An id for a reply: the value when it is valid, else 0. */
    fun id(key: Char): Long =
        try {
            unsigned(key)
        } catch (_: Refused) {
            0
        }

    private fun invalid(key: Char) = Refused("EINVAL", "bad value for $key")

    companion object {
        /** `k=v` pairs separated by `,`; pairs that are not one key and a value are skipped. */
        fun parse(text: String): Keys {
            val values = HashMap<Char, String>()
            for (pair in text.split(',')) if (pair.length >= 2 && pair[1] == '=') values[pair[0]] = pair.substring(2)
            return Keys(values)
        }
    }
}

private fun Placement.covers(
    column: Int,
    line: Long,
): Boolean = column >= this.column && column < this.column + columns && line >= this.line && line < this.line + rows

private fun placementKey(
    client: Long,
    placement: Long,
): Long = client shl 32 or placement

private fun ceilDiv(
    a: Long,
    b: Long,
): Long = (a + b - 1) / b

private val NOTHING = KittyOutcome(null)
private val UPLOADS = setOf("t", "T", "q")
private const val G = 'G'.code.toByte()
private const val SEMICOLON = ';'.code.toByte()
private const val U32_MAX = 0xFFFF_FFFFL
private const val MAX_DIGITS = 10
private const val DEFAULT_FORMAT = 32L
private const val RGB_FORMAT = 24L
private const val RGBA_FORMAT = 32L
private const val PNG_FORMAT = 100L
private const val OPAQUE = 0xFF.toByte()
private const val MAX_ARRAY = Int.MAX_VALUE - 8L
private const val PRUNE_SLACK = 64

/** Cells a placement may span each way, far more than any screen has. */
private const val MAX_CELLS = 1L shl 16

/** Cell pixels the context may give, so cell arithmetic on 32-bit image sides stays in a Long. */
private const val MAX_CELL_PIXELS = 1 shl 12
