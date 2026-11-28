package vesper.config

import okio.FileSystem
import okio.IOException
import okio.Path.Companion.toPath
import okio.SYSTEM
import vesper.sync.Monitor
import vesper.sync.ThreadStartException
import vesper.sync.startThread
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

// The config kept current while the program runs. A thread polls the file instead of subscribing to
// file-system events: a poll cannot lose the final state to an overflowing event queue (T30), and it
// needs no inotify, FSEvents or ReadDirectoryChangesW binding.

/** How often the file's stamp is compared. */
internal val POLL: Duration = 100.milliseconds

/** How often the file is read whatever its stamp says: a coarse clock can give two saves one stamp. */
internal val RESYNC: Duration = 2.seconds

/** What a host polls after a wakeup. */
public data class Snapshot(
    /** Starts at 1 and grows whenever [settings] or [error] change. */
    val generation: Long,
    /** The settings of the last time the file was valid, or the defaults. */
    val settings: Settings,
    /** Why the file on disk is not what [settings] came from, or null when it is. */
    val error: String?,
    /** Whether changes to the file are picked up without [LiveConfig.reload]. */
    val watching: Boolean,
)

private data class Stamp(
    val modifiedMillis: Long?,
    val size: Long?,
)

/** What tells two versions of the file apart without reading it; null while there is no file. */
internal fun fileStamp(path: String): Any? {
    val fs = FileSystem.SYSTEM
    return try {
        // Through a symlink, so an edit of the file it points to counts.
        val meta = fs.metadataOrNull(fs.canonicalize(path.toPath())) ?: return null
        Stamp(meta.lastModifiedAtMillis, meta.size)
    } catch (_: IOException) {
        null
    }
}

private object NotPending

/**
 * The config file at [path], loaded and then watched. A file that stops being valid keeps the last
 * good settings and reports why, so a typo mid-edit never resets the terminal.
 *
 * [onChange] runs on the watcher's thread, or on the caller's for [reload] and [set], with no lock
 * held, whenever [snapshot] would now differ; it must only post to the host's thread. A throw from it
 * stops the watching. [close] stops the thread and waits for it, so no [onChange] runs afterwards; it
 * must not be called from [onChange].
 */
public class LiveConfig internal constructor(
    public val path: String,
    private val onChange: () -> Unit,
    private val poll: Duration,
    private val resync: Duration,
    private val stamp: (String) -> Any?,
) : AutoCloseable {
    /** Never fails: a bad file is an error in the snapshot, and a thread that cannot start means no live reload. */
    public constructor(path: String, onChange: () -> Unit) : this(path, onChange, POLL, RESYNC, ::fileStamp)

    // Guards the fields below. Condition 0 wakes the poller to stop, and tells close that it has.
    private val state = Monitor(1)
    private var generation = 0L
    private var settings = Settings.DEFAULT
    private var error: String? = null

    // The text the last successful read found (null: no file), so a resync of the same bytes is free.
    private var lastText: String? = null
    private var lastTextKnown = false
    private var stopped = false
    private var polling = false

    // Held from a read to its publication, so an older read never replaces a newer one.
    private val reading = Monitor(1)

    init {
        // Before the load: a write between the two then differs from it and is read by the first poll.
        val seen = stamp(path)
        reading.locked { readAndPublish() }
        generation = 1
        // Set before the start: a poller that ended at once must not be marked running after it.
        state.locked { polling = true }
        try {
            startThread("vesper-config") { pollLoop(seen) }
        } catch (_: ThreadStartException) {
            state.locked { polling = false }
        }
    }

    public fun snapshot(): Snapshot = state.locked { Snapshot(generation, settings, error, polling) }

    /** Reads the file again now. */
    public fun reload() {
        if (reading.locked { readAndPublish() }) onChange()
    }

    /**
     * Changes one setting in the file through [editConfig] and applies it at once; a null [value]
     * removes the key so its default applies.
     *
     * @throws ConfigException when the edit is refused; the file and the settings stay as they were.
     */
    public fun set(
        key: String,
        value: String?,
    ) {
        val changed =
            reading.locked {
                editConfig(path, key, value)
                readAndPublish()
            }
        if (changed) onChange()
    }

    override fun close() {
        state.locked {
            stopped = true
            state.broadcast(0)
            while (polling) state.await(0)
        }
    }

    /** With [reading] held; whether the snapshot changed. */
    private fun readAndPublish(): Boolean {
        val text =
            try {
                readCapped(path)
            } catch (e: ConfigException) {
                return publish(null, e.message, null, textKnown = false)
            }
        if (state.locked { lastTextKnown && text == lastText }) return false
        return try {
            publish(text?.let { Settings.parse(it, path) } ?: Settings.DEFAULT, null, text, textKnown = true)
        } catch (e: ConfigException) {
            publish(null, e.message, text, textKnown = true)
        }
    }

    private fun publish(
        loaded: Settings?,
        failure: String?,
        text: String?,
        textKnown: Boolean,
    ): Boolean =
        state.locked {
            lastText = text
            lastTextKnown = textKnown
            val changed =
                if (loaded != null) {
                    (loaded != settings || error != null).also {
                        settings = loaded
                        error = null
                    }
                } else {
                    (failure != error).also { error = failure }
                }
            if (changed) generation++
            changed
        }

    private fun pollLoop(initial: Any?) {
        var seen = initial
        var pending: Any? = NotPending
        var lastResync = TimeSource.Monotonic.markNow()
        try {
            while (awaitPoll()) {
                val now = stamp(path)
                when (now) {
                    seen -> {
                        pending = NotPending
                    }

                    // Two polls agree: an editor's save, which may take several writes, is over.
                    pending -> {
                        seen = now
                        pending = NotPending
                        reload()
                    }

                    else -> {
                        pending = now
                    }
                }
                if (lastResync.elapsedNow() >= resync) {
                    lastResync = TimeSource.Monotonic.markNow()
                    reload()
                }
            }
        } finally {
            state.locked {
                polling = false
                state.broadcast(0)
            }
        }
    }

    /** Sleeps one poll; false once [close] was called. */
    private fun awaitPoll(): Boolean =
        state.locked {
            if (!stopped) state.await(0, poll)
            !stopped
        }
}
