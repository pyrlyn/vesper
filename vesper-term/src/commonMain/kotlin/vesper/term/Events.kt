package vesper.term

import vesper.grid.LinkId

// Polled host events, capped because they are PTY bytes. Separate because the queue is the one
// place the core hands the host something other than cells and replies.
//
// The bell is coalesced: several BELs are one event, and either door (`takeBell` or `pollEvent`)
// consumes it. The OSCs that make the other events are read in OscDispatch.kt and Links.kt.

/** The most events held until the host polls; newer ones are dropped. */
internal const val MAX_EVENTS = 32

/**
 * The most bytes the queued events carry together. A clipboard write may be large, so the count
 * alone would let 32 of them hold far more memory than a host that is slow to poll should pin.
 */
internal const val MAX_EVENT_BYTES = 2 shl 20

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

/** The OSC 133 marks (FinalTerm, as VS Code and iTerm2 read them). [letter] is the one the sequence uses. */
public enum class ShellMarkKind(
    public val letter: Char,
) {
    /** A: the prompt starts. */
    PromptStart('A'),

    /** B: the prompt ends and the command line starts. */
    CommandStart('B'),

    /** C: the command runs; its output follows. */
    CommandExecuted('C'),

    /** D: the command finished. */
    CommandFinished('D'),
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

    /** OSC 7: the shell's working directory, as the `file://` URI the program sent. */
    public data class Directory(
        public val uri: String,
    ) : Event

    /** OSC 133. [exitCode] is the one D carries, or null when it carries none. */
    public data class ShellMark(
        public val kind: ShellMarkKind,
        public val exitCode: Int?,
    ) : Event

    /**
     * OSC 52 write: put [data] on the selections named by [targets] (xterm's letters `c p q s 0`–`7`).
     * The host decides whether a program may write the clipboard at all.
     */
    public class ClipboardWrite(
        public val targets: String,
        public val data: ByteArray,
    ) : Event {
        override fun toString(): String = "ClipboardWrite($targets, ${data.size} bytes)"
    }

    /**
     * OSC 52 read: the program asks for the selections named by [targets]. The host answers with
     * [Terminal.answerClipboard] or [Terminal.denyClipboard]; the program waits for one or the other.
     */
    public data class ClipboardRead(
        public val targets: String,
    ) : Event

    /** OSC 9 (no [title]) or OSC 777 `notify`: a desktop notification the host may show. */
    public data class Notification(
        public val title: String,
        public val body: String,
    ) : Event

    /**
     * OSC 8 opened a link the terminal had no id for. Cells printed until it closes carry [id], and
     * [Terminal.linkUri] reads the URI back for as long as any cell holds the id. After a sweep frees
     * an id it may come back with another URI, announced by a new event.
     */
    public data class Link(
        public val id: LinkId,
        public val uri: String,
    ) : Event
}

/** What an event counts against [MAX_EVENT_BYTES]: its payload, in the units it is stored in. */
private val Event.cost: Int
    get() =
        when (this) {
            Event.Bell -> 0
            is Event.Title -> text.length
            is Event.Directory -> uri.length
            is Event.ShellMark -> 0
            is Event.ClipboardWrite -> targets.length + data.size
            is Event.ClipboardRead -> targets.length
            is Event.Notification -> title.length + body.length
            is Event.Link -> uri.length
        }

/** The queue plus the coalesced bell flag. */
internal class EventQueue {
    private val items = ArrayDeque<Event>()
    private var bell = false
    private var bytes = 0

    /** Queues [event], or drops it when the count or the byte budget is spent. */
    fun push(event: Event): Boolean {
        val cost = event.cost
        if (items.size >= MAX_EVENTS || bytes + cost > MAX_EVENT_BYTES) return false
        items.addLast(event)
        bytes += cost
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
            bytes -= event.cost
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
