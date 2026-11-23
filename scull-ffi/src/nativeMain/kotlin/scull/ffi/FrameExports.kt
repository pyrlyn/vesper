package scull.ffi

import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointed
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.LongVar
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.UShortVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import platform.posix.memcpy
import scull.ffi.c.TT_COLOR_INDEXED
import scull.ffi.c.TT_COLOR_RGB
import scull.ffi.c.TT_INVALID
import scull.ffi.c.tt_frame_view
import scull.ffi.c.tt_row
import scull.ffi.c.tt_run
import scull.ffi.c.tt_scroll
import scull.ffi.c.tt_style
import scull.grid.Color
import scull.grid.Style
import scull.pty.withLock
import scull.term.Frame
import scull.term.FrameRow
import kotlin.native.CName

private const val RED_SHIFT = 16
private const val GREEN_SHIFT = 8

/** A native array that only grows, reused across updates so a steady frame allocates nothing. */
private class Grow<T : CPointed>(
    private val make: (Int) -> CPointer<T>,
) {
    var ptr: CPointer<T>? = null
        private set
    private var cap = 0

    /** Room for [n] entries; the old contents are gone when it had to grow. */
    fun ensure(n: Int): CPointer<T>? {
        if (n > cap) {
            free()
            cap = maxOf(n, cap * 2)
            ptr = make(cap)
        }
        return ptr
    }

    fun free() {
        ptr?.let { nativeHeap.free(it.rawValue) }
        ptr = null
        cap = 0
    }
}

/**
 * What a `tt_frame*` points to: the Kotlin frame and the C views of it. A view's pointers are
 * these buffers, valid until the next update; rows the damage leaves alone keep theirs untouched.
 */
internal class FrameHandle {
    val frame = Frame()
    private val cells = Grow<LongVar> { nativeHeap.allocArray(it) }
    private val lines = Grow<tt_row> { nativeHeap.allocArray(it) }
    private val styles = Grow<tt_style> { nativeHeap.allocArray(it) }
    private val scrolls = Grow<tt_scroll> { nativeHeap.allocArray(it) }
    private val dirty = Grow<UShortVar> { nativeHeap.allocArray(it) }
    private val runs = ArrayList<Grow<tt_run>>()
    private val texts = ArrayList<Grow<UByteVar>>()

    /** Copies the rows [frame]'s last update changed into the C buffers and describes them in [view]. */
    fun fill(
        view: tt_frame_view,
        updated: Boolean,
    ) {
        val cols = frame.cols
        val rows = frame.rows
        val damage = frame.damage
        cells.ensure(cols * rows)
        lines.ensure(rows)
        while (runs.size < rows) {
            runs += Grow { nativeHeap.allocArray(it) }
            texts += Grow { nativeHeap.allocArray(it) }
        }
        // A scrolled row is rewritten rather than moved: the Kotlin frame already moved it, and a
        // copy costs the same as a move here.
        val rewrite = BooleanArray(rows)
        damage.scrolls.forEach { s -> s.rows.forEach { rewrite[it] = true } }
        damage.dirty.forEach { rewrite[it] = true }
        for (r in 0 until rows) if (rewrite[r]) copyRow(r, cols)
        copyStyles()
        val scrollList = damage.scrolls
        val scrollOut = scrolls.ensure(scrollList.size)
        scrollList.forEachIndexed { i, s ->
            val out = scrollOut!![i]
            out.start = s.start.toUShort()
            out.end = s.end.toUShort()
            out.from = s.from.toUShort()
        }
        val dirtyList = damage.dirty
        val dirtyOut = dirty.ensure(dirtyList.size)
        dirtyList.forEachIndexed { i, r -> dirtyOut!![i] = r.toUShort() }

        view.cols = cols.toUShort()
        view.rows = rows.toUShort()
        view.updated = if (updated) 1u else 0u
        view.full = if (damage.isFull) 1u else 0u
        val cursor = frame.cursor
        view.cursor.visible = if (cursor != null) 1u else 0u
        view.cursor.row = (cursor?.row ?: 0).toUShort()
        view.cursor.col = (cursor?.col ?: 0).toUShort()
        view.cells = cells.ptr?.reinterpret()
        view.cells_len = (cols * rows).toULong()
        view.lines = lines.ptr
        view.lines_len = rows.toULong()
        view.styles = styles.ptr
        view.styles_len = frame.styleCount.toULong()
        view.scrolls = scrolls.ptr
        view.scrolls_len = scrollList.size.toULong()
        view.dirty = dirty.ptr
        view.dirty_len = dirtyList.size.toULong()
    }

    private fun copyRow(
        r: Int,
        cols: Int,
    ) {
        val out = checkNotNull(cells.ptr)
        val base = r * cols
        for (c in 0 until cols) out[base + c] = frame.packedCell(r, c)
        val line = checkNotNull(lines.ptr)[r]
        val stamp = frame.stamp(r)
        line.id = (stamp?.id?.value ?: 0L).toULong()
        line.generation = (stamp?.generation ?: 0).toUInt()
        val row = frame.row(r)
        val text = row?.text?.encodeToByteArray() ?: ByteArray(0)
        val textOut = texts[r].ensure(text.size)
        if (text.isNotEmpty()) text.usePinned { memcpy(textOut, it.addressOf(0), text.size.toULong()) }
        line.text = textOut
        line.text_len = text.size.toULong()
        val runList = row?.runs.orEmpty()
        val runOut = runs[r].ensure(runList.size)
        if (row != null) writeRuns(row, runOut)
        line.runs = runOut
        line.runs_len = runList.size.toULong()
    }

    /** Runs with their text as UTF-8 byte ranges; the Kotlin frame counts UTF-16 units. */
    private fun writeRuns(
        row: FrameRow,
        out: CPointer<tt_run>?,
    ) {
        val text = row.text
        var unit = 0
        var byte = 0
        row.runs.forEachIndexed { i, run ->
            if (run.textStart < unit) {
                unit = 0
                byte = 0
            }
            byte += utf8Length(text, unit, run.textStart)
            val length = utf8Length(text, run.textStart, run.textStart + run.textLength)
            val o = out!![i]
            o.col = run.col.toUShort()
            o.cols = run.cols.toUShort()
            o.style = run.style.toUShort()
            o.width = run.width.toUByte()
            o.text_start = byte.toUInt()
            o.text_len = length.toUInt()
            unit = run.textStart + run.textLength
            byte += length
        }
    }

    private fun copyStyles() {
        val count = frame.styleCount
        val out = styles.ensure(count) ?: return
        for (id in 0 until count) {
            val style = frame.style(id) ?: Style.DEFAULT
            val o = out[id]
            o.fg = style.fg.abi()
            o.bg = style.bg.abi()
            o.underline_color = style.underlineColor.abi()
            o.attrs = style.attrs.toUShort()
            o.underline = style.underline.ordinal.toUByte()
        }
    }

    fun free() {
        listOf(cells, lines, styles, scrolls, dirty).forEach { it.free() }
        runs.forEach { it.free() }
        texts.forEach { it.free() }
    }
}

/** UTF-8 bytes of units [from] until [to] of [text], as `encodeToByteArray` writes them (a lone surrogate as U+FFFD). */
internal fun utf8Length(
    text: String,
    from: Int,
    to: Int,
): Int {
    var bytes = 0
    var i = from
    while (i < to) {
        val ch = text[i]
        bytes +=
            when {
                ch.code < 0x80 -> {
                    1
                }

                ch.code < 0x800 -> {
                    2
                }

                ch.isHighSurrogate() && i + 1 < to && text[i + 1].isLowSurrogate() -> {
                    i++
                    4
                }

                else -> {
                    3
                }
            }
        i++
    }
    return bytes
}

/** A colour as `tt_style` spells it: the kind in the top byte. */
private fun Color.abi(): UInt =
    when {
        isIndexed -> TT_COLOR_INDEXED or index.toUInt()
        isRgb -> TT_COLOR_RGB or (red shl RED_SHIFT or (green shl GREEN_SHIFT) or blue).toUInt()
        else -> 0u
    }

private fun CPointer<*>?.frameHandle(): FrameHandle? = this?.asStableRef<FrameHandle>()?.get()

@CName("tt_frame_new")
public fun ttFrameNew(): COpaquePointer? =
    try {
        StableRef.create(FrameHandle()).asCPointer()
    } catch (_: Throwable) {
        null
    }

@CName("tt_frame_update")
public fun ttFrameUpdate(
    frame: COpaquePointer?,
    term: COpaquePointer?,
    view: CPointer<tt_frame_view>?,
): UInt {
    val handle = frame.frameHandle() ?: return TT_INVALID
    return guarded(term.term()) { owner ->
        // Checked first, so a refused call loses no damage.
        if (view == null || structSize(view) < sizeOf<tt_frame_view>()) throw Invalid()
        owner.rearm()
        val updated = owner.lock.withLock { owner.terminal.updateFrame(handle.frame) }
        memScoped {
            val out = alloc<tt_frame_view>()
            handle.fill(out, updated)
            writeStruct(out, view, sizeOf<tt_frame_view>().toInt())
        }
        OK
    }
}

@CName("tt_frame_free")
public fun ttFrameFree(frame: COpaquePointer?) {
    val ref = frame?.asStableRef<FrameHandle>() ?: return
    try {
        ref.get().free()
    } catch (_: Throwable) {
        // Freeing must not fail; at worst the buffers leak.
    } finally {
        ref.dispose()
    }
}
