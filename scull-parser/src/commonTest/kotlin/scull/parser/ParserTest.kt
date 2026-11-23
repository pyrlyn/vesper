package scull.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Raw bytes from a string whose chars are all below U+0100, for sequences with invalid UTF-8. */
internal fun raw(text: String): ByteArray = ByteArray(text.length) { text[it].code.toByte() }

internal fun parse(bytes: ByteArray): List<Action> = Recorder().also { Parser().feed(bytes, it) }.actions

internal fun parseBytewise(bytes: ByteArray): List<Action> {
    val recorder = Recorder()
    val parser = Parser()
    for (i in bytes.indices) parser.feed(bytes, i, i + 1, recorder)
    return recorder.actions
}

private fun print(text: String) = Action.Print(text)

private fun csi(
    params: List<List<Int>>,
    private: Int = 0,
    intermediates: String = "",
    finalByte: Char,
) = Action.Csi(params, intermediates.map { it.code }, private, finalByte.code, truncated = false)

private fun esc(
    intermediates: String,
    finalByte: Char,
) = Action.Esc(intermediates.map { it.code }, finalByte.code)

private fun data(text: String) = raw(text).toList()

class ParserTest {
    @Test
    fun textUpToAControlByteIsOnePrint() {
        val prints = mutableListOf<String>()
        val handler =
            object : Handler {
                override fun print(
                    bytes: ByteArray,
                    start: Int,
                    end: Int,
                ) {
                    prints += bytes.decodeToString(start, end)
                }
            }
        Parser().feed(raw("hello world\r\nnext"), handler)
        assertEquals(listOf("hello world", "next"), prints)
    }

    @Test
    fun controlsBetweenRunsAreExecutedInOrder() {
        assertEquals(
            listOf(print("a"), Action.Execute(0x0D), Action.Execute(0x0A), print("b")),
            parse(raw("a\r\nb")),
        )
    }

    @Test
    fun invalidUtf8BecomesReplacementCharacters() {
        assertEquals(listOf(print("a�b�c")), parse(raw("aÿbâ\u0082c")))
    }

    @Test
    fun utf8SplitAcrossFeedsIsCompleted() {
        val text = "日本🎉é"
        assertEquals(listOf(print(text)), parseBytewise(text.encodeToByteArray()))
    }

    @Test
    fun incompleteUtf8BeforeAControlByteIsInvalid() {
        val bytes = raw("â\u0082\n")
        assertEquals(listOf(print("�"), Action.Execute(0x0A)), parse(bytes))
        assertEquals(parse(bytes), parseBytewise(bytes))
    }

    @Test
    fun delIsIgnoredInText() {
        assertEquals(listOf(print("ab")), parse(raw("a\u007Fb")))
    }

    @Test
    fun escDispatchCarriesIntermediates() {
        assertEquals(listOf(esc("", '7'), esc("(", 'B')), parse(raw("\u001B7\u001B(B")))
    }

    @Test
    fun escWithTooManyIntermediatesIsIgnored() {
        assertEquals(emptyList<Action>(), parse(raw("\u001B" + "(".repeat(MAX_INTERMEDIATES + 1) + "B")))
    }

    @Test
    fun csiWithParamsPrivateMarkerAndIntermediates() {
        assertEquals(
            listOf(
                csi(listOf(listOf(1), listOf(38, 2, 0, 1, 2, 3)), finalByte = 'm'),
                csi(listOf(listOf(25)), private = '?'.code, finalByte = 'h'),
                csi(listOf(listOf(2)), intermediates = " ", finalByte = 'q'),
            ),
            parse(raw("\u001B[1;38:2::1:2:3m\u001B[?25h\u001B[2 q")),
        )
    }

    @Test
    fun c0InsideCsiExecutesWithoutBreakingIt() {
        assertEquals(listOf(Action.Execute(0x0A), csi(listOf(listOf(31)), finalByte = 'm')), parse(raw("\u001B[3\n1m")))
    }

    @Test
    fun malformedCsiIsIgnoredUpToItsFinalByte() {
        assertEquals(listOf(print("XY")), parse(raw("\u001B[1?2mX\u001B[ 1mY")))
    }

    @Test
    fun csiWithTooManyIntermediatesIsIgnored() {
        assertEquals(listOf(print("ok")), parse(raw("\u001B[1   qok")))
    }

    @Test
    fun csiWithTooManyParamsIsTruncatedAndFlagged() {
        val actions = parse(raw("\u001B[" + List(MAX_PARAMS + OVERFLOW) { "1" }.joinToString(";") + "m"))
        val only = actions.single() as Action.Csi
        assertEquals(MAX_PARAMS, only.params.size)
        assertTrue(only.truncated)
    }

    @Test
    fun hugeParameterValuesSaturate() {
        assertEquals(listOf(csi(listOf(listOf(MAX_PARAM_VALUE)), finalByte = 'H')), parse(raw("\u001B[99999999999H")))
    }

    @Test
    fun canAbortsASequenceAndExecutes() {
        assertEquals(listOf(Action.Execute(0x18), print("m")), parse(raw("\u001B[12\u0018m")))
    }

    @Test
    fun highByteInsideCsiAbortsItAndIsPrinted() {
        assertEquals(listOf(print("é")), parse("\u001B[1é".encodeToByteArray()))
    }

    @Test
    fun utf8C1ControlsActAsTheirEscapeForms() {
        val bytes = "a\u0085b\u009B2J".encodeToByteArray()
        assertEquals(listOf(print("a"), esc("", 'E'), print("b"), csi(listOf(listOf(2)), finalByte = 'J')), parse(bytes))
        assertEquals(parse(bytes), parseBytewise(bytes))
    }

    @Test
    fun latin1SupplementIsTextNotC1() {
        assertEquals(listOf(print("©«»")), parse("©«»".encodeToByteArray()))
    }

    @Test
    fun oscEndsOnBelOrSt() {
        assertEquals(
            listOf(Action.Osc(data("0;title"), true), Action.Osc(data("8;;http://x"), false)),
            parse(raw("\u001B]0;title\u0007\u001B]8;;http://x\u001B\\")),
        )
    }

    @Test
    fun oscFieldsSplitOnSemicolons() {
        var fields = emptyList<String>()
        val handler =
            object : Handler {
                override fun oscDispatch(osc: Osc) {
                    fields = osc.fields().map { it.decodeToString() }
                }
            }
        Parser().feed(raw("\u001B]8;;a;b\u0007"), handler)
        assertEquals(listOf("8", "", "a", "b"), fields)
    }

    @Test
    fun oscDropsC0ControlsFromItsPayload() {
        assertEquals(listOf(Action.Osc(data("2;ab"), true)), parse(raw("\u001B]2;a\u0008\u000Db\u0007")))
    }

    @Test
    fun escWithoutBackslashCancelsAnOsc() {
        assertEquals(listOf(csi(listOf(listOf(1)), finalByte = 'm')), parse(raw("\u001B]2;lost\u001B[1m")))
    }

    @Test
    fun escWithoutBackslashCancelsADcs() {
        assertEquals(
            listOf(Action.Hook(emptyList(), emptyList(), 'q'.code), Action.Put(data("data")), Action.Unhook(End.Cancelled), esc("", '7')),
            parse(raw("\u001BPqdata\u001B7")),
        )
    }

    @Test
    fun dcsHooksStreamsItsPayloadAndUnhooks() {
        assertEquals(
            listOf(Action.Hook(listOf(listOf(1)), listOf('$'.code), 'q'.code), Action.Put(data("m")), Action.Unhook(End.Complete)),
            parse(raw("\u001BP1\$qm\u001B\\")),
        )
    }

    @Test
    fun malformedDcsHeaderIsIgnoredUntilSt() {
        assertEquals(listOf(print("ok")), parse(raw("\u001BP1?2qpayload\u001B\\ok")))
    }

    @Test
    fun apcStreamsItsPayload() {
        assertEquals(
            listOf(Action.ApcStart, Action.ApcPut(data("Ga=T;AAAA")), Action.ApcEnd(End.Complete)),
            parse(raw("\u001B_Ga=T;AAAA\u001B\\")),
        )
    }

    @Test
    fun apcCancelledByCanSaysSo() {
        assertEquals(
            listOf(Action.ApcStart, Action.ApcPut(data("Gx")), Action.ApcEnd(End.Cancelled), Action.Execute(0x18)),
            parse(raw("\u001B_Gx\u0018")),
        )
    }

    @Test
    fun sosAndPmAreSwallowed() {
        assertEquals(listOf(print("ok")), parse(raw("\u001BXsecret\u001B\\\u001B^pm\u001B\\ok")))
    }

    // Fuzz regression from the Rust parser: an ignored string after a cancelled APC computed its
    // room from the APC's byte count and underflowed.
    @Test
    fun pmRightAfterACancelledApcIsSwallowed() {
        assertEquals(
            listOf(Action.ApcStart, Action.ApcPut(data("abc")), Action.ApcEnd(End.Cancelled), print("ok")),
            parse(raw("\u001B_abc\u001B^x\u001B\\ok")),
        )
    }

    @Test
    fun feedTakesASubrangeAndRejectsABadOne() {
        val bytes = raw("xxab\u001B[1mxx")
        val recorder = Recorder()
        Parser().feed(bytes, 2, bytes.size - 2, recorder)
        assertEquals(listOf(print("ab"), csi(listOf(listOf(1)), finalByte = 'm')), recorder.actions)
        kotlin.test.assertFailsWith<IllegalArgumentException> { Parser().feed(bytes, 3, 2, recorder) }
    }
}

/** How far past a cap the overflow tests go. */
internal const val OVERFLOW = 4096
