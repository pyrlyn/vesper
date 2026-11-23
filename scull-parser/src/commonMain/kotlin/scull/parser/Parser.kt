package scull.parser

// The sequence state machine, after Paul Williams' DEC ANSI parser
// (https://vt100.net/emu/dec_ansi_parser), with the ground state and the string payload states
// replaced by bulk scans. Ported from the Rust scull-parser (69d9be4), which passed its fuzz target.
//
// Deviations from the Williams machine, all deliberate:
// - Input is UTF-8. C1 controls are recognised in their UTF-8 form (U+0080..U+009F) and in the
//   seven-bit `ESC Fe` form; a raw byte 0x80..0x9F is just invalid UTF-8.
// - APC has its own streamed state, for kitty graphics.
// - A string (OSC, DCS, APC) ends only on ST; `ESC` followed by anything else cancels it, so a lost
//   terminator cannot leak a half payload.
// - OSC also ends on BEL, as xterm accepts.
// - A byte 0x80 or above inside an escape or a control sequence aborts the sequence and is read
//   again as text, so no text is swallowed.

/** Most intermediate bytes kept; no standard sequence uses more than two. More are ignored, not misread. */
public const val MAX_INTERMEDIATES: Int = 2

/**
 * Most OSC payload bytes kept. An iTerm2 inline image (`OSC 1337 File=`) arrives as one base64 OSC,
 * so this matches the DCS cap and admits files of about 12 MiB. A longer OSC is dropped whole: a
 * cut image, clipboard write or link is worse than none.
 */
public const val MAX_OSC_BYTES: Int = 16 shl 20

/** OSC buffer kept from one sequence to the next, so one large image does not pin [MAX_OSC_BYTES]. */
internal const val OSC_KEPT_BYTES: Int = 64 shl 10

/** Most DCS payload bytes streamed: room for a full-screen sixel image. Past it the payload is cut. */
public const val MAX_DCS_BYTES: Int = 16 shl 20

/** Most APC payload bytes streamed. kitty graphics clients send 4 KiB chunks; the margin admits those that do not. */
public const val MAX_APC_BYTES: Int = 4 shl 20

private const val BEL = 0x07
private const val CAN = 0x18
private const val SUB = 0x1A
private const val ESC = 0x1B
private const val DEL = 0x7F

// Byte classes of the Williams table.
private const val C0_LAST = 0x1F
private const val INTERMEDIATE_FIRST = 0x20
private const val INTERMEDIATE_LAST = 0x2F
private const val ESC_FINAL_FIRST = 0x30
private const val DIGIT_LAST = 0x39
private const val COLON = 0x3A
private const val SEMICOLON = 0x3B
private const val PRIVATE_FIRST = 0x3C

// Parameter bytes are 0x30..0x3F: digits, `:`, `;` and the private markers.
private const val PARAM_LAST = 0x3F
private const val CSI_FINAL_FIRST = 0x40
private const val FINAL_LAST = 0x7E
private const val CSI_ENTRY = 0x5B // [
private const val DCS_ENTRY = 0x50 // P
private const val OSC_ENTRY = 0x5D // ]
private const val APC_ENTRY = 0x5F // _
private const val SOS_ENTRY = 0x58 // X
private const val PM_ENTRY = 0x5E // ^

// The ST that ends a string: `ESC \`.
private const val ST_FINAL = 0x5C

// A C1 control `c` is the escape sequence `ESC (c - 0x40)` (ECMA-48 §5.3).
private const val C1_TO_FE = 0x40

// States.
private const val GROUND = 0
private const val ESCAPE = 1
private const val ESCAPE_INTERMEDIATE = 2
private const val HEADER = 3
private const val STRING = 4

// Inside a string, right after `ESC`: `\` ends it, anything else cancels it.
private const val STRING_ESCAPE = 5

// Header kinds and phases.
private const val KIND_CSI = 0
private const val KIND_DCS = 1
private const val PHASE_ENTRY = 0
private const val PHASE_PARAM = 1
private const val PHASE_INTERMEDIATE = 2
private const val PHASE_IGNORE = 3

// String kinds; STR_IGNORE is SOS, PM, and a DCS whose header was malformed.
private const val STR_OSC = 0
private const val STR_DCS = 1
private const val STR_APC = 2
private const val STR_IGNORE = 3

/**
 * A VT parser. Feed it PTY output in chunks of any size; it keeps the state of a sequence or a
 * UTF-8 character that a chunk boundary cut. Everything it reads is untrusted, so every buffer it
 * can grow has a hard cap. Not thread-safe: one parser per terminal.
 */
public class Parser {
    private var state = GROUND
    private var kind = KIND_CSI
    private var phase = PHASE_ENTRY
    private var str = STR_OSC
    private val params = Params()
    private val intermediates = Intermediates()
    private var intermediateOverflow = false
    private val esc = Esc(intermediates)
    private val csi = Csi(params, intermediates)
    private val osc = Osc()
    private var oscOverflow = false
    private var stringLen = 0
    private var stringTruncated = false
    private val pending = ByteArray(MAX_UTF8_LEN)
    private var pendingLen = 0

    internal val oscCapacity: Int get() = osc.capacity

    internal val oscSize: Int get() = osc.size

    /** Parses all of [bytes]. */
    public fun feed(
        bytes: ByteArray,
        handler: Handler,
    ): Unit = feed(bytes, 0, bytes.size, handler)

    /** Parses `bytes[start, end)`, delivering every complete action to [handler]. */
    public fun feed(
        bytes: ByteArray,
        start: Int,
        end: Int,
        handler: Handler,
    ) {
        require(start in 0..end && end <= bytes.size) { "range $start..$end of ${bytes.size}" }
        var pos = start
        if (pendingLen > 0 && pos < end) pos = completeUtf8(bytes, pos, end, handler)
        while (pos < end) {
            pos =
                when (state) {
                    GROUND -> ground(bytes, pos, end, handler)
                    STRING -> if (str == STR_OSC) oscBytes(bytes, pos, end, handler) else stringBytes(bytes, pos, end, handler)
                    else -> sequenceBytes(bytes, pos, end, handler)
                }
        }
    }

    // Text up to the next control byte goes out as one print; the UTF-8 check runs in the same
    // pass, and ASCII, which is most output, takes one comparison per byte.
    private fun ground(
        bytes: ByteArray,
        from: Int,
        end: Int,
        handler: Handler,
    ): Int {
        var i = from
        var run = from
        while (i < end) {
            val byte = bytes[i].toInt() and BYTE_MASK
            if (byte in INTERMEDIATE_FIRST..FINAL_LAST) {
                i++
                continue
            }
            if (byte <= DEL) {
                if (run < i) handler.print(bytes, run, i)
                i++
                if (byte != DEL) {
                    byte(byte, handler)
                    if (state != GROUND) return i
                }
                run = i
                continue
            }
            // Two- and three-byte forms whose second byte has the full continuation range are
            // most non-ASCII text (Cyrillic, Greek, CJK, symbols); they skip the general check.
            if (i + 2 < end && isContinuation(bytes[i + 1])) {
                if (byte in C1_LEAD + 1..TWO_BYTE_LAST) {
                    i += 2
                    continue
                }
                if ((byte in THREE_BYTE_FIRST..THREE_BYTE_LAST && byte != SURROGATE_LEAD) && isContinuation(bytes[i + 2])) {
                    i += THREE_BYTES
                    continue
                }
            }
            val check = utf8Check(bytes, i, end)
            when {
                check >= INCOMPLETE -> {
                    if (run < i) handler.print(bytes, run, i)
                    bytes.copyInto(pending, 0, i, end)
                    pendingLen = end - i
                    return end
                }

                check < 0 -> {
                    if (run < i) handler.print(bytes, run, i)
                    handler.print(REPLACEMENT, 0, REPLACEMENT.size)
                    i -= check
                    run = i
                }

                byte == C1_LEAD && (bytes[i + 1].toInt() and BYTE_MASK) <= C1_LAST -> {
                    if (run < i) handler.print(bytes, run, i)
                    c1(bytes[i + 1].toInt() and BYTE_MASK, handler)
                    i += 2
                    if (state != GROUND) return i
                    run = i
                }

                else -> {
                    i += check
                }
            }
        }
        if (run < end) handler.print(bytes, run, end)
        return end
    }

    /** Completes a UTF-8 sequence the previous `feed` cut, the same way the whole stream would have decoded it. */
    private fun completeUtf8(
        bytes: ByteArray,
        from: Int,
        end: Int,
        handler: Handler,
    ): Int {
        val have = pendingLen
        val need = utf8Width(pending[0].toInt() and BYTE_MASK)
        val take = minOf(need - have, end - from)
        bytes.copyInto(pending, have, from, from + take)
        val length = have + take
        pendingLen = 0
        val check = utf8Check(pending, 0, length)
        return when {
            check >= INCOMPLETE -> {
                pendingLen = length
                end
            }

            check < 0 -> {
                handler.print(REPLACEMENT, 0, REPLACEMENT.size)
                // The pending bytes were a valid prefix, so the invalid subpart reaches into the input.
                from + (-check - have)
            }

            else -> {
                val tail = pending[1].toInt() and BYTE_MASK
                if (pending[0].toInt() and BYTE_MASK == C1_LEAD && tail <= C1_LAST) {
                    c1(tail, handler)
                } else {
                    handler.print(pending, 0, check)
                }
                from + take
            }
        }
    }

    // Steps through an escape or control sequence until it leaves for a bulk state. `ESC [`,
    // parameters and the final byte, which make up nearly all of an SGR-heavy stream, take a short
    // path past the full transition table in `byte`.
    private fun sequenceBytes(
        bytes: ByteArray,
        from: Int,
        end: Int,
        handler: Handler,
    ): Int {
        var i = from
        while (i < end) {
            if (state == GROUND || state == STRING) return i
            val byte = bytes[i].toInt() and BYTE_MASK
            if (state == ESCAPE && byte == CSI_ENTRY) {
                enterHeader(KIND_CSI)
                i++
                continue
            }
            if (state == HEADER && (phase == PHASE_ENTRY || phase == PHASE_PARAM)) {
                when (byte) {
                    in ESC_FINAL_FIRST..DIGIT_LAST -> {
                        params.digit(byte)
                        phase = PHASE_PARAM
                        i++
                        continue
                    }

                    COLON, SEMICOLON -> {
                        params.separator(byte == COLON)
                        phase = PHASE_PARAM
                        i++
                        continue
                    }

                    in CSI_FINAL_FIRST..FINAL_LAST -> {
                        dispatch(byte, handler)
                        i++
                        continue
                    }
                }
            }
            if (!byte(byte, handler)) return i
            i++
        }
        return end
    }

    private fun c1(
        tail: Int,
        handler: Handler,
    ) {
        enterEscape()
        escape(tail - C1_TO_FE, handler)
    }

    private fun enterEscape() {
        state = ESCAPE
        params.clear()
        intermediates.clear()
        intermediateOverflow = false
        csi.private = 0
    }

    private fun enterHeader(kind: Int) {
        state = HEADER
        this.kind = kind
        phase = PHASE_ENTRY
    }

    /** One byte outside the bulk states. False when it aborted the sequence and must be read again as text. */
    private fun byte(
        byte: Int,
        handler: Handler,
    ): Boolean {
        if (state == STRING_ESCAPE) {
            if (byte == ST_FINAL) {
                endString(cancelled = false, handler)
                state = GROUND
                return true
            }
            endString(cancelled = true, handler)
            enterEscape()
        }
        when (byte) {
            CAN, SUB -> {
                state = GROUND
                handler.execute(byte)
                return true
            }

            ESC -> {
                enterEscape()
                return true
            }
        }
        return when (state) {
            ESCAPE -> {
                escape(byte, handler)
            }

            ESCAPE_INTERMEDIATE -> {
                escapeIntermediate(byte, handler)
            }

            HEADER -> {
                header(byte, handler)
            }

            else -> {
                if (byte <= C0_LAST) handler.execute(byte)
                true
            }
        }
    }

    private fun escape(
        byte: Int,
        handler: Handler,
    ): Boolean {
        when (byte) {
            in 0..C0_LAST -> {
                handler.execute(byte)
            }

            in INTERMEDIATE_FIRST..INTERMEDIATE_LAST -> {
                collect(byte)
                state = ESCAPE_INTERMEDIATE
            }

            CSI_ENTRY -> {
                enterHeader(KIND_CSI)
            }

            DCS_ENTRY -> {
                enterHeader(KIND_DCS)
            }

            OSC_ENTRY -> {
                osc.reset()
                oscOverflow = false
                enterString(STR_OSC)
            }

            APC_ENTRY -> {
                startString()
                handler.apcStart()
                enterString(STR_APC)
            }

            SOS_ENTRY, PM_ENTRY -> {
                enterString(STR_IGNORE)
            }

            in ESC_FINAL_FIRST..FINAL_LAST -> {
                esc.finalByte = byte
                state = GROUND
                handler.escDispatch(esc)
            }

            DEL -> {}

            else -> {
                return abort()
            }
        }
        return true
    }

    private fun escapeIntermediate(
        byte: Int,
        handler: Handler,
    ): Boolean {
        when (byte) {
            in 0..C0_LAST -> {
                handler.execute(byte)
            }

            in INTERMEDIATE_FIRST..INTERMEDIATE_LAST -> {
                collect(byte)
            }

            in ESC_FINAL_FIRST..FINAL_LAST -> {
                state = GROUND
                if (!intermediateOverflow) {
                    esc.finalByte = byte
                    handler.escDispatch(esc)
                }
            }

            DEL -> {}

            else -> {
                return abort()
            }
        }
        return true
    }

    private fun header(
        byte: Int,
        handler: Handler,
    ): Boolean {
        val next =
            when {
                byte <= C0_LAST -> {
                    // Williams: C0 executes inside CSI but is ignored in a DCS header.
                    if (kind == KIND_CSI) handler.execute(byte)
                    phase
                }

                byte == DEL || (byte in INTERMEDIATE_FIRST..PARAM_LAST && phase == PHASE_IGNORE) -> {
                    phase
                }

                byte in INTERMEDIATE_FIRST..INTERMEDIATE_LAST -> {
                    collect(byte)
                    if (intermediateOverflow) PHASE_IGNORE else PHASE_INTERMEDIATE
                }

                (byte in ESC_FINAL_FIRST..SEMICOLON && phase == PHASE_INTERMEDIATE) ||
                    (byte in PRIVATE_FIRST..PARAM_LAST && (phase == PHASE_PARAM || phase == PHASE_INTERMEDIATE)) -> {
                    PHASE_IGNORE
                }

                byte in ESC_FINAL_FIRST..DIGIT_LAST -> {
                    params.digit(byte)
                    PHASE_PARAM
                }

                byte == COLON || byte == SEMICOLON -> {
                    params.separator(byte == COLON)
                    PHASE_PARAM
                }

                byte in PRIVATE_FIRST..PARAM_LAST -> {
                    csi.private = byte
                    PHASE_PARAM
                }

                byte in CSI_FINAL_FIRST..FINAL_LAST -> {
                    if (phase == PHASE_IGNORE) state = GROUND else dispatch(byte, handler)
                    return true
                }

                else -> {
                    return abort()
                }
            }
        if (kind == KIND_DCS && next == PHASE_IGNORE) {
            enterString(STR_IGNORE)
        } else {
            phase = next
        }
        return true
    }

    private fun dispatch(
        finalByte: Int,
        handler: Handler,
    ) {
        params.finish()
        csi.finalByte = finalByte
        if (kind == KIND_CSI) {
            state = GROUND
            handler.csiDispatch(csi)
        } else {
            startString()
            enterString(STR_DCS)
            handler.dcsHook(csi)
        }
    }

    private fun abort(): Boolean {
        state = GROUND
        return false
    }

    private fun collect(byte: Int) {
        if (!intermediates.add(byte)) intermediateOverflow = true
    }

    private fun enterString(kind: Int) {
        state = STRING
        str = kind
    }

    private fun startString() {
        stringLen = 0
        stringTruncated = false
    }

    private fun oscBytes(
        bytes: ByteArray,
        from: Int,
        end: Int,
        handler: Handler,
    ): Int {
        var stop = from
        while (stop < end && (bytes[stop].toInt() and BYTE_MASK) > C0_LAST) stop++
        val room = MAX_OSC_BYTES - osc.size
        if (stop - from > room) oscOverflow = true
        osc.append(bytes, from, from + minOf(stop - from, room))
        if (stop == end) return end
        when (val byte = bytes[stop].toInt()) {
            BEL -> {
                state = GROUND
                dispatchOsc(bellTerminated = true, handler)
            }

            ESC -> {
                state = STRING_ESCAPE
            }

            CAN, SUB -> {
                state = GROUND
                handler.execute(byte)
            }
            // Other C0 controls are dropped from the payload.
        }
        return stop + 1
    }

    private fun dispatchOsc(
        bellTerminated: Boolean,
        handler: Handler,
    ) {
        if (oscOverflow) return
        osc.bellTerminated = bellTerminated
        handler.oscDispatch(osc)
    }

    private fun stringBytes(
        bytes: ByteArray,
        from: Int,
        end: Int,
        handler: Handler,
    ): Int {
        var stop = from
        while (stop < end) {
            val byte = bytes[stop].toInt()
            if (byte == ESC || byte == CAN || byte == SUB) break
            stop++
        }
        val cap =
            when (str) {
                STR_DCS -> MAX_DCS_BYTES
                STR_APC -> MAX_APC_BYTES
                else -> 0
            }
        val room = maxOf(0, cap - stringLen)
        val taken = minOf(stop - from, room)
        if (stop - from > room && str != STR_IGNORE) stringTruncated = true
        stringLen += taken
        if (taken > 0) {
            when (str) {
                STR_DCS -> handler.dcsPut(bytes, from, from + taken)
                STR_APC -> handler.apcPut(bytes, from, from + taken)
            }
        }
        if (stop == end) return end
        val byte = bytes[stop].toInt()
        if (byte == ESC) {
            state = STRING_ESCAPE
        } else {
            endString(cancelled = true, handler)
            state = GROUND
            handler.execute(byte)
        }
        return stop + 1
    }

    private fun endString(
        cancelled: Boolean,
        handler: Handler,
    ) {
        val end =
            when {
                cancelled -> End.Cancelled
                stringTruncated -> End.Truncated
                else -> End.Complete
            }
        when (str) {
            STR_OSC -> if (!cancelled) dispatchOsc(bellTerminated = false, handler)
            STR_DCS -> handler.dcsUnhook(end)
            STR_APC -> handler.apcEnd(end)
        }
    }
}
