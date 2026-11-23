package vesper.term

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The caps on what the PTY can make the core hold: replies and events. */
class QueuesTest {
    @Test
    fun theReplyQueueNeverPassesItsCapAndKeepsRepliesWhole() {
        val r = Replies()
        repeat(MAX_REPLY_BYTES) { r.push(STATUS_OK) }
        val text = r.take().decodeToString()
        assertTrue(text.length <= MAX_REPLY_BYTES)
        assertEquals(STATUS_OK.repeat(text.length / STATUS_OK.length), text)
    }

    @Test
    fun takingTheRepliesEmptiesTheQueue() {
        val r = Replies()
        r.push(STATUS_OK)
        assertEquals(STATUS_OK, r.take().decodeToString())
        assertEquals(0, r.take().size)
    }

    @Test
    fun aReplyTooBigForTheRoomLeftIsDroppedWhole() {
        val r = Replies()
        r.push(ByteArray(MAX_REPLY_BYTES - 2))
        r.push(STATUS_OK)
        assertEquals(MAX_REPLY_BYTES - 2, r.take().size)
    }

    @Test
    fun aFullEventQueueDropsTheNewestAndStillRings() {
        val queue = EventQueue()
        repeat(MAX_EVENTS) { assertTrue(queue.push(Event.Title(TitleTarget.Window, "t$it"))) }
        assertFalse(queue.push(Event.Title(TitleTarget.Window, "nope")))
        queue.ring()
        assertTrue(queue.takeBell())
        assertFalse(queue.takeBell())
        assertEquals(MAX_EVENTS, generateSequence(queue::poll).count())
        queue.ring()
        queue.ring()
        assertEquals(Event.Bell, queue.poll())
        assertNull(queue.poll())
    }

    @Test
    fun aPolledBellIsNotTakenAgain() {
        val queue = EventQueue()
        queue.ring()
        assertEquals(Event.Bell, queue.poll())
        assertFalse(queue.takeBell())
        queue.ring()
        assertTrue(queue.takeBell())
        assertNull(queue.poll())
    }
}
