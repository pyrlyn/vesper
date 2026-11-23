package scull.term

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

private const val BSU = "$CSI?2026h"
private const val ESU = "$CSI?2026l"

class SyncGateTest {
    @Test
    fun anInactiveModeNeverHoldsAndForgetsTheHold() {
        val now = TimeSource.Monotonic.markNow()
        val g = SyncGate()
        assertTrue(g.holds(true, now))
        assertFalse(g.fed(true, 1))
        assertFalse(g.holds(false, now))
        assertNull(g.deadline)
        assertFalse(g.fed(true, MAX_SYNC_BYTES), "the byte count started over")
    }

    @Test
    fun theHoldIsTimedFromTheFirstLook() {
        val start = TimeSource.Monotonic.markNow()
        val g = SyncGate()
        assertFalse(g.fed(true, 10))
        assertNull(g.deadline, "not seen yet")
        assertTrue(g.holds(true, start))
        assertEquals(start + MAX_SYNC_HOLD, g.deadline)
        assertTrue(g.holds(true, start + MAX_SYNC_HOLD - 1.milliseconds))
        assertFalse(g.holds(true, start + MAX_SYNC_HOLD))
    }

    @Test
    fun bytesAddUpAcrossFeedsUntilTheCap() {
        val g = SyncGate()
        assertFalse(g.fed(true, MAX_SYNC_BYTES))
        assertTrue(g.fed(true, 1))
        assertFalse(g.fed(false, 1), "a reset mode starts over")
        assertFalse(g.fed(true, MAX_SYNC_BYTES))
    }
}

class TerminalSyncTest {
    @Test
    fun synchronizedOutputHoldsThePictureUntilEsu() {
        val now = TimeSource.Monotonic.markNow()
        val term = Terminal(10, 5, 0)
        assertFalse(term.syncHeld(now))
        term.feed(BSU)
        term.feed("half a frame")
        assertTrue(term.syncHeld(now))
        assertEquals(now + MAX_SYNC_HOLD, term.syncDeadline)
        term.feed(ESU)
        assertFalse(term.syncHeld(now))
        assertNull(term.syncDeadline)
    }

    @Test
    fun aHoldPastTheTimeCapIsReleasedAndReadsAsReset() {
        val start = TimeSource.Monotonic.markNow()
        val term = Terminal(10, 5, 0)
        term.feed(BSU)
        assertTrue(term.syncHeld(start))
        assertTrue(term.syncHeld(start + MAX_SYNC_HOLD / 2))
        assertFalse(term.syncHeld(start + MAX_SYNC_HOLD))
        assertFalse(term.modes.synchronizedOutput)
        term.feed("$CSI?2026\$p")
        assertEquals("$CSI?2026;2\$y", term.takeReplies().decodeToString())
    }

    @Test
    fun aHoldPastTheByteCapIsReleased() {
        val now = TimeSource.Monotonic.markNow()
        val term = Terminal(10, 5, 0)
        term.feed(BSU)
        term.feed(ByteArray(MAX_SYNC_BYTES - BSU.length) { 'x'.code.toByte() })
        assertTrue(term.syncHeld(now), "exactly at the cap still holds")
        term.feed("x")
        assertFalse(term.syncHeld(now))
        assertFalse(term.modes.synchronizedOutput)
    }

    @Test
    fun aNewHoldStartsWithFreshCaps() {
        val start = TimeSource.Monotonic.markNow()
        val term = Terminal(10, 5, 0)
        term.feed(BSU)
        assertTrue(term.syncHeld(start))
        term.feed(ESU)
        assertFalse(term.syncHeld(start + MAX_SYNC_HOLD / 2))
        term.feed(BSU)
        assertTrue(term.syncHeld(start + MAX_SYNC_HOLD), "timed from its own first look")
    }

    @Test
    fun aFullResetEndsTheHold() {
        val now = TimeSource.Monotonic.markNow()
        val term = Terminal(10, 5, 0)
        term.feed(BSU)
        term.feed("${ESC}c")
        assertFalse(term.syncHeld(now))
    }
}
