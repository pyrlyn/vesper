package scull.term

import scull.parser.Osc

// Polled host events, capped because they are PTY bytes. Separate because the queue is the one
// place the core hands the host something other than cells and replies.
//
// The bell is coalesced: several BELs are one event, and either door (`takeBell` or `pollEvent`)
// consumes it. Of the OSCs only the titles (0, 1, 2) are read here.

/** The most events held until the host polls; newer ones are dropped. */
internal const val MAX_EVENTS = 32

/** The most bytes of text one event carries; a longer title is dropped, not cut. */
internal const val MAX_EVENT_TEXT = 1024

private const val SEMICOLON = ';'.code.toByte()

/** What an OSC title names. [code] is the OSC number. */
public enum class TitleTarget(
    public val code: Int,
) {
    /** OSC 0: icon name and window title. */
    Both(0),

    /** OSC 1: icon name. */
    Icon(1),

    /** OSC 2: window title. */
    Window(2),
}

/** Something the host acts on, read with [Terminal.pollEvent]. */
public sealed interface Event {
    /** BEL, however many arrived since the last one was taken. */
    public data object Bell : Event

    /** OSC 0, 1 or 2. */
    public data class Title(
        public val target: TitleTarget,
        public val text: String,
    ) : Event
}

/** The queue plus the coalesced bell flag. */
internal class EventQueue {
    private val items = ArrayDeque<Event>()
    private var bell = false

    /** Queues [event], or drops it when full. */
    fun push(event: Event): Boolean {
        if (items.size >= MAX_EVENTS) return false
        items.addLast(event)
        return true
    }

    /** One BEL, however many arrived. A full queue still remembers it. */
    fun ring() {
        if (bell) return
        bell = true
        if (items.size < MAX_EVENTS) items.addLast(Event.Bell)
    }

    fun poll(): Event? {
        val event = items.removeFirstOrNull()
        if (event != null) {
            if (event is Event.Bell) bell = false
            return event
        }
        if (!bell) return null
        bell = false
        return Event.Bell
    }

    /** Either door takes the one bell, so a later poll does not ring again. */
    fun takeBell(): Boolean {
        val had = bell
        bell = false
        items.removeAll { it is Event.Bell }
        return had
    }
}

/** OSC: `Ps ; Pt`. Only the titles are read; every other one is ignored. */
internal fun State.dispatchOsc(osc: Osc) {
    endCluster()
    // A one-digit code followed by `;`.
    if (osc.size < 2 || osc[1] != SEMICOLON) return
    val target = TitleTarget.entries.firstOrNull { it.code == osc[0] - '0'.code } ?: return
    val length = osc.size - 2
    if (length > MAX_EVENT_TEXT) return
    val text = ByteArray(length) { osc[it + 2] }
    try {
        events.push(Event.Title(target, text.decodeToString(throwOnInvalidSequence = true)))
    } catch (_: CharacterCodingException) {
        // A title that is not UTF-8 is dropped rather than shown with replacement characters.
    }
}
