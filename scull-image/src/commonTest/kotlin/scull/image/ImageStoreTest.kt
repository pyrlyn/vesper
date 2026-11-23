package scull.image

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A [w] by [h] image, 64 bytes at the default 4 by 4. */
private fun pixels(
    w: Int = 4,
    h: Int = 4,
) = RgbaImage(w, h, ByteArray(w * h * RGBA))

/** The ids held, without touching them. */
private fun ImageStore.ids() = (1L..100L).filter { hasImage(it) }

/** -1 when image [id] is gone, else how many placements show it. */
private fun ImageStore.placementsOf(id: Long): Int = if (hasImage(id)) placements.values.count { it.imageId == id } else -1

class ImageStoreTest {
    @Test
    fun theLeastRecentlyUsedImageGoesFirst() {
        val store = ImageStore(quotaBytes = 3 * 64)
        val (a, _, c) = List(3) { store.add(pixels()).id }
        assertEquals(3 * 64L, store.usedBytes)
        store[a]
        val d = store.add(pixels()).id
        assertEquals(listOf(a, c, d), store.ids())
        // A larger image evicts as many as it needs.
        store.add(pixels(8, 4))
        assertEquals(listOf(d, 5L), store.ids())
        store.checkInvariants()
    }

    @Test
    fun shownImagesOutliveUnshownOnes() {
        val store = ImageStore(quotaBytes = 3 * 64)
        val (a, b, c) = List(3) { store.add(pixels()).id }
        assertNotNull(store.place(Placement(a, 0, 0, 2, 1)))
        store[b]
        store[c]
        val d = store.add(pixels()).id
        assertEquals(listOf(a, c, d), store.ids(), "b went though a was used before it")
        store.checkInvariants()
    }

    @Test
    fun whenEveryImageIsShownTheOldestGoesWithItsPlacements() {
        val store = ImageStore(quotaBytes = 2 * 64)
        val (a, b) = List(2) { store.add(pixels()).id }
        store.place(Placement(a, 0, 0, 1, 1))
        store.place(Placement(a, 3, 9, 1, 1))
        store.place(Placement(b, 0, 1, 1, 1))
        store.add(pixels())
        assertEquals(-1, store.placementsOf(a))
        assertEquals(1, store.placementsOf(b))
        assertEquals(listOf(b), store.placements.values.map { it.imageId })
        store.checkInvariants()
    }

    @Test
    fun anImageLargerThanTheQuotaIsRefusedAndEvictsNothing() {
        val store = ImageStore(quotaBytes = 99)
        val a = store.add(pixels()).id
        val e = assertFailsWith<ImageError.TooLarge> { store.add(pixels(5, 5)) }
        assertEquals(100L to 99L, e.needed to e.limit)
        assertEquals(listOf(a), store.ids())
    }

    @Test
    fun aMovedPlacementKeepsItsIdItsOrderAndTheRest() {
        val store = ImageStore(quotaBytes = 1L shl 20)
        val a = store.add(pixels()).id
        val first = assertNotNull(store.place(Placement(a, 1, 2, 3, 4, Crop(1, 1, 2, 2), z = -1, offsetX = 5, offsetY = 6)))
        val second = assertNotNull(store.place(Placement(a, 0, 0, 1, 1)))
        assertTrue(store.move(first, 7, 40))
        assertEquals(listOf(first, second), store.placements.keys.toList())
        val p = store.placements.getValue(first)
        assertEquals(
            listOf(7L, 40L, 3L, 4L, -1L, 5L, 6L),
            listOf(p.column, p.line, p.columns, p.rows, p.z, p.offsetX, p.offsetY).map {
                it.toLong()
            },
        )
        assertEquals(2, p.crop?.width)
        assertFalse(store.move(99, 0, 0))
        store.checkInvariants()
    }

    @Test
    fun theImageCountIsCappedToo() {
        val store = ImageStore(quotaBytes = 1L shl 20, maxImages = 2)
        repeat(5) { store.add(pixels(1, 1)) }
        assertEquals(listOf(4L, 5L), store.ids())
        assertEquals(2, store.imageCount)
    }

    @Test
    fun theOldestPlacementGoesWhenTheCapIsHit() {
        val store = ImageStore(quotaBytes = 1L shl 20, maxPlacements = 2)
        val a = store.add(pixels()).id
        val p1 = assertNotNull(store.place(Placement(a, 0, 0, 1, 1)))
        val p2 = assertNotNull(store.place(Placement(a, 1, 0, 1, 1)))
        val p3 = assertNotNull(store.place(Placement(a, 2, 0, 1, 1)))
        assertEquals(listOf(p2, p3), store.placements.keys.toList())
        assertFalse(store.unplace(p1), "the oldest placement is already gone")
        assertEquals(2, store.placementsOf(a))
        assertTrue(store.hasImage(a), "the image stays after a placement is evicted")
        assertFailsWith<IllegalArgumentException> { ImageStore(quotaBytes = 64, maxPlacements = 0) }
        store.checkInvariants()
    }

    @Test
    fun aPlacementEvictionCanLeaveItsImageUnshown() {
        val store = ImageStore(quotaBytes = 2 * 64, maxPlacements = 1)
        val (a, b) = List(2) { store.add(pixels()).id }
        assertNotNull(store.place(Placement(a, 0, 0, 1, 1)))
        assertNotNull(store.place(Placement(b, 0, 1, 1, 1)))
        assertEquals(listOf(b), store.placements.values.map { it.imageId })
        assertEquals(0, store.placementsOf(a), "a is still held, just unshown")
        store.add(pixels())
        assertEquals(-1, store.placementsOf(a), "the unshown image was evicted first")
        assertEquals(1, store.placementsOf(b))
        store.checkInvariants()
    }

    @Test
    fun placementsComeAndGoWithoutTheirImage() {
        val store = ImageStore(quotaBytes = 1L shl 20)
        val a = store.add(pixels(10, 6)).id
        assertNull(store.place(Placement(99, 0, 0, 1, 1)), "no such image")
        assertNull(store.place(Placement(a, 0, 0, 1, 1, Crop(5, 0, 6, 6))), "crop past the right edge")
        val p1 = assertNotNull(store.place(Placement(a, 0, 0, 1, 1, Crop(4, 0, 6, 6))))
        val p2 = assertNotNull(store.place(Placement(a, 0, 5, 1, 1)))
        val p3 = assertNotNull(store.place(Placement(a, 2, 5, 1, 1, z = -1)))
        assertEquals(listOf(p1, p2, p3), store.placements.keys.toList())
        assertTrue(store.unplace(p1))
        assertFalse(store.unplace(p1))
        assertEquals(2, store.removePlacements { it.line == 5L })
        assertEquals(0, store.placementsOf(a), "the image stays after its placements go")
        assertTrue(store.delete(a))
        assertFalse(store.delete(a))
        assertEquals(0L, store.usedBytes)
        assertEquals(2L, store.add(pixels()).id, "ids are never reused")
        assertFailsWith<IllegalArgumentException> { Placement(a, 0, 0, 0, 1) }
        assertFailsWith<IllegalArgumentException> { Crop(0, 0, 0, 1) }
        store.checkInvariants()
    }

    @Test
    fun theBookkeepingHoldsUnderRandomOperations() {
        val rng = Random(19)
        repeat(2000) { fuzzImageStore(rng.nextBytes(rng.nextInt(1, 200))) }
    }
}
