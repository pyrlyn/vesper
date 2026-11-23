package scull.input

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

private const val COL = 9
private const val ROW = 4

// COL and ROW as X10 bytes: one-based plus 32.
private const val CX = '*'.code
private const val CY = '%'.code
private const val SEED = 0x5C012

class MouseTest {
    private fun ev(
        action: MouseAction,
        button: MouseButton?,
        mods: Modifiers = NONE,
    ) = MouseEvent(action, button, mods, COL, ROW, 100, 50)

    private fun press(button: MouseButton) = ev(MouseAction.Press, button)

    private fun release(button: MouseButton) = ev(MouseAction.Release, button)

    private fun motion(button: MouseButton?) = ev(MouseAction.Motion, button)

    private fun enc(
        event: MouseEvent,
        tracking: MouseTracking,
        encoding: MouseEncoding = MouseEncoding.Default,
    ): ByteArray {
        val out = ByteSink()
        encodeMouse(event, MouseModes(tracking, encoding), out)
        return out.toByteArray()
    }

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private fun x10Report(cb: Int) = bytes(0x1b, '['.code, 'M'.code, cb, CX, CY)

    @Test
    fun buttonCodesMatchXtermNormalTracking() {
        val table =
            listOf(
                press(MouseButton.Left) to ' '.code,
                press(MouseButton.Middle) to '!'.code,
                press(MouseButton.Right) to '"'.code,
                release(MouseButton.Right) to '#'.code,
                ev(MouseAction.Press, MouseButton.Right, CTRL) to '2'.code,
                ev(MouseAction.Press, MouseButton.Left, SHIFT) to '$'.code,
                ev(MouseAction.Press, MouseButton.Left, ALT) to '('.code,
                press(MouseButton.WheelUp) to '`'.code,
                press(MouseButton.WheelDown) to 'a'.code,
                press(MouseButton.WheelLeft) to 'b'.code,
                press(MouseButton.WheelRight) to 'c'.code,
                press(MouseButton.Back) to 160,
                press(MouseButton.Button11) to 163,
                release(MouseButton.Back) to '#'.code,
            )
        for ((event, cb) in table) assertContentEquals(x10Report(cb), enc(event, MouseTracking.Normal), "$event")
    }

    @Test
    fun trackingModesChooseWhichEventsAreReported() {
        val modes = MouseTracking.entries
        // Off, X10, Normal, ButtonEvent, AnyEvent; null is no report.
        val table =
            listOf(
                press(MouseButton.Left) to listOf(null, ' ', ' ', ' ', ' '),
                release(MouseButton.Left) to listOf(null, null, '#', '#', '#'),
                press(MouseButton.WheelUp) to listOf(null, null, '`', '`', '`'),
                release(MouseButton.WheelUp) to listOf(null, null, null, null, null),
                motion(MouseButton.Left) to listOf(null, null, null, '@', '@'),
                motion(MouseButton.Right) to listOf(null, null, null, 'B', 'B'),
                motion(null) to listOf(null, null, null, null, 'C'),
            )
        for ((event, row) in table) {
            for ((tracking, cb) in modes.zip(row)) {
                val expected = cb?.let { x10Report(it.code) } ?: ByteArray(0)
                assertContentEquals(expected, enc(event, tracking), "$event $tracking")
            }
        }
    }

    @Test
    fun x10ModeReportsNoModifiersAndOnlyThreeButtons() {
        val ctrlLeft = ev(MouseAction.Press, MouseButton.Left, CTRL)
        assertContentEquals(x10Report(' '.code), enc(ctrlLeft, MouseTracking.X10))
        assertContentEquals(ByteArray(0), enc(press(MouseButton.Back), MouseTracking.X10))
    }

    @Test
    fun defaultEncodingSendsThePastEndMarkerBeyond223() {
        fun at(col: Int) = enc(press(MouseButton.Left).copy(col = col, row = 0), MouseTracking.Normal)
        assertContentEquals(bytes(0x1b, '['.code, 'M'.code, ' '.code, '!'.code, '!'.code), at(0))
        assertContentEquals(bytes(0x1b, '['.code, 'M'.code, ' '.code, 0xff, '!'.code), at(222))
        assertContentEquals(bytes(0x1b, '['.code, 'M'.code, ' '.code, 0, '!'.code), at(223))
        assertContentEquals(bytes(0x1b, '['.code, 'M'.code, ' '.code, 0, '!'.code), at(Int.MAX_VALUE))
    }

    @Test
    fun utf8EncodingExtendsCoordinatesTo2015() {
        fun at(col: Int) = enc(press(MouseButton.Left).copy(col = col, row = 0), MouseTracking.Normal, MouseEncoding.Utf8)
        assertContentEquals(bytes(0x1b, '['.code, 'M'.code, ' '.code, 0x7f, '!'.code), at(94))
        assertContentEquals(bytes(0x1b, '['.code, 'M'.code, ' '.code, 0xc2, 0x80, '!'.code), at(95))
        assertContentEquals(bytes(0x1b, '['.code, 'M'.code, ' '.code, 0xdf, 0xbf, '!'.code), at(2014))
        assertContentEquals(bytes(0x1b, '['.code, 'M'.code, ' '.code, 0, '!'.code), at(2015))
        val back = enc(press(MouseButton.Back), MouseTracking.Normal, MouseEncoding.Utf8)
        assertContentEquals(bytes(0x1b, '['.code, 'M'.code, 0xc2, 0xa0, CX, CY), back)
    }

    @Test
    fun sgrReportsTheReleasedButtonWithALowercaseFinal() {
        fun sgr(e: MouseEvent) = enc(e, MouseTracking.AnyEvent, MouseEncoding.Sgr).decodeToString()
        val table =
            listOf(
                press(MouseButton.Left) to "0;10;5M",
                release(MouseButton.Left) to "0;10;5m",
                release(MouseButton.Right) to "2;10;5m",
                release(MouseButton.Back) to "128;10;5m",
                press(MouseButton.WheelDown) to "65;10;5M",
                motion(null) to "35;10;5M",
                motion(MouseButton.Middle) to "33;10;5M",
                ev(MouseAction.Press, MouseButton.Left, CTRL_SHIFT) to "20;10;5M",
            )
        for ((event, expected) in table) assertEquals("\u001b[<$expected", sgr(event), "$event")
        val far = press(MouseButton.Left).copy(col = Int.MAX_VALUE, row = 5000)
        assertEquals("\u001b[<0;${Int.MAX_VALUE};5001M", sgr(far))
    }

    @Test
    fun urxvtReportsX10CodesInDecimal() {
        fun urxvt(e: MouseEvent) = enc(e, MouseTracking.Normal, MouseEncoding.Urxvt).decodeToString()
        assertEquals("\u001b[32;10;5M", urxvt(press(MouseButton.Left)))
        assertEquals("\u001b[35;10;5M", urxvt(release(MouseButton.Left)))
        assertEquals("\u001b[96;10;5M", urxvt(press(MouseButton.WheelUp)))
    }

    @Test
    fun sgrPixelsReportsOneBasedPixelPositions() {
        fun px(e: MouseEvent) = enc(e, MouseTracking.Normal, MouseEncoding.SgrPixels).decodeToString()
        assertEquals("\u001b[<0;101;51M", px(press(MouseButton.Left)))
        assertEquals("\u001b[<0;101;51m", px(release(MouseButton.Left)))
    }

    @Test
    fun aNegativePositionIsRefused() {
        assertFailsWith<IllegalArgumentException> { MouseEvent(MouseAction.Press, MouseButton.Left, col = -1, row = 0) }
    }

    @Test
    fun noMouseEventThrows() {
        val random = Random(SEED)
        val out = ByteSink()
        repeat(20_000) {
            val col = if (random.nextBoolean()) random.nextInt(300) else random.nextInt(Int.MAX_VALUE) + 1
            val row = random.nextInt(3000)
            val event =
                MouseEvent(
                    action = MouseAction.entries.random(random),
                    button = if (random.nextInt(4) == 0) null else MouseButton.entries.random(random),
                    mods = Modifiers.of(random.nextInt(0x100)),
                    col = col,
                    row = row,
                    xPx = col,
                    yPx = row,
                )
            out.clear()
            encodeMouse(event, MouseModes(MouseTracking.entries.random(random), MouseEncoding.entries.random(random)), out)
        }
    }
}
