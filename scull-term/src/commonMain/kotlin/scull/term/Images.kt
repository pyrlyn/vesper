package scull.term

import scull.grid.Grid
import scull.grid.TrackPoint
import scull.image.Extent
import scull.image.ITerm2Images
import scull.image.ImageError
import scull.image.ImageStore
import scull.image.InlineImage
import scull.image.KittyContext
import scull.image.KittyGraphics
import scull.image.MAX_CELL_PIXELS
import scull.image.Placement
import scull.image.RgbaImage
import scull.image.SixelDecoder
import scull.parser.Csi
import scull.parser.End
import scull.parser.MAX_APC_BYTES
import scull.parser.Osc

// Inline images on the grid: OSC 1337 `File=` (iTerm2), DCS `q` (sixel) and APC `G` (kitty) go to
// scull-image's decoders, the image is placed at the cursor, and the cursor moves as each protocol
// says. Separate from the dispatch files because images live beside the grid, not in its cells.
//
// Placements are anchored to absolute lines (Grid.firstLine plus the ring index, the numbering
// selections use), so scrolling the whole screen moves nothing; only eviction from the ring, ED 3
// and reflow touch them. A partial region scroll leaves images where they are, as the Rust core this
// replaced did. Cursor moves follow WezTerm's reading of the protocols (term/src/terminalstate/
// image.rs, behaviour only): sixel leaves the cursor under the image's left edge, iTerm2 and kitty
// right of its bottom-right cell. Leaving the alternate screen drops its images, as foot does.

/** Cell size assumed until the host reports one: the classic 8 x 16 VGA text cell. */
internal const val DEFAULT_CELL_WIDTH = 8
internal const val DEFAULT_CELL_HEIGHT = 16

/** Pixel bytes each screen keeps: kitty's quota per screen (graphics protocol, "storage quotas"). */
public const val IMAGE_QUOTA_BYTES: Long = 320L shl 20

/** Most bytes one image file has after base64, and again after zlib. */
internal const val MAX_IMAGE_FILE_BYTES = 64 shl 20

/** Most RGBA bytes one decoded image has. */
internal const val MAX_IMAGE_PIXEL_BYTES = 128L shl 20

/** Widest or tallest sixel in pixels; [MAX_IMAGE_PIXEL_BYTES] bounds the area. */
private const val MAX_SIXEL_SIDE = 1 shl 14

/** Most cells an image spans each way, and lines the cursor walks past one. */
private const val MAX_IMAGE_CELLS = 1 shl 12

private const val SIXEL_FINAL = 'q'.code

/** DCS `P2` of a sixel whose unpainted pixels stay transparent. */
private const val SIXEL_TRANSPARENT = 1

/** Behind unpainted sixel pixels otherwise: the core does not know the host's background. */
private const val SIXEL_BACKGROUND = 0x000000

private const val RGBA = 4
private const val PERCENT = 100
private const val APC_INITIAL = 256

/** Above this the APC buffer is let go after a command, so one large upload does not pin it. */
private const val APC_KEEP = 64 shl 10

/** The images of one screen: its store, the kitty decoder that writes to it, and which images sixel and iTerm2 placed. */
internal class ScreenImages {
    val store = ImageStore(IMAGE_QUOTA_BYTES)
    val kitty = KittyGraphics(store, MAX_IMAGE_FILE_BYTES, MAX_IMAGE_PIXEL_BYTES)

    /** Images with one placement that nothing can place again: they go with it. */
    val inline = HashSet<Long>()

    /** Drops the placements wholly above [firstLine], which the ring no longer holds. */
    fun prune(firstLine: Long) {
        if (store.placements.isNotEmpty()) removeWhere { it.line + it.rows <= firstLine }
    }

    /** Drops the placements [predicate] matches, and the sixel and iTerm2 images they showed. */
    fun removeWhere(predicate: (Placement) -> Boolean) {
        val shown =
            store.placements.values
                .filter(predicate)
                .map { it.imageId }
        if (shown.isEmpty()) return
        store.removePlacements(predicate)
        for (id in shown) if (inline.remove(id)) store.delete(id)
    }

    fun unplace(id: Long) {
        val p = store.placements[id] ?: return
        store.unplace(id)
        if (inline.remove(p.imageId)) store.delete(p.imageId)
    }
}

/** An APC's payload, collected for the kitty decoder, which takes one command whole. */
internal class ApcBuffer {
    private var bytes = ByteArray(APC_INITIAL)
    private var size = 0

    fun clear() {
        if (bytes.size > APC_KEEP) bytes = ByteArray(APC_INITIAL)
        size = 0
    }

    fun append(
        from: ByteArray,
        start: Int,
        end: Int,
    ) {
        val n = end - start
        // The parser already stops at this cap; the buffer does not trust that.
        if (n <= 0 || size + n > MAX_APC_BYTES) return
        if (size + n > bytes.size) bytes = bytes.copyOf(maxOf(size + n, minOf(bytes.size * 2, MAX_APC_BYTES)))
        from.copyInto(bytes, size, start, end)
        size += n
    }

    fun take(): ByteArray = bytes.copyOf(size).also { clear() }
}

/** DCS hook: a sixel image begins when the header is `P1;P2;P3 q`. */
internal fun State.sixelStart(header: Csi) {
    endCluster()
    sixel = null
    if (header.finalByte != SIXEL_FINAL || header.intermediates.size != 0 || header.private != 0) return
    val transparent =
        header.params
            .groups()
            .getOrNull(1)
            ?.firstOrNull() == SIXEL_TRANSPARENT
    sixel = SixelDecoder(transparent, MAX_SIXEL_SIDE, MAX_SIXEL_SIDE, MAX_IMAGE_PIXEL_BYTES, SIXEL_BACKGROUND)
}

/** DCS end: a cut image still shows what arrived; a cancelled one shows nothing. */
internal fun State.sixelEnd(end: End) {
    val decoder = sixel ?: return
    sixel = null
    if (end == End.Cancelled) return
    val pixels =
        try {
            padToCells(decoder.finish())
        } catch (_: ImageError) {
            return
        }
    val column = col
    val lines = cellsFor(pixels.height.toLong(), cellHeight)
    if (!showInline(pixels, cellsFor(pixels.width.toLong(), cellWidth), lines)) return
    advanceLines(lines)
    goto(row, column)
}

/** OSC 1337: iTerm2's `File=` and multipart transfers; its other commands are ignored. */
internal fun State.inlineImage(osc: Osc) {
    val image =
        try {
            iterm.accept(osc.toByteArray())
        } catch (_: ImageError) {
            null
        } ?: return
    val auto = image.width == Extent.Auto && image.height == Extent.Auto
    val pixels = if (auto) padToCells(image.pixels) else image.pixels
    val (columns, lines) = inlineCells(image, pixels)
    val column = col
    if (showInline(pixels, columns, lines)) movePast(column, columns, lines)
}

/** APC end: runs a whole kitty command, queues its reply and moves the cursor. A cut or cancelled one is dropped. */
internal fun State.kittyEnd(end: End) {
    val bytes = apc.take()
    if (end != End.Complete || bytes.isEmpty()) return
    val top = grid.screenTopLine
    val context = KittyContext(col, top + row, cellWidth, cellHeight, top, cols, rows)
    val outcome = images.kitty.accept(bytes, context)
    outcome.reply?.let(replies::push)
    if (outcome.cursorColumns > 0 && outcome.cursorRows > 0) movePast(col, outcome.cursorColumns, outcome.cursorRows)
}

/** Sets the cell size images are measured with; false, and nothing changes, when a side is outside 1..[MAX_CELL_PIXELS]. */
internal fun State.setCellSize(
    width: Int,
    height: Int,
): Boolean {
    if (width !in 1..MAX_CELL_PIXELS || height !in 1..MAX_CELL_PIXELS) return false
    cellWidth = width
    cellHeight = height
    return true
}

/** ED 2: images on the screen go with the text, as in kitty and foot. */
internal fun State.eraseScreenImages() {
    val top = grid.screenTopLine
    images.removeWhere { it.line < top + rows && it.line + it.rows > top }
}

/** The shown screen's placements in viewport rows into [out], lowest z first and in placement order within a z. */
internal fun State.visiblePlacements(out: MutableList<FramePlacement>) {
    val top = grid.screenTopLine - grid.displayOffset
    val store = images.store
    for (p in store.placements.values) {
        val r = p.line - top
        if (r >= rows || r + p.rows <= 0) continue
        val image = store[p.imageId] ?: continue
        val crop = p.crop
        val pixels = image.pixels
        out +=
            FramePlacement(
                image = image,
                row = r.toInt(),
                col = p.column,
                cols = p.columns,
                rows = p.rows,
                sourceX = crop?.x ?: 0,
                sourceY = crop?.y ?: 0,
                sourceWidth = crop?.width ?: pixels.width,
                sourceHeight = crop?.height ?: pixels.height,
                z = p.z,
                offsetX = p.offsetX,
                offsetY = p.offsetY,
            )
    }
    out.sortBy { it.z }
}

/**
 * Stores [pixels] and places them at the cursor over [columns] by [lines] cells. Earlier sixel and
 * iTerm2 placements it covers whole go, as in foot, so an animation drawn in place does not pile up.
 */
private fun State.showInline(
    pixels: RgbaImage,
    columns: Int,
    lines: Int,
): Boolean {
    endCluster()
    val shown = images
    val image =
        try {
            shown.store.add(pixels)
        } catch (_: ImageError) {
            return false
        }
    // The store evicts on its own; ids it dropped need not be remembered.
    shown.inline.retainAll { shown.store.hasImage(it) }
    val line = grid.screenTopLine + row
    val column = col
    shown.removeWhere { p ->
        p.imageId in shown.inline &&
            p.z == 0 &&
            p.line >= line &&
            p.column >= column &&
            p.line + p.rows <= line + lines &&
            p.column + p.columns <= column + columns
    }
    shown.inline += image.id
    return shown.store.place(Placement(image.id, column, line, columns, lines)) != null
}

/** Moves down [lines] lines, scrolling at the bottom margin as IND does. */
private fun State.advanceLines(lines: Int) {
    repeat(lines.coerceAtMost(MAX_IMAGE_CELLS)) { index() }
}

/** Puts the cursor right of the bottom-right cell of an image placed at [column]. */
private fun State.movePast(
    column: Int,
    columns: Int,
    lines: Int,
) {
    advanceLines(lines - 1)
    goto(row, column + columns.coerceAtMost(MAX_IMAGE_CELLS))
}

/** Cells [px] pixels take at [cell] pixels a cell, at least one and at most [MAX_IMAGE_CELLS]. */
private fun cellsFor(
    px: Long,
    cell: Int,
): Int = ((px + cell - 1) / cell).coerceIn(1L, MAX_IMAGE_CELLS.toLong()).toInt()

/**
 * Grows [image] with transparent pixels to whole cells, so a host that scales an image to its cells
 * shows it at its own size. Kept as it is when the padded image would pass the pixel cap.
 */
private fun State.padToCells(image: RgbaImage): RgbaImage {
    val w = (image.width.toLong() + cellWidth - 1) / cellWidth * cellWidth
    val h = (image.height.toLong() + cellHeight - 1) / cellHeight * cellHeight
    if (w == image.width.toLong() && h == image.height.toLong()) return image
    // Checked before allocating: the cell size comes from the host and the image from the program.
    if (w * h * RGBA > MAX_IMAGE_PIXEL_BYTES) return image
    val out = ByteArray((w * h * RGBA).toInt())
    val from = image.width * RGBA
    val to = w.toInt() * RGBA
    for (y in 0 until image.height) image.rgba.copyInto(out, y * to, y * from, y * from + from)
    return RgbaImage(w.toInt(), h.toInt(), out)
}

/** The pixels an iTerm2 size asks for, null for `auto`. */
private fun requested(
    extent: Extent,
    cell: Int,
    screenCells: Int,
): Long? =
    when (extent) {
        Extent.Auto -> null
        is Extent.Cells -> extent.count.toLong() * cell
        is Extent.Pixels -> extent.count.toLong()
        is Extent.Percent -> screenCells.toLong() * cell * extent.percent / PERCENT
    }

/** Cells for iTerm2's `width` and `height`; with both given and `preserveAspectRatio`, the image is fitted inside them. */
private fun State.inlineCells(
    image: InlineImage,
    pixels: RgbaImage,
): Pair<Int, Int> {
    val w = pixels.width.toLong()
    val h = pixels.height.toLong()
    val wantW = requested(image.width, cellWidth, cols)
    val wantH = requested(image.height, cellHeight, rows)
    // Sides are at least 1 and the wanted sizes below 2^44, so no product leaves a Long.
    val (pw, ph) =
        when {
            wantW != null && wantH != null && !image.preserveAspectRatio -> wantW to wantH
            wantW != null && (wantH == null || wantW * h <= wantH * w) -> wantW to wantW * h / w
            wantH != null -> wantH * w / h to wantH
            else -> w to h
        }
    return cellsFor(pw, cellWidth) to cellsFor(ph, cellHeight)
}

/** Where a screen's placements sit before a resize: each one's id, how far its top is above the ring, and its cell as a point the reflow moves. */
internal class Anchors(
    val ids: LongArray,
    val above: LongArray,
    val points: List<TrackPoint>,
)

/** Anchors of this screen's placements on [grid], whose rows are about to be reflowed. */
internal fun ScreenImages.anchors(grid: Grid): Anchors {
    prune(grid.firstLine)
    val first = grid.firstLine
    val entries = store.placements.entries.toList()
    return Anchors(
        LongArray(entries.size) { entries[it].key },
        LongArray(entries.size) { (first - entries[it].value.line).coerceAtLeast(0) },
        entries.map { (_, p) -> TrackPoint((p.line - first).coerceAtLeast(0).toInt(), p.column) },
    )
}

/** Moves the placements [anchors] named to where the reflow took their points ([moved]); one whose cell was lost goes. */
internal fun ScreenImages.reanchor(
    grid: Grid,
    anchors: Anchors,
    moved: List<TrackPoint>,
) {
    val first = grid.firstLine
    anchors.ids.forEachIndexed { i, id ->
        val point = moved[i]
        if (point.clamped) unplace(id) else store.move(id, point.col, first + point.row - anchors.above[i])
    }
}
