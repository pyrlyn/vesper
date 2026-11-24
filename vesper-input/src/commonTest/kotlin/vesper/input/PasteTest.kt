package vesper.input

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

private const val START = "\u001b[200~"
private const val END = "\u001b[201~"
private const val SEED = 0x5C013

class PasteTest {
    private fun paste(
        text: String,
        bracketed: Boolean,
    ) = bytesOf { encodePaste(text, bracketed, it) }

    @Test
    fun bracketedPasteWrapsTheTextInMarkers() {
        assertEquals("${START}ls -l\n$END", paste("ls -l\n", true))
        assertEquals("$START$END", paste("", true))
    }

    @Test
    fun markersInsideTheTextAreRemoved() {
        val table =
            listOf(
                "a\u001b[201~rm -rf ~\n" to "arm -rf ~\n",
                "\u001b[200~x" to "x",
                "x\u009b201~y" to "xy",
                "x\u009b200~y" to "xy",
                // Removing the inner marker must not leave an outer one behind.
                "\u001b[20\u001b[201~1~z" to "z",
                "\u001b[2\u001b[20\u001b[201~1~01~!" to "!",
            )
        for ((text, body) in table) assertEquals("$START$body$END", paste(text, true), text)
    }

    @Test
    fun otherEscapeSequencesPassThroughUntouched() {
        assertEquals("$START\u001b[31mred$END", paste("\u001b[31mred", true))
    }

    @Test
    fun unbracketedPasteSendsLineFeedsAsCarriageReturns() {
        assertEquals("a\rb\rc\r", paste("a\nb\r\nc\r", false))
        assertEquals(END, paste(END, false))
    }

    @Test
    fun focusChangesAreCsiIAndCsiO() {
        val reports =
            bytesOf {
                encodeFocus(true, it)
                encodeFocus(false, it)
            }
        assertEquals("\u001b[I\u001b[O", reports)
    }

    @Test
    fun aBracketedBodyNeverContainsAMarker() {
        val parts = listOf("\u001b", "[", "2", "0", "1", "~", "\u009b", "a")
        val random = Random(SEED)
        repeat(5_000) {
            val text = (0 until random.nextInt(64)).joinToString("") { parts.random(random) }
            val out = ByteSink()
            encodePaste(text, true, out)
            val bytes = out.toByteArray()
            val body = bytes.copyOfRange(START.length, bytes.size - END.length)
            for (marker in PASTE_MARKERS) assertFalse(body.contains(marker), text)
        }
    }

    private fun ByteArray.contains(needle: ByteArray): Boolean =
        (0..size - needle.size).any { start -> needle.indices.all { this[start + it] == needle[it] } }
}
