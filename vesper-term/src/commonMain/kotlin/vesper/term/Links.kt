package vesper.term

import vesper.grid.Grid
import vesper.grid.LinkId
import vesper.grid.Marks
import vesper.grid.Row

// OSC 8 hyperlinks: `8 ; params ; URI` opens a link, `8 ; ; ` closes it. The grid keeps which
// columns carry which id; this file numbers the links and frees the numbers no cell holds any more.
//
// Ids are capped because every one is a URI a program chose. Reclaim is a sweep, as the grid does
// for styles: a printed cell stays one store, and the walk over both screens runs only when the
// table is full. The spec (egmontkob's gist) lets cells with the same `id=` and URI hover together,
// so that pair is the key; links without an `id=` share an id when their URI is the same, which
// only joins cells that lead to the same place.

/** The most link ids alive at once. */
internal const val MAX_LINKS = 1024

/**
 * How many opens a full table refuses between sweeps that freed little. A program that keeps every
 * id on screen would otherwise cost a walk over all history per OSC 8.
 */
private const val SWEEP_EVERY = 64

/** The numbered links and the one open now. */
internal class Links {
    private data class Key(
        val param: String?,
        val uri: String,
    )

    // Slot 0 is never used, so a zero id never names a link.
    private val keys = arrayOfNulls<Key>(MAX_LINKS + 1)
    private val index = HashMap<Key, Int>()
    private val free = ArrayList<Int>()
    private var next = 1
    private var misses = 0

    /** The link printed cells get, or null for none. */
    var open: LinkId? = null

    fun uri(id: Int): String? = keys.getOrNull(id)?.uri

    fun find(
        param: String?,
        uri: String,
    ): LinkId? = index[Key(param, uri)]?.let(::LinkId)

    /** A new id for the pair, or null when the table is full. */
    fun add(
        param: String?,
        uri: String,
    ): LinkId? {
        val id =
            when {
                free.isNotEmpty() -> free.removeAt(free.lastIndex)
                next <= MAX_LINKS -> next++
                else -> return null
            }
        val key = Key(param, uri)
        keys[id] = key
        index[key] = id
        return LinkId(id)
    }

    /** Whether a refused open should sweep: the first one, then one in [SWEEP_EVERY] while sweeps free little. */
    fun shouldSweep(): Boolean = misses++ % SWEEP_EVERY == 0

    /** Frees every id not in [live]. */
    fun sweep(live: Marks) {
        var freed = 0
        for (id in 1..MAX_LINKS) {
            val key = keys[id] ?: continue
            if (live.isMarked(id)) continue
            keys[id] = null
            index.remove(key)
            free += id
            freed++
        }
        // Enough room for a while: the next time the table fills, sweep at once again.
        misses = if (freed >= SWEEP_EVERY) 0 else 1
    }
}

/** OSC 8: opens a link, or closes the open one when the URI is empty or the link is refused. */
internal fun State.link(payload: ByteArray) {
    links.open = null
    val split = payload.indexOf(SEMICOLON)
    if (split < 0 || payload.size > MAX_URI_BYTES) return
    val uri = utf8(payload, split + 1, payload.size) ?: return
    if (uri.isEmpty()) return
    val param = utf8(payload, 0, split)?.let(::linkParam) ?: return
    val id = links.find(param.ifEmpty { null }, uri) ?: newLink(param.ifEmpty { null }, uri) ?: return
    links.open = id
}

/** The `id=` value among OSC 8's `key=value:key=value` parameters, or "" when there is none. */
private fun linkParam(params: String): String =
    params
        .split(':')
        .firstOrNull { it.startsWith("id=") }
        ?.removePrefix("id=")
        .orEmpty()

private fun State.newLink(
    param: String?,
    uri: String,
): LinkId? {
    val id =
        links.add(param, uri)
            ?: if (links.shouldSweep()) {
                links.sweep(liveLinks())
                links.add(param, uri)
            } else {
                null
            }
    if (id != null) events.push(Event.Link(id, uri))
    return id
}

/** Every id a row of either screen holds, or the open link. */
private fun State.liveLinks(): Marks {
    val marks = Marks(MAX_LINKS + 1)
    markRows(grid, marks)
    markRows(alt, marks)
    links.open?.let { marks.mark(it.value) }
    return marks
}

private fun markRows(
    grid: Grid,
    marks: Marks,
) {
    for (i in 0 until grid.historyLength + grid.screenRows) {
        val row = grid.row(i) ?: continue
        for (span in row.links) marks.mark(span.link.value)
    }
}

/** Cells [start] until [end] of [row], just printed, take the open link or lose the one they had. */
internal fun State.stampLink(
    row: Row,
    start: Int,
    end: Int,
) {
    val link = links.open
    if (link != null) row.setLink(start, end, link) else row.clearLinks(start, end)
}
