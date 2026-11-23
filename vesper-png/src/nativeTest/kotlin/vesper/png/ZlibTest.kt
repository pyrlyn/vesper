package vesper.png

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ZlibTest {
    private val text = ByteArray(5000) { (it % 251).toByte() }

    @Test
    fun inflateUndoesDeflate() {
        assertContentEquals(text, Zlib.inflate(Zlib.deflate(text), text.size))
        assertContentEquals(ByteArray(0), Zlib.inflate(Zlib.deflate(ByteArray(0)), 0))
    }

    @Test
    fun theCapIsTheLastByteAllowed() {
        val data = Zlib.deflate(text)
        assertEquals(text.size, Zlib.inflate(data, text.size).size)
        assertEquals(text.size - 1, assertFailsWith<ZlibTooLargeException> { Zlib.inflate(data, text.size - 1) }.limit)
    }

    @Test
    fun aBombStopsAtTheCap() {
        val bomb = Zlib.deflate(ByteArray(64 shl 20))
        assertFailsWith<ZlibTooLargeException> { Zlib.inflate(bomb, 1 shl 16) }
    }

    @Test
    fun brokenStreamsFail() {
        val data = Zlib.deflate(text)
        assertFailsWith<ZlibException> { Zlib.inflate(data.copyOf(data.size / 2), text.size) }
        assertFailsWith<ZlibException> { Zlib.inflate(ByteArray(0), text.size) }
        assertFailsWith<ZlibException> { Zlib.inflate(byteArrayOf(1, 2, 3, 4), text.size) }
    }

    @Test
    fun bytesAfterTheStreamAreIgnored() {
        assertContentEquals(text, Zlib.inflate(Zlib.deflate(text) + byteArrayOf(9, 9), text.size))
    }
}
