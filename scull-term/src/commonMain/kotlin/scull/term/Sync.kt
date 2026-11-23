package scull.term

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

// Synchronized output (mode 2026): while a program draws between BSU (`CSI ? 2026 h`) and ESU
// (`CSI ? 2026 l`) the frame keeps the last picture, so the UI never shows half a redraw. The bytes
// reach the grid at once and only the frame update is held, as foot and Contour do; Alacritty buffers
// the bytes in its parser instead, which needs a buffer the grid's own caps make unnecessary. An
// untrusted program can set the mode and never reset it, so the hold has Alacritty's two caps and the
// mode then reads as reset.

/** Bytes one synchronized update may span before the picture is released: Alacritty's 2 MiB, several redraws of a large screen. */
public const val MAX_SYNC_BYTES: Int = 2 * 1024 * 1024

/**
 * How long one synchronized update may hold the picture: Alacritty's and Contour's 150 ms. foot waits
 * a second; the shorter hold keeps a program that forgets ESU from freezing the view.
 */
public val MAX_SYNC_HOLD: Duration = 150.milliseconds

internal typealias Instant = TimeSource.Monotonic.ValueTimeMark

/**
 * What the current hold has used of its caps. Bytes are counted per `feed` that ends with the mode
 * set, so a feed that starts and ends inside one update counts whole. The time starts at the first
 * look that finds the mode set (the I/O thread looks right after each feed). An ESU and a new BSU in
 * one feed are never seen apart; the caps then run on, which is what guarantees the picture moves.
 */
internal class SyncGate {
    private var since: Instant? = null
    private var bytes = 0L

    fun reset() {
        since = null
        bytes = 0
    }

    /** Counts [count] bytes just fed; [active] is the mode after them. True when the byte cap is passed. */
    fun fed(
        active: Boolean,
        count: Int,
    ): Boolean {
        if (!active) {
            reset()
            return false
        }
        bytes += count
        return bytes > MAX_SYNC_BYTES
    }

    /** Whether the picture is still held at [now]: false once the mode is reset or the hold lasted [MAX_SYNC_HOLD]. */
    fun holds(
        active: Boolean,
        now: Instant,
    ): Boolean {
        if (!active) {
            reset()
            return false
        }
        val start = since ?: now.also { since = it }
        return now - start < MAX_SYNC_HOLD
    }

    /** When the hold ends by itself, once a look has seen it. */
    val deadline: Instant? get() = since?.plus(MAX_SYNC_HOLD)
}
