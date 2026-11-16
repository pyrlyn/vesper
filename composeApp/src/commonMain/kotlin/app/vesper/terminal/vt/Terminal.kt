package app.vesper.terminal.vt

/**
 * Cell attributes packed into a Long:
 *  bits 0..24  foreground color, bits 25..49 background color, bits 50..57 flags.
 * Color encoding: 0 = default, 1..256 = palette index + 1, bit 24 set = 24-bit RGB.
 */
object Attr {
    const val COLOR_BITS = 25
    const val COLOR_MASK = (1L shl COLOR_BITS) - 1
    const val RGB_FLAG = 1 shl 24
    const val FLAG_SHIFT = 50

    const val BOLD = 1
    const val DIM = 2
    const val ITALIC = 4
    const val UNDERLINE = 8
    const val INVERSE = 16
    const val HIDDEN = 32
    const val STRIKE = 64

    fun fg(a: Long): Int = (a and COLOR_MASK).toInt()
    fun bg(a: Long): Int = ((a shr COLOR_BITS) and COLOR_MASK).toInt()
    fun flags(a: Long): Int = (a ushr FLAG_SHIFT).toInt() and 0xFF
    fun withFg(a: Long, c: Int): Long = (a and COLOR_MASK.inv()) or (c.toLong() and COLOR_MASK)
    fun withBg(a: Long, c: Int): Long = (a and (COLOR_MASK shl COLOR_BITS).inv()) or ((c.toLong() and COLOR_MASK) shl COLOR_BITS)
    fun withFlag(a: Long, f: Int, on: Boolean): Long =
        if (on) a or (f.toLong() shl FLAG_SHIFT) else a and (f.toLong() shl FLAG_SHIFT).inv()

    fun palette(index: Int): Int = index + 1
    fun rgb(r: Int, g: Int, b: Int): Int = RGB_FLAG or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)
}

const val WIDE_TAIL = -1

class TermLine(cols: Int) {
    var cps = IntArray(cols)
        private set
    var attrs = LongArray(cols)
        private set
    var wrapped = false

    /** Bumped on every mutation; renderers cache on it. */
    var version = 0
        private set
    var renderCache: Any? = null
    var renderCacheKey: Long = Long.MIN_VALUE

    val size: Int get() = cps.size

    fun touch() {
        version++
    }

    fun resize(cols: Int) {
        if (cols == cps.size) return
        cps = cps.copyOf(cols)
        attrs = attrs.copyOf(cols)
        if (cols > 0 && cps[cols - 1] != WIDE_TAIL && cols >= 2 && charWidth(cps[cols - 1]) == 2) cps[cols - 1] = 0
        touch()
    }

    fun clear(from: Int, to: Int, attr: Long) {
        val end = minOf(to, cps.size)
        for (i in maxOf(0, from) until end) {
            cps[i] = 0
            attrs[i] = attr
        }
        touch()
    }

    /** Index after the last non-blank cell (ignoring trailing default-background blanks). */
    fun contentLength(): Int {
        var n = cps.size
        while (n > 0 && cps[n - 1] == 0 && Attr.bg(attrs[n - 1]) == 0 && Attr.flags(attrs[n - 1]) and Attr.INVERSE == 0) n--
        return n
    }

    fun isBlank(): Boolean = contentLength() == 0

    fun text(): String {
        val sb = StringBuilder()
        val n = contentLength()
        for (i in 0 until n) {
            val cp = cps[i]
            when {
                cp == WIDE_TAIL -> Unit
                cp == 0 -> sb.append(' ')
                else -> sb.append(codePointToString(cp))
            }
        }
        return sb.toString()
    }

    fun copy(): TermLine {
        val l = TermLine(0)
        l.cps = cps.copyOf()
        l.attrs = attrs.copyOf()
        l.wrapped = wrapped
        return l
    }
}

/** Callbacks from the emulator to its owner. */
interface TerminalListener {
    /** Bytes the emulator must send back to the program (DSR, DA, …). */
    fun respond(text: String) {}
    fun onTitle(title: String) {}
    fun onBell() {}
    fun onOsc(code: Int, data: String) {}
    fun onClearScrollback() {}
    fun onAltScreen(active: Boolean) {}

    /** Answer for OSC 10/11/12 color queries, as `rgb:rrrr/gggg/bbbb`. */
    fun colorQuery(which: Int): String? = null
}

/**
 * xterm-compatible screen model: main screen with scrollback, alternate screen,
 * scroll regions, SGR (16/256/truecolor), the common DEC private modes and charsets.
 * Not thread-safe; callers synchronize on [lock].
 */
class Terminal(
    cols: Int,
    rows: Int,
    private val maxScrollback: Int = 10_000,
    var listener: TerminalListener = object : TerminalListener {},
) : VtHandler {
    val lock = Any()

    var cols = cols.coerceAtLeast(2)
        private set
    var rows = rows.coerceAtLeast(1)
        private set

    val scrollback = ArrayDeque<TermLine>()

    /** Lines that fell off the scrollback limit (lets callers keep absolute positions). */
    var droppedLines = 0L
        private set

    private var mainLines = Array(this.rows) { TermLine(this.cols) }
    private var altLines: Array<TermLine>? = null
    private val lines: Array<TermLine> get() = altLines ?: mainLines

    val isAltScreen: Boolean get() = altLines != null

    var cursorX = 0
        private set
    var cursorY = 0
        private set
    private var pendingWrap = false

    var attr = 0L
        private set
    private var scrollTop = 0
    private var scrollBottom = this.rows - 1

    // Modes
    var autowrap = true
        private set
    var originMode = false
        private set
    var insertMode = false
        private set
    var newlineMode = false
        private set
    var applicationCursorKeys = false
        private set
    var applicationKeypad = false
        private set
    var bracketedPaste = false
        private set
    var cursorVisible = true
        private set
    var mouseTracking = 0
        private set
    var mouseSgr = false
        private set
    var focusEvents = false
        private set
    var alternateScroll = true
        private set

    var title = ""
        private set

    private var g0Graphics = false
    private var g1Graphics = false
    private var shiftOut = false
    private var tabStops = BooleanArray(this.cols) { it % 8 == 0 }

    private data class SavedCursor(val x: Int, val y: Int, val attr: Long, val origin: Boolean, val g0: Boolean, val wrapPending: Boolean)

    private var savedMain: SavedCursor? = null
    private var savedAlt: SavedCursor? = null

    private val parser = VtParser(this)

    /** Incremented on every change (for UI invalidation). */
    var version = 0L
        private set

    fun feed(text: CharSequence) {
        parser.feed(text)
        version++
    }

    // ── Read API ─────────────────────────────────────────────────────────

    fun screenLine(row: Int): TermLine = lines[row]

    /** Number of rows on the main screen that hold content (used by block rendering). */
    fun mainContentRows(): Int {
        var last = -1
        for (r in mainLines.indices.reversed()) {
            if (!mainLines[r].isBlank()) {
                last = r
                break
            }
        }
        val cursorRow = if (!isAltScreen && cursorX > 0) cursorY else -1
        return maxOf(last, cursorRow) + 1
    }

    /** Scrollback + main screen content, top to bottom. */
    fun mainLineCount(): Int = scrollback.size + mainContentRows()

    fun mainLine(index: Int): TermLine =
        if (index < scrollback.size) scrollback[index] else mainLines[index - scrollback.size]

    /** Cursor row in [mainLine] coordinates. */
    fun mainCursorLine(): Int = scrollback.size + cursorY

    fun plainText(): String = buildString {
        val n = mainLineCount()
        for (i in 0 until n) {
            val l = mainLine(i)
            append(l.text())
            if (i < n - 1 && !l.wrapped) append('\n')
        }
    }

    // ── Resize ───────────────────────────────────────────────────────────

    fun resize(newCols: Int, newRows: Int) {
        val c = newCols.coerceAtLeast(2)
        val r = newRows.coerceAtLeast(1)
        if (c == cols && r == rows) return
        mainLines = resizeScreen(mainLines, c, r, pushToScrollback = true)
        altLines = altLines?.let { resizeScreen(it, c, r, pushToScrollback = false) }
        cols = c
        rows = r
        tabStops = BooleanArray(c) { it % 8 == 0 }
        scrollTop = 0
        scrollBottom = r - 1
        cursorX = cursorX.coerceIn(0, c - 1)
        cursorY = cursorY.coerceIn(0, r - 1)
        pendingWrap = false
        version++
    }

    private fun resizeScreen(screen: Array<TermLine>, c: Int, r: Int, pushToScrollback: Boolean): Array<TermLine> {
        screen.forEach { it.resize(c) }
        val list = screen.toMutableList()
        val isCurrent = screen === lines
        if (r < list.size) {
            // Drop blank lines below the cursor first, then scroll the top into history.
            while (list.size > r && list.size - 1 > (if (isCurrent) cursorY else -1) && list.last().isBlank()) list.removeAt(list.size - 1)
            while (list.size > r) {
                val top = list.removeAt(0)
                if (pushToScrollback) pushScrollback(top)
                if (isCurrent) cursorY = (cursorY - 1).coerceAtLeast(0)
            }
        }
        while (list.size < r) list.add(TermLine(c))
        return list.toTypedArray()
    }

    // ── VtHandler ────────────────────────────────────────────────────────

    override fun print(cp: Int) {
        var c = cp
        val graphics = if (shiftOut) g1Graphics else g0Graphics
        if (graphics && c in 0x5F..0x7E) c = DEC_GRAPHICS[c - 0x5F].code
        val w = charWidth(c)
        if (w == 0) return
        if (pendingWrap) {
            pendingWrap = false
            if (autowrap) {
                lines[cursorY].wrapped = true
                cursorX = 0
                index()
            }
        }
        if (w == 2 && cursorX == cols - 1) {
            if (!autowrap) return
            lines[cursorY].clear(cursorX, cols, blankAttr())
            lines[cursorY].wrapped = true
            cursorX = 0
            index()
        }
        val line = lines[cursorY]
        if (insertMode) shiftRight(line, cursorX, w)
        breakWide(line, cursorX)
        if (w == 2) breakWide(line, cursorX + 1)
        line.cps[cursorX] = c
        line.attrs[cursorX] = attr
        if (w == 2) {
            line.cps[cursorX + 1] = WIDE_TAIL
            line.attrs[cursorX + 1] = attr
        }
        line.touch()
        lastPrinted = c
        cursorX += w
        if (cursorX >= cols) {
            cursorX = cols - 1
            if (autowrap) pendingWrap = true
        }
    }

    /** If [x] is half of a wide character, blank the other half. */
    private fun breakWide(line: TermLine, x: Int) {
        if (x >= cols) return
        if (line.cps[x] == WIDE_TAIL && x > 0) line.cps[x - 1] = 0
        if (x + 1 < cols && line.cps[x + 1] == WIDE_TAIL) line.cps[x + 1] = 0
    }

    override fun execute(code: Int) {
        when (code) {
            0x07 -> listener.onBell()
            0x08 -> {
                if (pendingWrap) pendingWrap = false else if (cursorX > 0) cursorX--
            }
            0x09 -> tab(1)
            0x0A, 0x0B, 0x0C -> {
                index()
                if (newlineMode) cursorX = 0
                pendingWrap = false
            }
            0x0D -> {
                cursorX = 0
                pendingWrap = false
            }
            0x0E -> shiftOut = true
            0x0F -> shiftOut = false
        }
    }

    override fun esc(intermediates: String, final: Char) {
        if (intermediates.isEmpty()) {
            when (final) {
                '7' -> saveCursor()
                '8' -> restoreCursor()
                'D' -> index()
                'E' -> {
                    cursorX = 0
                    index()
                }
                'H' -> if (cursorX < cols) tabStops[cursorX] = true
                'M' -> reverseIndex()
                'c' -> reset()
                '=' -> applicationKeypad = true
                '>' -> applicationKeypad = false
            }
            pendingWrap = false
            return
        }
        when (intermediates) {
            "(" -> g0Graphics = final == '0'
            ")" -> g1Graphics = final == '0'
            "#" -> if (final == '8') {
                // DECALN: fill the screen with 'E'.
                for (l in lines) {
                    for (i in 0 until cols) {
                        l.cps[i] = 'E'.code
                        l.attrs[i] = 0
                    }
                    l.touch()
                }
            }
        }
    }

    override fun csi(params: IntArray, isSub: BooleanArray, paramCount: Int, private: Char, intermediates: String, final: Char) {
        fun p(i: Int, def: Int): Int = if (i < paramCount && params[i] > 0) params[i] else def
        fun p0(i: Int): Int = if (i < paramCount && params[i] >= 0) params[i] else 0

        if (private == '?') {
            when {
                intermediates == "\$" && final == 'p' -> {
                    val mode = p0(0)
                    val state = privateModeState(mode)
                    listener.respond("\u001b[?$mode;$state\$y")
                }
                final == 'h' || final == 'l' -> for (i in 0 until paramCount) setPrivateMode(params[i], final == 'h')
                final == 'J' -> eraseInDisplay(p0(0))
                final == 'K' -> eraseInLine(p0(0))
                final == 'n' -> if (p0(0) == 6) reportCursor(private = true)
            }
            return
        }
        if (private == '>') {
            if (final == 'c') listener.respond("\u001b[>1;10;0c")
            return
        }
        if (private != 0.toChar()) return
        if (intermediates.isNotEmpty()) {
            if (intermediates == "!" && final == 'p') softReset()
            return
        }
        when (final) {
            '@' -> insertChars(p(0, 1))
            'A' -> moveCursor(cursorX, cursorY - p(0, 1), clampToRegion = true)
            'B', 'e' -> moveCursor(cursorX, cursorY + p(0, 1), clampToRegion = true)
            'C', 'a' -> moveCursor(cursorX + p(0, 1), cursorY)
            'D' -> moveCursor(cursorX - p(0, 1), cursorY)
            'E' -> moveCursor(0, cursorY + p(0, 1), clampToRegion = true)
            'F' -> moveCursor(0, cursorY - p(0, 1), clampToRegion = true)
            'G', '`' -> moveCursor(p(0, 1) - 1, cursorY)
            'H', 'f' -> setCursorPosition(p(1, 1) - 1, p(0, 1) - 1)
            'I' -> tab(p(0, 1))
            'J' -> eraseInDisplay(p0(0))
            'K' -> eraseInLine(p0(0))
            'L' -> insertLines(p(0, 1))
            'M' -> deleteLines(p(0, 1))
            'P' -> deleteChars(p(0, 1))
            'S' -> scrollUp(p(0, 1))
            'T' -> if (paramCount <= 1) scrollDown(p(0, 1))
            'X' -> eraseChars(p(0, 1))
            'Z' -> backTab(p(0, 1))
            'b' -> repeatLast(p(0, 1))
            'c' -> if (p0(0) == 0) listener.respond("\u001b[?62;22c")
            'd' -> setCursorPosition(cursorX, p(0, 1) - 1)
            'g' -> when (p0(0)) {
                0 -> if (cursorX < cols) tabStops[cursorX] = false
                3 -> tabStops.fill(false)
            }
            'h', 'l' -> for (i in 0 until paramCount) {
                when (params[i]) {
                    4 -> insertMode = final == 'h'
                    20 -> newlineMode = final == 'h'
                }
            }
            'm' -> sgr(params, isSub, paramCount)
            'n' -> when (p0(0)) {
                5 -> listener.respond("\u001b[0n")
                6 -> reportCursor(private = false)
            }
            'r' -> setScrollRegion(p(0, 1) - 1, p(1, rows) - 1)
            's' -> saveCursor()
            'u' -> restoreCursor()
            't' -> when (p0(0)) {
                18 -> listener.respond("\u001b[8;$rows;${cols}t")
                22, 23 -> Unit // title stack push/pop
            }
        }
    }

    override fun osc(data: String) {
        val sep = data.indexOf(';')
        val code = (if (sep < 0) data else data.substring(0, sep)).toIntOrNull() ?: return
        val rest = if (sep < 0) "" else data.substring(sep + 1)
        when (code) {
            0, 2 -> {
                title = rest
                listener.onTitle(rest)
            }
            10, 11, 12 -> if (rest == "?") {
                listener.colorQuery(code)?.let { listener.respond("\u001b]$code;$it\u001b\\") }
            }
            else -> listener.onOsc(code, rest)
        }
    }

    // ── Operations ───────────────────────────────────────────────────────

    private var lastPrinted = ' '.code

    private fun repeatLast(n: Int) {
        val line = lines[cursorY]
        val prev = if (cursorX > 0) line.cps[cursorX - 1] else lastPrinted
        if (prev <= 0) return
        repeat(minOf(n, cols * rows)) { print(prev) }
    }

    private fun blankAttr(): Long = attr and (Attr.COLOR_MASK shl Attr.COLOR_BITS) // keep background (BCE)

    private fun newLine(): TermLine = TermLine(cols).also { l -> if (blankAttr() != 0L) l.clear(0, cols, blankAttr()) }

    private fun pushScrollback(line: TermLine) {
        scrollback.addLast(line)
        while (scrollback.size > maxScrollback) {
            scrollback.removeFirst()
            droppedLines++
        }
    }

    private fun index() {
        if (cursorY == scrollBottom) {
            scrollUp(1)
        } else if (cursorY < rows - 1) {
            cursorY++
        }
    }

    private fun reverseIndex() {
        if (cursorY == scrollTop) scrollDown(1) else if (cursorY > 0) cursorY--
    }

    private fun scrollUp(n: Int) {
        val screen = lines
        val count = minOf(n, scrollBottom - scrollTop + 1)
        repeat(count) {
            val removed = screen[scrollTop]
            for (i in scrollTop until scrollBottom) screen[i] = screen[i + 1]
            screen[scrollBottom] = newLine()
            if (!isAltScreen && scrollTop == 0) pushScrollback(removed)
        }
    }

    private fun scrollDown(n: Int) {
        val screen = lines
        val count = minOf(n, scrollBottom - scrollTop + 1)
        repeat(count) {
            for (i in scrollBottom downTo scrollTop + 1) screen[i] = screen[i - 1]
            screen[scrollTop] = newLine()
        }
    }

    private fun insertLines(n: Int) {
        if (cursorY !in scrollTop..scrollBottom) return
        val screen = lines
        val count = minOf(n, scrollBottom - cursorY + 1)
        repeat(count) {
            for (i in scrollBottom downTo cursorY + 1) screen[i] = screen[i - 1]
            screen[cursorY] = newLine()
        }
        cursorX = 0
        pendingWrap = false
    }

    private fun deleteLines(n: Int) {
        if (cursorY !in scrollTop..scrollBottom) return
        val screen = lines
        val count = minOf(n, scrollBottom - cursorY + 1)
        repeat(count) {
            for (i in cursorY until scrollBottom) screen[i] = screen[i + 1]
            screen[scrollBottom] = newLine()
        }
        cursorX = 0
        pendingWrap = false
    }

    private fun shiftRight(line: TermLine, from: Int, n: Int) {
        for (i in cols - 1 downTo from + n) {
            line.cps[i] = line.cps[i - n]
            line.attrs[i] = line.attrs[i - n]
        }
        line.clear(from, minOf(from + n, cols), blankAttr())
    }

    private fun insertChars(n: Int) {
        val line = lines[cursorY]
        shiftRight(line, cursorX, minOf(n, cols - cursorX))
        pendingWrap = false
    }

    private fun deleteChars(n: Int) {
        val line = lines[cursorY]
        val count = minOf(n, cols - cursorX)
        for (i in cursorX until cols - count) {
            line.cps[i] = line.cps[i + count]
            line.attrs[i] = line.attrs[i + count]
        }
        line.clear(cols - count, cols, blankAttr())
        pendingWrap = false
    }

    private fun eraseChars(n: Int) {
        lines[cursorY].clear(cursorX, minOf(cols, cursorX + n), blankAttr())
        pendingWrap = false
    }

    private fun eraseInLine(mode: Int) {
        val line = lines[cursorY]
        when (mode) {
            0 -> line.clear(cursorX, cols, blankAttr())
            1 -> line.clear(0, cursorX + 1, blankAttr())
            2 -> line.clear(0, cols, blankAttr())
        }
        if (mode != 1) line.wrapped = false
        pendingWrap = false
    }

    private fun eraseInDisplay(mode: Int) {
        val screen = lines
        when (mode) {
            0 -> {
                eraseInLine(0)
                for (r in cursorY + 1 until rows) screen[r].clear(0, cols, blankAttr())
            }
            1 -> {
                for (r in 0 until cursorY) screen[r].clear(0, cols, blankAttr())
                eraseInLine(1)
            }
            2 -> for (r in 0 until rows) {
                screen[r].clear(0, cols, blankAttr())
                screen[r].wrapped = false
            }
            3 -> {
                if (!isAltScreen) {
                    droppedLines += scrollback.size
                    scrollback.clear()
                    listener.onClearScrollback()
                }
            }
        }
        pendingWrap = false
    }

    private fun tab(n: Int) {
        repeat(n) {
            var x = cursorX + 1
            while (x < cols - 1 && !tabStops[x]) x++
            cursorX = minOf(x, cols - 1)
        }
        pendingWrap = false
    }

    private fun backTab(n: Int) {
        repeat(n) {
            var x = cursorX - 1
            while (x > 0 && !tabStops[x]) x--
            cursorX = maxOf(x, 0)
        }
        pendingWrap = false
    }

    private fun moveCursor(x: Int, y: Int, clampToRegion: Boolean = false) {
        cursorX = x.coerceIn(0, cols - 1)
        cursorY = if (clampToRegion && cursorY in scrollTop..scrollBottom) {
            y.coerceIn(scrollTop, scrollBottom)
        } else {
            y.coerceIn(0, rows - 1)
        }
        pendingWrap = false
    }

    private fun setCursorPosition(x: Int, y: Int) {
        if (originMode) {
            cursorX = x.coerceIn(0, cols - 1)
            cursorY = (y + scrollTop).coerceIn(scrollTop, scrollBottom)
        } else {
            cursorX = x.coerceIn(0, cols - 1)
            cursorY = y.coerceIn(0, rows - 1)
        }
        pendingWrap = false
    }

    private fun setScrollRegion(top: Int, bottom: Int) {
        val t = top.coerceIn(0, rows - 1)
        val b = bottom.coerceIn(0, rows - 1)
        if (t >= b) return
        scrollTop = t
        scrollBottom = b
        setCursorPosition(0, 0)
    }

    private fun reportCursor(private: Boolean) {
        val y = if (originMode) cursorY - scrollTop else cursorY
        listener.respond("\u001b[${if (private) "?" else ""}${y + 1};${cursorX + 1}R")
    }

    private fun saveCursor() {
        val s = SavedCursor(cursorX, cursorY, attr, originMode, g0Graphics, pendingWrap)
        if (isAltScreen) savedAlt = s else savedMain = s
    }

    private fun restoreCursor() {
        val s = (if (isAltScreen) savedAlt else savedMain) ?: SavedCursor(0, 0, 0, false, false, false)
        cursorX = s.x.coerceIn(0, cols - 1)
        cursorY = s.y.coerceIn(0, rows - 1)
        attr = s.attr
        originMode = s.origin
        g0Graphics = s.g0
        pendingWrap = s.wrapPending
    }

    private fun enterAltScreen(saveCursor: Boolean, clear: Boolean) {
        if (isAltScreen) return
        if (saveCursor) saveCursor()
        val alt = altLines ?: Array(rows) { TermLine(cols) }
        if (clear) alt.forEach { it.clear(0, cols, 0) }
        altLines = alt
        scrollTop = 0
        scrollBottom = rows - 1
        listener.onAltScreen(true)
    }

    private fun exitAltScreen(restoreCursor: Boolean) {
        if (!isAltScreen) return
        altLines = null
        scrollTop = 0
        scrollBottom = rows - 1
        if (restoreCursor) restoreCursor()
        listener.onAltScreen(false)
    }

    private fun privateModeState(mode: Int): Int = when (mode) {
        1 -> if (applicationCursorKeys) 1 else 2
        6 -> if (originMode) 1 else 2
        7 -> if (autowrap) 1 else 2
        25 -> if (cursorVisible) 1 else 2
        1049, 1047, 47 -> if (isAltScreen) 1 else 2
        2004 -> if (bracketedPaste) 1 else 2
        1000, 1002, 1003 -> if (mouseTracking == mode) 1 else 2
        1006 -> if (mouseSgr) 1 else 2
        else -> 0
    }

    private fun setPrivateMode(mode: Int, on: Boolean) {
        when (mode) {
            1 -> applicationCursorKeys = on
            6 -> {
                originMode = on
                setCursorPosition(0, 0)
            }
            7 -> autowrap = on
            25 -> cursorVisible = on
            47 -> if (on) enterAltScreen(saveCursor = false, clear = false) else exitAltScreen(restoreCursor = false)
            1047 -> if (on) enterAltScreen(saveCursor = false, clear = false) else {
                altLines?.forEach { it.clear(0, cols, 0) }
                exitAltScreen(restoreCursor = false)
            }
            1048 -> if (on) saveCursor() else restoreCursor()
            1049 -> if (on) enterAltScreen(saveCursor = true, clear = true) else exitAltScreen(restoreCursor = true)
            1000, 1002, 1003 -> mouseTracking = if (on) mode else 0
            1004 -> focusEvents = on
            1006 -> mouseSgr = on
            1007 -> alternateScroll = on
            2004 -> bracketedPaste = on
        }
    }

    private fun softReset() {
        cursorVisible = true
        originMode = false
        autowrap = true
        insertMode = false
        applicationCursorKeys = false
        applicationKeypad = false
        scrollTop = 0
        scrollBottom = rows - 1
        attr = 0
        g0Graphics = false
        g1Graphics = false
        shiftOut = false
        savedMain = null
        savedAlt = null
    }

    fun reset() {
        exitAltScreen(restoreCursor = false)
        softReset()
        bracketedPaste = false
        mouseTracking = 0
        mouseSgr = false
        newlineMode = false
        mainLines.forEach { it.clear(0, cols, 0) }
        tabStops = BooleanArray(cols) { it % 8 == 0 }
        cursorX = 0
        cursorY = 0
        pendingWrap = false
    }

    private fun sgr(params: IntArray, isSub: BooleanArray, count: Int) {
        if (count == 0) {
            attr = 0
            return
        }
        var i = 0
        while (i < count) {
            val code = if (params[i] < 0) 0 else params[i]
            when (code) {
                0 -> attr = 0
                1 -> attr = Attr.withFlag(attr, Attr.BOLD, true)
                2 -> attr = Attr.withFlag(attr, Attr.DIM, true)
                3 -> attr = Attr.withFlag(attr, Attr.ITALIC, true)
                4 -> {
                    // 4:0 disables, 4:n (n>0) is a styled underline.
                    val style = if (i + 1 < count && isSub[i + 1]) params[++i] else 1
                    attr = Attr.withFlag(attr, Attr.UNDERLINE, style != 0)
                }
                7 -> attr = Attr.withFlag(attr, Attr.INVERSE, true)
                8 -> attr = Attr.withFlag(attr, Attr.HIDDEN, true)
                9 -> attr = Attr.withFlag(attr, Attr.STRIKE, true)
                21 -> attr = Attr.withFlag(attr, Attr.UNDERLINE, true)
                22 -> attr = Attr.withFlag(Attr.withFlag(attr, Attr.BOLD, false), Attr.DIM, false)
                23 -> attr = Attr.withFlag(attr, Attr.ITALIC, false)
                24 -> attr = Attr.withFlag(attr, Attr.UNDERLINE, false)
                27 -> attr = Attr.withFlag(attr, Attr.INVERSE, false)
                28 -> attr = Attr.withFlag(attr, Attr.HIDDEN, false)
                29 -> attr = Attr.withFlag(attr, Attr.STRIKE, false)
                in 30..37 -> attr = Attr.withFg(attr, Attr.palette(code - 30))
                39 -> attr = Attr.withFg(attr, 0)
                in 40..47 -> attr = Attr.withBg(attr, Attr.palette(code - 40))
                49 -> attr = Attr.withBg(attr, 0)
                in 90..97 -> attr = Attr.withFg(attr, Attr.palette(code - 90 + 8))
                in 100..107 -> attr = Attr.withBg(attr, Attr.palette(code - 100 + 8))
                38, 48, 58 -> {
                    val (color, consumed) = extendedColor(params, isSub, count, i)
                    if (color != null) {
                        if (code == 38) attr = Attr.withFg(attr, color)
                        if (code == 48) attr = Attr.withBg(attr, color)
                    }
                    i += consumed
                }
            }
            i++
        }
    }

    /** Parses `38;5;n`, `38;2;r;g;b` and the colon forms. Returns color and number of extra params consumed. */
    private fun extendedColor(params: IntArray, isSub: BooleanArray, count: Int, at: Int): Pair<Int?, Int> {
        if (at + 1 >= count) return null to 0
        val colon = isSub[at + 1]
        val kind = params[at + 1]
        if (kind == 5) {
            if (at + 2 >= count) return null to 1
            return Attr.palette(params[at + 2].coerceIn(0, 255)) to 2
        }
        if (kind == 2) {
            // Colon form may carry a color-space id: 38:2:<cs>:r:g:b
            var start = at + 2
            if (colon) {
                var subs = 0
                var j = at + 2
                while (j < count && isSub[j]) {
                    subs++
                    j++
                }
                if (subs >= 4) start = at + 3
                if (start + 2 >= count) return null to (j - at - 1)
                val c = Attr.rgb(params[start].coerceAtLeast(0), params[start + 1].coerceAtLeast(0), params[start + 2].coerceAtLeast(0))
                return c to (j - at - 1)
            }
            if (start + 2 >= count) return null to (count - at - 1)
            return Attr.rgb(params[start].coerceAtLeast(0), params[start + 1].coerceAtLeast(0), params[start + 2].coerceAtLeast(0)) to 4
        }
        return null to 1
    }

    private companion object {
        /** DEC Special Graphics for 0x5F..0x7E. */
        const val DEC_GRAPHICS = " ◆▒␉␌␍␊°±␤␋┘┐┌└┼⎺⎻─⎼⎽├┤┴┬│≤≥π≠£·"
    }
}
