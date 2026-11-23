package scull.ffi

import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlin.io.encoding.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val ESC = "\u001b"

/** A kitty command transmitting and showing 2 x 2 RGBA pixels, each byte [fill], with [keys] added. */
private fun kitty(
    keys: String,
    fill: Byte,
) = "${ESC}_Ga=T,f=32,s=2,v=2,q=2,$keys;${Base64.encode(ByteArray(16) { fill })}$ESC\\"

class ImageExportsTest {
    private val term: COpaquePointer = newTerm(10, 4)
    private val frame: COpaquePointer = ttFrameNew()!!

    @AfterTest
    fun free() {
        ttFrameFree(frame)
        ttTermFree(term)
    }

    @Test
    fun aPlacementCarriesItsCellsItsSourceAndPixelsReadInPlace() {
        update(frame, term)
        term.feed("$ESC[2;3H${kitty("i=1,c=2,r=1,z=-3,X=1,Y=2", 7)}")
        val view = update(frame, term)
        val p = view.placements.single()
        assertEquals(listOf(1, 2, 2, 1), p.cells)
        assertEquals(listOf(1, 2, 0, 0, 2, 2), p.pixelRect)
        assertEquals(-3, p.z)
        assertTrue(1 in view.dirty, "the row under the new image is repainted")
        val pixels = assertNotNull(p.pixels)
        assertEquals(Triple(2, 2, 8), pixels.size)
        assertEquals(List<Byte>(16) { 7 }, pixels.bytes)
    }

    @Test
    fun anImageKeepsItsPointerAndGenerationUntilItsPixelsAreReplaced() {
        term.feed(kitty("i=1,C=1", 1))
        val first = update(frame, term).placements.single()
        val again = update(frame, term).placements.single()
        assertEquals(first.image to first.generation, again.image to again.generation)
        term.feed(kitty("i=1,C=1", 2))
        val replaced = update(frame, term).placements.single()
        assertNotEquals(first.generation, replaced.generation)
        assertEquals(List<Byte>(16) { 2 }, assertNotNull(replaced.pixels).bytes)
        term.feed("${ESC}_Ga=d,d=A$ESC\\")
        val gone = update(frame, term)
        assertTrue(gone.placements.isEmpty())
        assertEquals(listOf(0), gone.dirty, "the row the image left")
    }

    @Test
    fun noImageAnswersNullAndZeroes() =
        memScoped {
            val w = alloc<UIntVar> { value = 9u }
            val h = alloc<UIntVar> { value = 9u }
            val stride = alloc<ULongVar> { value = 9u }
            assertNull(ttImagePixels(null, w.ptr, h.ptr, stride.ptr))
            assertEquals(listOf(0uL, 0uL, 0uL), listOf(w.value.toULong(), h.value.toULong(), stride.value))
            assertNull(ttImagePixels(null, null, null, null))
        }

    @Test
    fun theResizePixelSizeSetsTheCellImagesAreMeasuredWith() {
        // 100 x 80 pixels over 10 x 4 cells: 10 x 20 a cell, so a 2 x 2 image fills one.
        ttTermResize(term, 10u, 4u, 100u, 80u)
        term.feed("${ESC}Pq#0;2;100;0;0#0~~$ESC\\")
        val sixel = update(frame, term).placements.single()
        assertEquals(listOf(0, 0, 1, 1), sixel.cells)
        assertEquals(Triple(10, 20, 40), assertNotNull(sixel.pixels).size, "the sixel was padded to the cell")
        // An unknown pixel size keeps the cell.
        ttTermResize(term, 10u, 4u, 0u, 0u)
        term.feed("$ESC[2J$ESC[H${ESC}Pq#0;2;100;0;0#0~~$ESC\\")
        assertEquals(Triple(10, 20, 40), assertNotNull(update(frame, term).placements.single().pixels).size)
    }
}
