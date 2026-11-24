package vesper.parser

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Seeded so a failure reproduces; the corpus is the fuzz target's regression net in CI.
private const val SEED = 0x00C0FFEE
private const val CASES = 3000
private const val MAX_LENGTH = 400

/** Bytes that change state, weighted up so short inputs still reach every state. */
private val interesting = raw("\u001B\u0007\u0018\u001A\u007F\n[]P_X^\\;:?019m q\$Â\u0085\u009Bâ\u0082¬ð\u009F\u008E\u0089ÿ")

private fun Random.stream(): ByteArray =
    ByteArray(nextInt(MAX_LENGTH)) {
        when (nextInt(3)) {
            0 -> interesting[nextInt(interesting.size)]
            1 -> nextInt(256).toByte()
            else -> 'a'.code.toByte()
        }
    }

class PropertyTest {
    @Test
    fun fuzzCorpusIsChunkingInvariant() {
        val random = Random(SEED)
        repeat(CASES) { parserFeedIsChunkingInvariant(random.stream()) }
        listOf(ByteArray(0), raw("\u001B"), raw("Â"), raw("\u001BP"), raw("\u001B]\u001B")).forEach(::parserFeedIsChunkingInvariant)
    }

    @Test
    fun textWithoutControlsIsPrintedUnchanged() {
        val random = Random(SEED)
        repeat(CASES) {
            val text =
                buildString {
                    repeat(random.nextInt(200)) {
                        var cp: Int
                        do {
                            cp = random.nextInt(0x110000)
                        } while (cp < 0x20 || cp in 0x7F..0x9F || cp in 0xD800..0xDFFF)
                        appendCodePoint(cp)
                    }
                }
            val expected = if (text.isEmpty()) emptyList() else listOf(Action.Print(text))
            assertEquals(expected, parse(text.encodeToByteArray()))
        }
    }

    @Test
    fun arbitraryBytesPrintTheirLossyDecodingWhenFreeOfControls() {
        val random = Random(SEED)
        var checked = 0
        repeat(CASES) {
            val bytes =
                ByteArray(random.nextInt(MAX_LENGTH)) {
                    (if (random.nextBoolean()) random.nextInt(0x20, 0x7F) else random.nextInt(0x80, 0x100)).toByte()
                }
            val lossy = bytes.decodeToString()
            if (lossy.any { it.code in 0x80..0x9F }) return@repeat
            // A control byte settles a UTF-8 sequence cut at the end of the input.
            val printed = parse(bytes + '\n'.code.toByte()).filterIsInstance<Action.Print>().joinToString("") { it.text }
            assertEquals(lossy, printed)
            checked++
        }
        assertTrue(checked > CASES / 10, "only $checked cases were free of C1 controls")
    }

    @Test
    fun utf8CheckAgreesWithTheStrictDecoderOnEveryLeadAndSecondByte() {
        for (lead in 0x80..0xFF) {
            for (second in 0..0xFF) {
                val bytes = byteArrayOf(lead.toByte(), second.toByte(), 0x80.toByte(), 0x80.toByte())
                val width = utf8Width(lead)
                val check = utf8Check(bytes, 0, bytes.size)
                val strict = width > 0 && runCatching { bytes.decodeToString(0, width, throwOnInvalidSequence = true) }.isSuccess
                assertEquals(strict, check == width, "lead ${lead.toString(16)} second ${second.toString(16)}")
            }
        }
    }
}

private fun StringBuilder.appendCodePoint(cp: Int) {
    if (cp < 0x10000) {
        append(cp.toChar())
    } else {
        val v = cp - 0x10000
        append((0xD800 + (v shr 10)).toChar())
        append((0xDC00 + (v and 0x3FF)).toChar())
    }
}
