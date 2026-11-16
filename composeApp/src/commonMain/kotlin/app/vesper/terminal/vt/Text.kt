package app.vesper.terminal.vt

/** Streaming UTF-8 decoder that keeps incomplete sequences between reads. */
class Utf8Decoder {
    private var need = 0
    private var acc = 0
    private var min = 0

    fun decode(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): String {
        val sb = StringBuilder(length)
        for (i in offset until offset + length) {
            val b = bytes[i].toInt() and 0xFF
            if (need > 0) {
                if (b and 0xC0 == 0x80) {
                    acc = (acc shl 6) or (b and 0x3F)
                    need--
                    if (need == 0) emit(sb, if (acc < min) 0xFFFD else acc)
                    continue
                }
                sb.append('�')
                need = 0
            }
            when {
                b < 0x80 -> sb.append(b.toChar())
                b and 0xE0 == 0xC0 -> start(b and 0x1F, 1, 0x80)
                b and 0xF0 == 0xE0 -> start(b and 0x0F, 2, 0x800)
                b and 0xF8 == 0xF0 -> start(b and 0x07, 3, 0x10000)
                else -> sb.append('�')
            }
        }
        return sb.toString()
    }

    private fun start(bits: Int, n: Int, minimum: Int) {
        acc = bits
        need = n
        min = minimum
    }

    private fun emit(sb: StringBuilder, cp: Int) {
        when {
            cp > 0x10FFFF || cp in 0xD800..0xDFFF -> sb.append('�')
            cp < 0x10000 -> sb.append(cp.toChar())
            else -> {
                val v = cp - 0x10000
                sb.append((0xD800 + (v shr 10)).toChar())
                sb.append((0xDC00 + (v and 0x3FF)).toChar())
            }
        }
    }
}

/** Column width of a code point: 0 (combining / zero-width), 1, or 2 (East Asian wide, emoji). */
fun charWidth(cp: Int): Int = when {
    cp < 0x300 -> 1
    cp in 0x300..0x36F || cp in 0x1AB0..0x1AFF || cp in 0x1DC0..0x1DFF || cp in 0x20D0..0x20FF -> 0
    cp == 0x200B || cp == 0x200C || cp == 0x200D || cp == 0x2060 || cp in 0xFE00..0xFE0F || cp in 0xFE20..0xFE2F -> 0
    cp in 0x1100..0x115F || cp in 0x2E80..0x303E || cp in 0x3041..0x33FF || cp in 0x3400..0x4DBF -> 2
    cp in 0x4E00..0x9FFF || cp in 0xA000..0xA4CF || cp in 0xAC00..0xD7A3 || cp in 0xF900..0xFAFF -> 2
    cp in 0xFE30..0xFE4F || cp in 0xFF00..0xFF60 || cp in 0xFFE0..0xFFE6 -> 2
    cp in 0x1F300..0x1F64F || cp in 0x1F680..0x1F6FF || cp in 0x1F900..0x1F9FF || cp in 0x1FA70..0x1FAFF -> 2
    cp in 0x20000..0x3FFFD -> 2
    else -> 1
}

fun codePointToString(cp: Int): String = if (cp < 0x10000) {
    cp.toChar().toString()
} else {
    val v = cp - 0x10000
    charArrayOf((0xD800 + (v shr 10)).toChar(), (0xDC00 + (v and 0x3FF)).toChar()).concatToString()
}
