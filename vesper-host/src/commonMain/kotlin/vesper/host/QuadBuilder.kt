package vesper.host

import vesper.grid.Attrs
import vesper.grid.Color
import vesper.grid.Style
import vesper.grid.Underline
import vesper.term.Frame
import kotlin.math.floor
import kotlin.math.max

private const val UNDERLINE_BASE = 0x110000
private const val CURSOR_GLYPHS = 16

/** The sprite code of an underline kind: past Unicode's last code point, so no character has it. */
public fun underlineSprite(kind: Underline): Int = UNDERLINE_BASE + kind.ordinal

/** The underline kind sprite [code] draws, or null when it is no underline. */
public fun spriteUnderline(code: Int): Underline? = Underline.entries.getOrNull(code - UNDERLINE_BASE)?.takeIf { it != Underline.None }

/** One row's quads in row pixels: [bg] goes under every row's [fg]. */
public class RowQuads {
    public val bg: Quads = Quads()
    public val fg: Quads = Quads()

    /** Bumped each time the row is built, so a GPU copy can tell it is stale. */
    public var version: Long = 0
        internal set
    internal val shelves = HashSet<Int>()
}

/**
 * Turns a frame into quads, the part of rendering no graphics API owns: every cell background, glyph,
 * sprite and decoration is one quad. Rows are built only when the damage says they changed, kept in
 * slots and reached through a row-to-slot table, so a scroll only reorders the table. A renderer
 * copies each slot to the GPU when its [RowQuads.version] moves, and draws row by row.
 */
public class QuadBuilder(
    private val palette: Palette,
    private val glyphs: GlyphSource,
    cache: GlyphCache,
    metrics: CellMetrics,
    scale: Double,
) {
    /** Where the glyphs live; replaced by [rescale]. */
    public var cache: GlyphCache = cache
        private set
    public var metrics: CellMetrics = metrics
        private set
    public var scale: Double = scale
        private set
    public var cols: Int = 0
        private set
    public var rows: Int = 0
        private set

    /** Backgrounds a row may hold: at most one per cell. */
    public var capacityBg: Int = 0
        private set

    /** Glyphs and decorations a row may hold; the cap keeps a flood of combining marks from growing the buffers. */
    public var capacityFg: Int = 0
        private set

    /** One per slot; [slots] says which row each holds. */
    public var content: Array<RowQuads> = emptyArray()
        private set
    public var slots: RowSlots = RowSlots(0)
        private set
    private var frameNumber = 0L
    private var version = 0L
    private var needsRebuild = true

    /** Glyphs from now on are drawn at [scale] into [cache], which holds nothing rasterised at the old one. */
    public fun rescale(
        scale: Double,
        metrics: CellMetrics,
        cache: GlyphCache,
    ) {
        this.scale = scale
        this.metrics = metrics
        this.cache = cache
        needsRebuild = true
    }

    /**
     * Builds the rows of [frame] that [sources] says changed. True when the grid changed size: the
     * slots are new, and so must be the GPU's copy of them.
     */
    public fun update(
        frame: Frame,
        sources: RowSources,
    ): Boolean {
        val resized = frame.cols != cols || frame.rows != rows
        if (resized) allocate(frame.cols, frame.rows)
        frameNumber++
        val dirty =
            if (needsRebuild || sources.sources.size != rows) (0 until rows).toList() else slots.remap(sources.sources)
        needsRebuild = false
        sources.drawn(rows)
        // Shelves that reused rows draw from must survive this frame's evictions.
        for (slot in slots.slotOfRow) for (shelf in content[slot].shelves) cache.touch(shelf, frameNumber)
        var generation = cache.generation
        dirty.forEach { build(it, frame) }
        // A grown atlas dropped every entry, so rows built before the growth point at texels that are gone.
        repeat(4) {
            if (cache.generation == generation) return resized
            generation = cache.generation
            for (row in 0 until rows) build(row, frame)
        }
        return resized
    }

    /** The left edge of column [col] in pixels, on the pixel grid. */
    public fun x(col: Int): Float = floor(col * metrics.width).toFloat()

    /** The cursor's quads in grid pixels: what goes under the text, and what goes over it. */
    public fun cursorQuads(
        frame: Frame,
        focused: Boolean,
    ): Pair<Quads, Quads> {
        val under = Quads(4)
        val over = Quads(4)
        val cursor = frame.cursor ?: return under to over
        if (cursor.row !in 0 until rows || cursor.col !in 0 until cols) return under to over
        val cell = frame.cell(cursor.row, cursor.col)
        val top = (cursor.row * metrics.height).toFloat()
        val left = x(cursor.col)
        val right = x(cursor.col + max(1, cell.width))
        val height = metrics.height.toFloat()
        val t = metrics.thickness.toFloat()
        val color = shaderColor(palette.cursor)
        if (!focused) {
            under.add(left, top, right - left, t, color)
            under.add(left, top + height - t, right - left, t, color)
            under.add(left, top, t, height, color)
            under.add(right - t, top, t, height, color)
            return under to over
        }
        // The block goes over the backgrounds; the glyphs under it are drawn again on top in the background colour.
        under.add(left, top, right - left, height, color)
        val glyphs = content[slots.slotOfRow[cursor.row]].fg
        for (i in 0 until glyphs.count) {
            val middle = glyphs.x(i) + glyphs.w(i) / 2
            if (glyphs.kind(i) == TINTED && middle >= left && middle < right && over.count < CURSOR_GLYPHS) {
                over.addCopy(glyphs, i, top, shaderColor(palette.background))
            }
        }
        return under to over
    }

    /** Underlines under the shown preedit in grid pixels, added to [out]: one per clause with a gap between them, thick under the target. */
    public fun preeditQuads(
        frame: Frame,
        out: Quads,
    ) {
        val preedit = frame.preedit ?: return
        if (preedit.row !in 0 until rows) return
        val t = metrics.thickness.toFloat()
        val bottom = ((preedit.row + 1) * metrics.height).toFloat()
        val color = shaderColor(palette.foreground)
        for (line in preeditLines(preedit)) {
            val height = if (line.thick) 2 * t else t
            val left = x(line.col) + t
            out.add(left, bottom - height, x(line.col + line.cols) - t - left, height, color)
        }
    }

    private fun allocate(
        cols: Int,
        rows: Int,
    ) {
        this.cols = cols
        this.rows = rows
        capacityBg = cols
        capacityFg = cols * 4 + 16
        content = Array(rows) { RowQuads() }
        slots = RowSlots(rows)
        needsRebuild = true
    }

    private fun entry(key: Long): AtlasEntry? = cache.entry(key, frameNumber) { glyphs.rasterize(key, metrics, scale) }

    private fun build(
        row: Int,
        frame: Frame,
    ) {
        val out = content[slots.slotOfRow[row]]
        out.bg.clear()
        out.fg.clear()
        out.shelves.clear()
        // Neighbouring cells of one colour share a quad.
        var start = 0
        var current = palette.background
        for (col in 0..cols) {
            val bg =
                if (col ==
                    cols
                ) {
                    palette.background
                } else {
                    frame.cell(row, col).let { palette.colors(style(frame, it.style), it.flags).bg }
                }
            if (bg == current && col < cols) continue
            if (current != palette.background && col > start) {
                out.bg.add(x(start), 0f, x(col) - x(start), metrics.height.toFloat(), shaderColor(current))
            }
            start = col
            current = bg
        }
        val line = frame.row(row)
        if (line != null) for (run in line.runs) addRun(run.col, run.cols, run.width, line.runText(run), style(frame, run.style), out)
        out.version = ++version
    }

    private fun style(
        frame: Frame,
        id: Int,
    ): Style = frame.style(id) ?: Style.DEFAULT

    private fun addRun(
        first: Int,
        count: Int,
        width: Int,
        runText: String,
        style: Style,
        out: RowQuads,
    ) {
        if (style.attrs and Attrs.HIDDEN != 0) return
        val fg = palette.colors(style).fg
        val color = shaderColor(fg)
        val face = face(style.attrs and Attrs.BOLD != 0, style.attrs and Attrs.ITALIC != 0)
        for (glyph in glyphs.shape(runText, face, width)) {
            if (out.fg.count >= capacityFg) break
            val entry = entry(glyph.key)?.takeIf { !it.isEmpty } ?: continue
            val col = (first + glyph.column) * metrics.width
            // Sprites start on the cell's pixel edge, as backgrounds do.
            val pen = if (isSpriteKey(glyph.key)) floor(col) else snap(col + glyph.dx * scale)
            val baseline = metrics.baseline - snap(glyph.dy * scale)
            out.fg.add(
                (pen + entry.left).toFloat(),
                (baseline - entry.top).toFloat(),
                entry.width.toFloat(),
                entry.height.toFloat(),
                if (entry.isColor) shaderColor(0xFFFFFF) else color,
                if (entry.isColor) COLOR_GLYPH else TINTED,
                entry.x,
                entry.y,
            )
            out.shelves.add(entry.shelf)
        }
        val end = first + count
        if (style.underline != Underline.None) {
            val entry = entry(spriteKey(underlineSprite(style.underline)))?.takeIf { !it.isEmpty }
            if (entry != null) {
                val tint = if (style.underlineColor == Color.DEFAULT) color else shaderColor(palette.resolve(style.underlineColor, fg))
                for (col in first until end) {
                    if (out.fg.count >= capacityFg) break
                    out.fg.add(
                        x(col),
                        (metrics.baseline - entry.top).toFloat(),
                        entry.width.toFloat(),
                        entry.height.toFloat(),
                        tint,
                        TINTED,
                        entry.x,
                        entry.y,
                    )
                }
                out.shelves.add(entry.shelf)
            }
        }
        val lineAt = { y: Double -> out.fg.add(x(first), snap(y).toFloat(), x(end) - x(first), metrics.thickness.toFloat(), color) }
        if (style.attrs and Attrs.STRIKE != 0 && out.fg.count < capacityFg) lineAt(metrics.baseline - metrics.strike)
        if (style.attrs and Attrs.OVERLINE != 0 && out.fg.count < capacityFg) lineAt(0.0)
    }
}
