package scull.pty

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ByteRingTest {
    @Test
    fun bytesComeOutInOrderAcrossTheWrap() {
        val ring = ByteRing(8)
        ring.push(byteArrayOf(1, 2, 3, 4, 5, 6), 0, 6)
        ring.drop(4)
        // Six more: two at the end of the array, four from its start.
        ring.push(byteArrayOf(0, 7, 8, 9, 10, 11, 12), 1, 6)
        assertEquals(8, ring.size)
        assertEquals(0, ring.room)
        assertContentEquals(byteArrayOf(5, 6, 7, 8, 9), ring.peek(5))
        ring.drop(5)
        assertContentEquals(byteArrayOf(10, 11, 12), ring.peek(100))
    }

    @Test
    fun pushingPastTheRoomFails() {
        val ring = ByteRing(4)
        ring.push(ByteArray(3), 0, 3)
        assertFailsWith<IllegalArgumentException> { ring.push(ByteArray(2), 0, 2) }
    }

    @Test
    fun clearEmptiesTheRing() {
        val ring = ByteRing(4)
        ring.push(byteArrayOf(1, 2), 0, 2)
        ring.clear()
        assertEquals(0, ring.size)
        assertEquals(4, ring.room)
        assertContentEquals(byteArrayOf(), ring.peek(4))
    }
}
