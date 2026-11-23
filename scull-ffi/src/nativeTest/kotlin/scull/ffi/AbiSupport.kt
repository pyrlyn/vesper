package scull.ffi

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.COpaquePointerVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.LongVar
import kotlinx.cinterop.MemScope
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toLong
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import scull.ffi.c.TT_EMPTY
import scull.ffi.c.TT_EVENT_TEXT_BODY
import scull.ffi.c.TT_OK
import scull.ffi.c.tt_event
import scull.ffi.c.tt_frame_view
import scull.ffi.c.tt_placement
import scull.ffi.c.tt_str
import scull.ffi.c.tt_term_options
import scull.ffi.c.tt_wakeup_fn
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** Options as a host built against this header fills them. */
internal fun MemScope.options(
    cols: Int = 10,
    rows: Int = 3,
): tt_term_options =
    alloc<tt_term_options>().apply {
        struct_size = sizeOf<tt_term_options>().toUInt()
        abi_version = ABI_VERSION
        this.cols = cols.toUShort()
        this.rows = rows.toUShort()
        scrollback = 100u
    }

/** [bytes] copied into [scope] as a `tt_str`. */
internal fun MemScope.str(
    target: tt_str,
    bytes: ByteArray,
) {
    val buf = allocArray<ByteVar>(bytes.size.coerceAtLeast(1))
    bytes.forEachIndexed { i, b -> buf[i] = b }
    target.ptr = buf.reinterpret()
    target.len = bytes.size.toULong()
}

/** An array of `tt_str` holding [texts]. */
internal fun MemScope.strs(texts: List<String>): CPointer<tt_str> {
    val array = allocArray<tt_str>(texts.size.coerceAtLeast(1))
    texts.forEachIndexed { i, text -> str(array[i], text.encodeToByteArray()) }
    return array
}

internal fun newTerm(
    cols: Int = 10,
    rows: Int = 3,
): COpaquePointer =
    memScoped {
        val out = alloc<COpaquePointerVar>()
        assertEquals(TT_OK, ttTermNew(options(cols, rows).ptr, out.ptr))
        assertNotNull(out.value)
    }

/** A terminal running [command] with [wakeup]; null when the spawn failed with that status. */
internal fun spawn(
    command: List<String>,
    wakeup: tt_wakeup_fn? = null,
    cols: Int = 20,
    rows: Int = 5,
): Pair<UInt, COpaquePointer?> =
    memScoped {
        val o = options(cols, rows)
        str(o.program, command.first().encodeToByteArray())
        o.args = strs(command.drop(1))
        o.args_len = (command.size - 1).toULong()
        o.wakeup = wakeup
        val out = alloc<COpaquePointerVar>()
        val status = ttTermSpawn(o.ptr, out.ptr)
        status to out.value
    }

internal fun COpaquePointer.feed(text: String) {
    val bytes = text.encodeToByteArray()
    bytes.usePinned { assertEquals(TT_OK, ttTermFeed(this, it.addressOf(0), bytes.size.toULong())) }
}

/** One run of a row, with its text cut out by the byte offsets the core gave. */
internal data class RunView(
    val col: Int,
    val cols: Int,
    val style: Int,
    val width: Int,
    val text: String,
)

internal data class StyleView(
    val fg: UInt,
    val bg: UInt,
    val underlineColor: UInt,
    val attrs: Int,
    val underline: Int,
)

/** What `tt_image_pixels` answered: `width, height, stride` and the bytes. */
internal data class PixelsView(
    val size: Triple<Int, Int, Int>,
    val bytes: List<Byte>,
) {
    companion object {
        fun of(image: COpaquePointer?): PixelsView? =
            memScoped {
                val w = alloc<UIntVar>()
                val h = alloc<UIntVar>()
                val stride = alloc<ULongVar>()
                val pixels = ttImagePixels(image, w.ptr, h.ptr, stride.ptr)
                val size = Triple(w.value.toInt(), h.value.toInt(), stride.value.toInt())
                pixels?.let { PixelsView(size, it.reinterpret<ByteVar>().readBytes(size.second * size.third).toList()) }
            }
    }
}

/** A placement with the address of its image and the pixels read through it. */
internal data class PlacementView(
    val image: Long,
    val generation: ULong,
    /** `row, col, cols, rows`. */
    val cells: List<Int>,
    /** `offset_x, offset_y, src_x, src_y, src_w, src_h`. */
    val pixelRect: List<Int>,
    val z: Int,
    val pixels: PixelsView?,
) {
    companion object {
        fun of(p: tt_placement): PlacementView =
            PlacementView(
                p.image.toLong(),
                p.generation,
                listOf(p.row, p.col.toInt(), p.cols.toInt(), p.rows.toInt()),
                listOf(p.offset_x, p.offset_y, p.src_x, p.src_y, p.src_w, p.src_h).map { it.toInt() },
                p.z,
                PixelsView.of(p.image),
            )
    }
}

/** Everything a view pointed at, copied out before the next update invalidates it. */
internal class FrameSnapshot(
    view: tt_frame_view,
) {
    val structSize = view.struct_size
    val cols = view.cols.toInt()
    val rows = view.rows.toInt()
    val updated = view.updated.toInt() == 1
    val full = view.full.toInt() == 1
    val cursor = Triple(view.cursor.row.toInt(), view.cursor.col.toInt(), view.cursor.visible.toInt() == 1)

    /** Each cell as `codepoint, style, width, flags`. */
    val cells: List<List<Int>> =
        List(view.cells_len.toInt()) {
            val c = view.cells!![it]
            listOf(c.codepoint.toInt(), c.style.toInt(), c.width.toInt(), c.flags.toInt())
        }

    /** The packed form of each cell, as a host reading `uint64_t` sees it. */
    val packed: List<Long> = List(view.cells_len.toInt()) { view.cells!!.reinterpret<LongVar>()[it] }
    val text: List<String>
    val runs: List<List<RunView>>
    val ids: List<Long>
    val generations: List<Int>

    /** Each row's link spans as `id, col, cols`. */
    val links: List<List<Triple<Int, Int, Int>>>

    init {
        val texts = mutableListOf<String>()
        val allRuns = mutableListOf<List<RunView>>()
        val rowIds = mutableListOf<Long>()
        val gens = mutableListOf<Int>()
        val spans = mutableListOf<List<Triple<Int, Int, Int>>>()
        for (r in 0 until view.lines_len.toInt()) {
            val line = view.lines!![r]
            val bytes = if (line.text_len == 0uL) ByteArray(0) else line.text!!.reinterpret<ByteVar>().readBytes(line.text_len.toInt())
            texts += bytes.decodeToString()
            allRuns +=
                List(line.runs_len.toInt()) {
                    val run = line.runs!![it]
                    val start = run.text_start.toInt()
                    RunView(
                        run.col.toInt(),
                        run.cols.toInt(),
                        run.style.toInt(),
                        run.width.toInt(),
                        bytes.copyOfRange(start, start + run.text_len.toInt()).decodeToString(),
                    )
                }
            rowIds += line.id.toLong()
            gens += line.generation.toInt()
            spans +=
                List(line.links_len.toInt()) {
                    val link = line.links!![it]
                    Triple(link.id.toInt(), link.col.toInt(), link.cols.toInt())
                }
        }
        text = texts
        runs = allRuns
        ids = rowIds
        generations = gens
        links = spans
    }

    val styles: List<StyleView> =
        List(view.styles_len.toInt()) {
            val s = view.styles!![it]
            StyleView(s.fg, s.bg, s.underline_color, s.attrs.toInt(), s.underline.toInt())
        }
    val scrolls: List<Triple<Int, Int, Int>> =
        List(view.scrolls_len.toInt()) {
            val s = view.scrolls!![it]
            Triple(s.start.toInt(), s.end.toInt(), s.from.toInt())
        }
    val dirty: List<Int> = List(view.dirty_len.toInt()) { view.dirty!![it].toInt() }
    val placements: List<PlacementView> = List(view.placements_len.toInt()) { PlacementView.of(view.placements!![it]) }

    fun cell(
        row: Int,
        col: Int,
    ): List<Int> = cells[row * cols + col]
}

internal fun update(
    frame: COpaquePointer,
    term: COpaquePointer,
): FrameSnapshot =
    memScoped {
        val view = alloc<tt_frame_view>()
        view.struct_size = sizeOf<tt_frame_view>().toUInt()
        assertEquals(TT_OK, ttFrameUpdate(frame, term, view.ptr))
        FrameSnapshot(view)
    }

internal data class EventView(
    val kind: Int,
    val serial: ULong,
    val detail: UInt,
    val textLen: Int,
    val exitCode: UInt,
    val signaled: Boolean,
    val id: ULong,
    val titleLen: Int,
)

/** The next event, or null on `TT_EMPTY`. */
internal fun poll(term: COpaquePointer): EventView? =
    memScoped {
        val event = alloc<tt_event>()
        event.struct_size = sizeOf<tt_event>().toUInt()
        when (val status = ttTermPollEvent(term, event.ptr)) {
            TT_OK -> {
                EventView(
                    event.kind.toInt(),
                    event.serial,
                    event.detail,
                    event.text_len.toInt(),
                    event.exit_code,
                    event.signaled.toInt() == 1,
                    event.id,
                    event.title_len.toInt(),
                )
            }

            TT_EMPTY -> {
                null
            }

            else -> {
                fail("poll answered $status")
            }
        }
    }

/** The text of the event [serial], with the status and the length the core reported. */
internal fun eventText(
    term: COpaquePointer,
    serial: ULong,
    cap: Int = 256,
    part: UInt = TT_EVENT_TEXT_BODY.toUInt(),
): Triple<UInt, Int, String> =
    memScoped {
        val buf = allocArray<ByteVar>(cap.coerceAtLeast(1))
        val len = alloc<ULongVar>()
        val status = ttTermEventText(term, serial, part, if (cap == 0) null else buf, cap.toULong(), len.ptr)
        val n = len.value.toInt()
        Triple(status, n, if (status == TT_OK) buf.readBytes(n).decodeToString() else "")
    }

/** Waits up to [limit] for [done], sleeping between tries; fails the test on timeout. */
internal fun waitFor(
    what: String,
    limit: Duration = 10.seconds,
    done: () -> Boolean,
) {
    val deadline = TimeSource.Monotonic.markNow() + limit
    while (!done()) {
        if (deadline.hasPassedNow()) fail("timed out waiting for $what")
        platform.posix.usleep(10_000u)
    }
}
