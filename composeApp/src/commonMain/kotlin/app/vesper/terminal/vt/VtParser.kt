package app.vesper.terminal.vt

/** Receives the actions decoded by [VtParser] (a DEC/xterm-style state machine). */
interface VtHandler {
    fun print(cp: Int)

    /** C0 control (BEL, BS, HT, LF, CR, …). */
    fun execute(code: Int)

    /**
     * [params] holds [paramCount] values (-1 = omitted); [isSub] marks values introduced by ':'
     * (e.g. SGR `38:2::r:g:b`). [private] is the leading `<=>?` marker or 0.
     */
    fun csi(params: IntArray, isSub: BooleanArray, paramCount: Int, private: Char, intermediates: String, final: Char)

    fun esc(intermediates: String, final: Char)

    /** OSC payload without the introducer and terminator, e.g. `133;D;0`. */
    fun osc(data: String)
}

class VtParser(private val handler: VtHandler) {
    private enum class State { Ground, Esc, EscIntermediate, Csi, CsiIgnore, Osc, OscEsc, Str, StrEsc }

    private var state = State.Ground
    private val params = IntArray(MAX_PARAMS)
    private val isSub = BooleanArray(MAX_PARAMS)
    private var paramCount = 0
    private var current = -1
    private var nextIsSub = false
    private var private = 0.toChar()
    private val intermediates = StringBuilder()
    private val oscBuffer = StringBuilder()
    private var pendingHigh = 0.toChar()

    fun feed(text: CharSequence) {
        for (i in 0 until text.length) {
            val ch = text[i]
            if (pendingHigh != 0.toChar()) {
                val high = pendingHigh
                pendingHigh = 0.toChar()
                if (ch.isLowSurrogate()) {
                    step(((high.code - 0xD800) shl 10) + (ch.code - 0xDC00) + 0x10000)
                    continue
                }
                step(0xFFFD)
            }
            if (ch.isHighSurrogate()) {
                pendingHigh = ch
            } else {
                step(ch.code)
            }
        }
    }

    private fun step(cp: Int) {
        // CAN / SUB abort any sequence.
        if (cp == 0x18 || cp == 0x1A) {
            state = State.Ground
            return
        }
        when (state) {
            State.Ground -> ground(cp)
            State.Esc -> escape(cp)
            State.EscIntermediate -> escIntermediate(cp)
            State.Csi -> csi(cp)
            State.CsiIgnore -> if (cp in 0x40..0x7E) state = State.Ground else if (cp < 0x20 && cp != 0x1B) handler.execute(cp)
            State.Osc -> osc(cp)
            State.OscEsc -> {
                dispatchOsc()
                state = State.Ground
                if (cp != '\\'.code) escape(cp)
            }
            State.Str -> if (cp == 0x1B) state = State.StrEsc else if (cp == 0x07) state = State.Ground
            State.StrEsc -> state = if (cp == '\\'.code) State.Ground else State.Str
        }
    }

    private fun ground(cp: Int) {
        when {
            cp == 0x1B -> enterEscape()
            cp < 0x20 -> handler.execute(cp)
            cp == 0x7F -> Unit
            cp in 0x80..0x9F -> Unit // 8-bit C1 controls are not used in UTF-8 streams
            else -> handler.print(cp)
        }
    }

    private fun enterEscape() {
        state = State.Esc
        intermediates.setLength(0)
    }

    private fun escape(cp: Int) {
        when (cp) {
            '['.code -> {
                state = State.Csi
                paramCount = 0
                current = -1
                nextIsSub = false
                private = 0.toChar()
                intermediates.setLength(0)
            }
            ']'.code -> {
                state = State.Osc
                oscBuffer.setLength(0)
            }
            'P'.code, 'X'.code, '^'.code, '_'.code -> state = State.Str
            0x1B -> enterEscape()
            in 0x20..0x2F -> {
                intermediates.append(cp.toChar())
                state = State.EscIntermediate
            }
            in 0x30..0x7E -> {
                state = State.Ground
                handler.esc("", cp.toChar())
            }
            else -> if (cp < 0x20) handler.execute(cp) else state = State.Ground
        }
    }

    private fun escIntermediate(cp: Int) {
        when (cp) {
            in 0x20..0x2F -> intermediates.append(cp.toChar())
            in 0x30..0x7E -> {
                state = State.Ground
                handler.esc(intermediates.toString(), cp.toChar())
            }
            0x1B -> enterEscape()
            else -> if (cp < 0x20) handler.execute(cp) else state = State.Ground
        }
    }

    private fun pushParam() {
        if (paramCount < MAX_PARAMS) {
            params[paramCount] = current
            isSub[paramCount] = nextIsSub
            paramCount++
        }
        current = -1
    }

    private fun csi(cp: Int) {
        when (cp) {
            in '0'.code..'9'.code -> {
                val digit = cp - '0'.code
                current = if (current < 0) digit else minOf(current * 10 + digit, 1_000_000)
            }
            ';'.code -> {
                pushParam()
                nextIsSub = false
            }
            ':'.code -> {
                pushParam()
                nextIsSub = true
            }
            in '<'.code..'?'.code -> {
                if (paramCount == 0 && current < 0 && private == 0.toChar()) private = cp.toChar() else state = State.CsiIgnore
            }
            in 0x20..0x2F -> intermediates.append(cp.toChar())
            in 0x40..0x7E -> {
                if (current >= 0 || paramCount > 0) pushParam()
                state = State.Ground
                handler.csi(params, isSub, paramCount, private, intermediates.toString(), cp.toChar())
            }
            0x1B -> enterEscape()
            else -> if (cp < 0x20) handler.execute(cp) else state = State.CsiIgnore
        }
    }

    private fun osc(cp: Int) {
        when (cp) {
            0x07 -> {
                dispatchOsc()
                state = State.Ground
            }
            0x1B -> state = State.OscEsc
            else -> if (oscBuffer.length < MAX_OSC) appendCodePoint(oscBuffer, cp)
        }
    }

    private fun dispatchOsc() {
        handler.osc(oscBuffer.toString())
        oscBuffer.setLength(0)
    }

    private fun appendCodePoint(sb: StringBuilder, cp: Int) {
        if (cp < 0x10000) {
            sb.append(cp.toChar())
        } else {
            val v = cp - 0x10000
            sb.append((0xD800 + (v shr 10)).toChar())
            sb.append((0xDC00 + (v and 0x3FF)).toChar())
        }
    }

    private companion object {
        const val MAX_PARAMS = 32
        const val MAX_OSC = 1 shl 16
    }
}
