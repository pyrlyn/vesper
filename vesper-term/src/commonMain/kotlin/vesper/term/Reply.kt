package vesper.term

// Replies to the program: device attributes, status and cursor reports, mode reports. Separate
// because it is the only output a PTY can make the core produce, so the queue that holds it is
// where the cap lives.
//
// Formats follow xterm's ctlseqs (DA1, DA2, DSR, DECXCPR, DECRQM). The queue holds whole replies
// only: when one would pass the cap it is dropped, never cut, so the program never reads half a
// sequence.

/**
 * The most reply bytes held until the caller drains them. Far above what any program waits for
 * between reads, small enough that a flood of requests from an untrusted PTY cannot grow memory.
 */
internal const val MAX_REPLY_BYTES = 4096

/** DA1: a VT220 (62) with ANSI colour (22), the set xterm's VT220 mode reports minus the features this core lacks. */
private const val PRIMARY_ATTRIBUTES = "\u001B[?62;22c"

/** DA2: terminal type 1 (VT220), firmware version 0 until Vesper has a release number, ROM cartridge 0. */
private const val SECONDARY_ATTRIBUTES = "\u001B[>1;0;0c"

/** DSR 5: "OK, no malfunction". */
internal const val STATUS_OK = "\u001B[0n"

/** DECXCPR reports the page; there is only one. */
private const val PAGE = 1

/** DSR parameters. */
private const val DSR_STATUS = 5
private const val DSR_CURSOR = 6

/** Reply bytes waiting for the caller. */
internal class Replies {
    private var bytes = ByteArray(MAX_REPLY_BYTES)
    private var size = 0

    /** Queues one whole reply, or drops it if the queue is full. */
    fun push(reply: ByteArray) {
        add(reply, MAX_REPLY_BYTES)
    }

    fun push(reply: String) = push(reply.encodeToByteArray())

    /**
     * Queues an answer the host supplied (OSC 52), which may pass [MAX_REPLY_BYTES]: that cap is
     * there for what the PTY can make the core say, and the host chose this size itself.
     */
    fun pushAnswer(reply: ByteArray): Boolean = add(reply, MAX_REPLY_BYTES + MAX_ANSWER_BYTES)

    private fun add(
        reply: ByteArray,
        cap: Int,
    ): Boolean {
        if (size + reply.size > cap) return false
        if (size + reply.size > bytes.size) bytes = bytes.copyOf(size + reply.size)
        reply.copyInto(bytes, size)
        size += reply.size
        return true
    }

    fun take(): ByteArray {
        val out = bytes.copyOf(size)
        size = 0
        // A buffer one clipboard answer grew is not kept for the next device report.
        if (bytes.size > MAX_REPLY_BYTES) bytes = ByteArray(MAX_REPLY_BYTES)
        return out
    }
}

/** Room for `ESC ] 52 ;`, the targets, `;` and the terminator around an OSC 52 answer. */
private const val ANSWER_FRAMING = 32

/** The longest OSC 52 answer: the base64 of the largest clipboard in its framing. */
private val MAX_ANSWER_BYTES = base64Length(MAX_CLIPBOARD_BYTES) + ANSWER_FRAMING

internal fun State.primaryAttributes() = replies.push(PRIMARY_ATTRIBUTES)

internal fun State.secondaryAttributes() = replies.push(SECONDARY_ATTRIBUTES)

/** DSR (`CSI Ps n`) and, with [private], DECDSR (`CSI ? Ps n`). */
internal fun State.deviceStatus(
    ps: Int,
    private: Boolean,
) {
    when (ps) {
        DSR_STATUS -> {
            if (!private) replies.push(STATUS_OK)
        }

        DSR_CURSOR -> {
            // Relative to the margins in origin mode, as xterm reports it.
            val reportedRow = (if (modes.origin) (row - top).coerceAtLeast(0) else row) + 1
            val reportedCol = (if (modes.origin) (col - left).coerceAtLeast(0) else col) + 1
            replies.push(if (private) "\u001B[?$reportedRow;$reportedCol;${PAGE}R" else "\u001B[$reportedRow;${reportedCol}R")
        }
    }
}

/** DECRQM for an ANSI mode: `CSI Pd ; Ps $ y`. */
internal fun State.reportAnsiMode(code: Int) = replies.push("\u001B[$code;${ansiModeStatus(code)}\$y")

/** DECRQM for a DEC private mode: `CSI ? Pd ; Ps $ y`. */
internal fun State.reportDecMode(code: Int) = replies.push("\u001B[?$code;${decModeStatus(code)}\$y")
