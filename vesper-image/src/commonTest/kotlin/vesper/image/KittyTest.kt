package vesper.image

import vesper.harness.fuzzCases
import vesper.png.Png
import vesper.png.Zlib
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val ESC = "\u001b"

private fun b64(bytes: ByteArray) = Base64.encode(bytes)

private fun rgba(
    width: Int,
    height: Int,
) = ByteArray(width * height * RGBA) { (it * 7).toByte() }

private fun ok(ids: String) = "${ESC}_G$ids;OK$ESC\\"

/** Cells of 10 by 20 pixels on an 80 by 24 screen whose top row is line 90; the cursor at [column], [line]. */
private fun at(
    column: Int = 2,
    line: Long = 100,
) = KittyContext(column, line, cellWidth = 10, cellHeight = 20, top = 90, columns = 80, rows = 24)

private fun KittyGraphics.send(
    command: String,
    context: KittyContext = at(),
) = accept(command.encodeToByteArray(), context)

private fun assertRefused(
    ids: String,
    code: String,
    outcome: KittyOutcome,
) {
    val reply = assertNotNull(outcome.reply)
    assertTrue(reply.startsWith("${ESC}_G$ids;$code:") && reply.endsWith("$ESC\\"), reply)
}

/** One cell's worth of RGBA: 10 by 20 pixels. */
private val CELL = "s=10,v=20;" + b64(rgba(10, 20))

class KittyTest {
    private val store = ImageStore(quotaBytes = 1L shl 20)
    private val kitty = KittyGraphics(store, maxFileBytes = 1 shl 16, maxPixelBytes = 1L shl 18)

    private fun shown() =
        store.placements.values
            .map { it.column to it.line }
            .toSet()

    @Test
    fun transmitStoresRgbaAndReplies() {
        val pixels = rgba(2, 3)
        assertEquals(ok("i=7"), kitty.send("Ga=t,f=32,s=2,v=3,i=7;" + b64(pixels)).reply)
        val image = assertNotNull(store[1])
        assertEquals(2, image.pixels.width)
        assertContentEquals(pixels, image.pixels.rgba)
        assertTrue(store.placements.isEmpty())
    }

    @Test
    fun rgbBecomesOpaqueRgba() {
        kitty.send("Gf=24,s=1,v=2,i=1;" + b64(byteArrayOf(1, 2, 3, 4, 5, 6)))
        assertContentEquals(byteArrayOf(1, 2, 3, -1, 4, 5, 6, -1), assertNotNull(store[1]).pixels.rgba)
    }

    @Test
    fun pngIsDecoded() {
        val pixels = rgba(3, 2)
        assertEquals(ok("i=1"), kitty.send("Gf=100,i=1;" + b64(Png.encode(3, 2, pixels))).reply)
        assertContentEquals(pixels, assertNotNull(store[1]).pixels.rgba)
    }

    @Test
    fun deflatedDataIsInflatedWithinItsSize() {
        val pixels = rgba(4, 4)
        assertEquals(ok("i=1"), kitty.send("Go=z,s=4,v=4,i=1;" + b64(Zlib.deflate(pixels))).reply)
        assertContentEquals(pixels, assertNotNull(store[1]).pixels.rgba)
        assertRefused("i=2", "ENODATA", kitty.send("Go=z,s=4,v=4,i=2;" + b64(Zlib.deflate(ByteArray(1 shl 20)))))
        assertRefused("i=3", "EINVAL", kitty.send("Go=z,s=4,v=4,i=3;" + b64(byteArrayOf(1, 2, 3))))
        assertRefused("i=4", "EINVAL", kitty.send("Go=x,s=4,v=4,i=4;" + b64(pixels)))
        assertEquals(1, store.imageCount)
    }

    @Test
    fun chunksJoinIntoOneCommandRunAtTheLastChunk() {
        val data = b64(rgba(4, 4))
        val chunks = data.chunked(16)
        assertNull(kitty.send("Ga=T,s=4,v=4,i=3,m=1;${chunks.first()}").reply)
        for (chunk in chunks.drop(1).dropLast(1)) assertNull(kitty.send("Gm=1;$chunk").reply)
        assertEquals(0, store.imageCount)
        val last = kitty.send("Gm=0;${chunks.last()}", at(column = 7, line = 95))
        assertEquals(ok("i=3"), last.reply)
        assertEquals(1 to 1, last.cursorColumns to last.cursorRows)
        assertEquals(setOf(7 to 95L), shown())
    }

    @Test
    fun aLaterChunkMayChangeTheQuietLevel() {
        assertNull(kitty.send("Gs=1,v=1,i=1,m=1;AAAA").reply)
        assertNull(kitty.send("Gm=0,q=1;AA").reply)
        assertEquals(1, store.imageCount)
    }

    @Test
    fun anUploadPastTheCapFailsOnceAndDropsItsChunks() {
        val small = KittyGraphics(store, maxFileBytes = 12, maxPixelBytes = 1L shl 18)
        assertNull(small.send("Gs=2,v=2,i=1,m=1;AAAAAAAA").reply)
        assertRefused("i=1", "EFBIG", small.send("Gm=1;AAAAAAAAAAAA"))
        assertNull(small.send("Gm=1;AAAA").reply)
        assertNull(small.send("Gm=0;AAAA").reply)
        assertEquals(ok("i=2"), small.send("Gs=1,v=1,i=2;AAAAAA").reply)
        assertEquals(1, store.imageCount)
    }

    @Test
    fun aDeleteAbortsTheUpload() {
        assertNull(kitty.send("Gs=4,v=4,i=4,m=1;AAAA").reply)
        assertNull(kitty.send("Ga=d,d=A").reply)
        // The upload is gone, so this is a command of its own, without the size it needs.
        assertNull(kitty.send("Gm=0;AAAA").reply)
        assertEquals(0, store.imageCount)
    }

    @Test
    fun quietLevelsDropOkThenErrors() {
        assertNull(kitty.send("Gq=1,i=1,$CELL").reply)
        assertRefused("i=9", "ENOENT", kitty.send("Ga=p,q=1,i=9"))
        assertNull(kitty.send("Ga=p,q=2,i=9").reply)
        assertEquals(1, store.imageCount)
    }

    @Test
    fun commandsWithoutAnIdGetNoReply() {
        assertNull(kitty.send("Ga=T,$CELL").reply)
        assertNull(kitty.send("Ga=p,x=99").reply)
        assertEquals(1, store.placements.size)
    }

    @Test
    fun imageNumbersGetIdsFromTheTopAndNameTheNewestImage() {
        assertEquals(ok("i=4294967295,I=5"), kitty.send("GI=5,$CELL").reply)
        assertEquals(ok("i=4294967294,I=5"), kitty.send("GI=5,$CELL").reply)
        assertEquals(ok("i=4294967294,I=5"), kitty.send("Ga=p,I=5").reply)
        assertEquals(
            setOf(2L),
            store.placements.values
                .map { it.imageId }
                .toSet(),
        )
        assertRefused("I=6", "ENOENT", kitty.send("Ga=p,I=6"))
        // A failed upload's reply names only its own ids, not the id the last command was given.
        val small = KittyGraphics(store, maxFileBytes = 4, maxPixelBytes = 1L shl 18)
        small.send("GI=8,s=1,v=1;AAAAAA")
        assertRefused("i=1", "EFBIG", small.send("Gi=1,s=1,v=1,m=1;AAAAAAAAAAAA"))
        assertRefused("i=1,I=2", "EINVAL", kitty.send("Gi=1,I=2,$CELL"))
    }

    @Test
    fun putPlacesAtTheCursorAndReplacesTheSamePlacementId() {
        kitty.send("Gi=1,$CELL")
        val put = kitty.send("Ga=p,i=1,p=2", at(4, 96))
        assertEquals(ok("i=1,p=2"), put.reply)
        assertEquals(1 to 1, put.cursorColumns to put.cursorRows)
        kitty.send("Ga=p,i=1,p=2", at(5, 97))
        assertEquals(setOf(5 to 97L), shown())
        kitty.send("Ga=p,i=1,p=3", at(6, 98))
        kitty.send("Ga=p,i=1", at(6, 99))
        kitty.send("Ga=p,i=1", at(6, 99))
        assertEquals(4, store.placements.size)
        assertRefused("i=9", "ENOENT", kitty.send("Ga=p,i=9"))
        assertRefused("i=1", "EINVAL", kitty.send("Ga=p,i=1,x=10"))
        assertRefused("i=1", "EINVAL", kitty.send("Ga=p,i=1,c=x"))
    }

    @Test
    fun placementSizeCropOffsetAndStacking() {
        kitty.send("Gi=1,s=30,v=50;" + b64(rgba(30, 50)))

        fun put(keys: String): Placement {
            store.removePlacements { true }
            kitty.send("Ga=p,i=1,$keys")
            return store.placements.values.single()
        }
        put("").let { assertEquals(3 to 3, it.columns to it.rows) }
        put("c=6").let { assertEquals(6 to 5, it.columns to it.rows) }
        put("r=2").let { assertEquals(3 to 2, it.columns to it.rows) }
        put("c=4,r=9").let { assertEquals(4 to 9, it.columns to it.rows) }
        put("x=10,w=10,X=3,Y=4,z=-5").let {
            val crop = assertNotNull(it.crop)
            assertEquals(listOf(10, 0, 10, 50), listOf(crop.x, crop.y, crop.width, crop.height))
            assertEquals(listOf(3, 4, -5), listOf(it.offsetX, it.offsetY, it.z))
            assertEquals(2 to 3, it.columns to it.rows)
        }
        put("y=40,h=99").let { assertEquals(10, assertNotNull(it.crop).height) }
        put("X=99").let { assertEquals(9, it.offsetX) }
    }

    @Test
    fun transmitAndPutMovesTheCursorUnlessToldNotTo() {
        val moved = kitty.send("Ga=T,i=1,s=30,v=50;" + b64(rgba(30, 50)))
        assertEquals(3 to 3, moved.cursorColumns to moved.cursorRows)
        val still = kitty.send("Ga=T,i=2,C=1,$CELL")
        assertEquals(ok("i=2"), still.reply)
        assertEquals(0 to 0, still.cursorColumns to still.cursorRows)
    }

    @Test
    fun sendingAnIdAgainReplacesTheImageAndItsPlacements() {
        kitty.send("Ga=T,i=1,p=1,$CELL")
        kitty.send("Gi=1,s=2,v=2;" + b64(rgba(2, 2)))
        assertEquals(1, store.imageCount)
        assertTrue(store.placements.isEmpty())
        assertEquals(2, assertNotNull(store[2]).pixels.width)
    }

    @Test
    fun queryChecksWithoutStoring() {
        assertEquals(ok("i=31"), kitty.send("Ga=q,i=31,s=1,v=1;AAAAAA").reply)
        assertRefused("i=31", "ENODATA", kitty.send("Ga=q,i=31,s=2,v=1;AAAAAA"))
        assertEquals(0, store.imageCount)
    }

    @Test
    fun refusals() {
        assertRefused("i=1", "EBADF", kitty.send("Gt=f,i=1;" + b64("/etc/passwd".encodeToByteArray())))
        assertRefused("i=1", "EBADF", kitty.send("Gt=s,i=1;" + b64("shm".encodeToByteArray())))
        assertRefused("i=1", "ENODATA", kitty.send("Gs=2,v=2,i=1;AAAA"))
        assertRefused("i=1", "EINVAL", kitty.send("Gi=1;AAAA"))
        assertRefused("i=1", "EFBIG", kitty.send("Gs=4294967295,v=4294967295,i=1;AAAA"))
        assertRefused("i=1", "EFBIG", kitty.send("Gf=100,i=1;" + b64(Png.encode(300, 300, ByteArray(300 * 300 * RGBA)))))
        assertRefused("i=1", "EINVAL", kitty.send("Gf=100,i=1;" + b64(byteArrayOf(1, 2, 3))))
        assertRefused("i=1", "EINVAL", kitty.send("Gf=8,i=1,$CELL"))
        assertRefused("i=1", "EINVAL", kitty.send("Gs=1,v=1,i=1;!!!!"))
        assertRefused("i=1", "EINVAL", kitty.send("Ga=f,i=1"))
        assertRefused("i=1", "EINVAL", kitty.send("Ga=T,U=1,i=1,$CELL"))
        assertRefused("i=1", "EINVAL", kitty.send("Ga=T,P=2,Q=1,i=1,$CELL"))
        assertRefused("i=1", "EINVAL", kitty.send("Ga=w,i=1"))
        assertRefused("i=1", "EINVAL", kitty.send("Gs=-1,v=1,i=1;AAAAAA"))
        assertRefused("i=1", "EINVAL", kitty.send("Gs=4294967296,v=1,i=1;AAAAAA"))
        assertEquals(0, store.imageCount)
        val tiny = KittyGraphics(ImageStore(quotaBytes = 16), maxFileBytes = 1 shl 16, maxPixelBytes = 1L shl 18)
        assertRefused("i=1", "ENOSPC", tiny.send("Gi=1,$CELL"))
    }

    @Test
    fun otherApcsAreIgnored() {
        assertNull(kitty.send("Pq").reply)
        assertNull(kitty.accept(ByteArray(0), at()).reply)
    }

    /** Image 1 shown at (0, 95) as placement 1, at (5, 100) with z=1 as 2, off screen at (5, 50) as 3. */
    private fun threePlacements() {
        kitty.send("Gi=1,$CELL")
        kitty.send("Ga=p,i=1,p=1", at(0, 95))
        kitty.send("Ga=p,i=1,p=2,z=1", at(5, 100))
        kitty.send("Ga=p,i=1,p=3", at(5, 50))
    }

    private fun deleting(
        command: String,
        context: KittyContext = at(),
    ): Set<Pair<Int, Long>> {
        store.removePlacements { true }
        threePlacements()
        assertNull(kitty.send(command, context).reply, "deletes never reply")
        return shown()
    }

    @Test
    fun deleteByPosition() {
        assertEquals(setOf(5 to 50L), deleting("Ga=d"))
        assertEquals(setOf(5 to 50L), deleting("Ga=d,d=a"))
        assertEquals(setOf(0 to 95L, 5 to 50L), deleting("Ga=d,d=c", at(5, 100)))
        assertEquals(setOf(0 to 95L, 5 to 50L), deleting("Ga=d,d=p,x=6,y=11"))
        assertEquals(setOf(0 to 95L, 5 to 100L, 5 to 50L), deleting("Ga=d,d=q,x=6,y=11,z=0"))
        assertEquals(setOf(0 to 95L, 5 to 50L), deleting("Ga=d,d=q,x=6,y=11,z=1"))
        assertEquals(setOf(0 to 95L), deleting("Ga=d,d=x,x=6"))
        assertEquals(setOf(5 to 100L, 5 to 50L), deleting("Ga=d,d=y,y=6"))
        assertEquals(setOf(0 to 95L, 5 to 50L), deleting("Ga=d,d=z,z=1"))
        assertEquals(3, deleting("Ga=d,d=x,x=0").size, "a bad delete does nothing")
        assertEquals(1, store.imageCount, "lower case keeps the data")
    }

    @Test
    fun upperCaseDeletesFreeImagesNothingShows() {
        assertEquals(setOf(5 to 50L), deleting("Ga=d,d=A"))
        assertEquals(1, store.imageCount, "the off-screen placement still shows it")
        assertEquals(setOf(0 to 95L), deleting("Ga=d,d=X,x=6"))
        assertEquals(setOf(5 to 100L, 5 to 50L), deleting("Ga=d,d=Y,y=6"))
        assertEquals(1, store.imageCount)
        assertEquals(emptySet(), deleting("Ga=d,d=I,i=1"))
        assertEquals(0, store.imageCount)
    }

    @Test
    fun deleteByIdPlacementIdNumberAndRange() {
        assertEquals(setOf(0 to 95L, 5 to 50L), deleting("Ga=d,d=i,i=1,p=2"))
        assertEquals(emptySet(), deleting("Ga=d,d=i,i=1"))
        assertEquals(1, store.imageCount)
        kitty.send("Gi=2,$CELL")
        kitty.send("Gi=3,$CELL")
        kitty.send("GI=7,$CELL")
        kitty.send("Ga=p,I=7")
        kitty.send("Ga=d,d=N,I=7")
        assertEquals(3, store.imageCount)
        kitty.send("Ga=d,d=R,x=1,y=2")
        assertEquals(1, store.imageCount)
        // Only image 3 is left, so deleting it empties the store.
        kitty.send("Ga=d,d=I,i=3")
        assertEquals(0, store.imageCount)
    }

    @Test
    fun mapsStayBoundedWhileTheStoreEvicts() {
        val small = ImageStore(quotaBytes = 4L * 10 * 20 * RGBA, maxImages = 4)
        val k = KittyGraphics(small, maxFileBytes = 1 shl 16, maxPixelBytes = 1L shl 18)
        for (i in 1..500) {
            k.send("Ga=T,i=$i,p=$i,$CELL")
            k.checkInvariants()
            small.checkInvariants()
        }
        assertEquals(4, small.imageCount)
    }

    @Test
    fun mutatedCommandsKeepTheBookkeepingSound() {
        val png = b64(Png.encode(3, 2, rgba(3, 2)))
        val data = b64(rgba(4, 4))
        val seeds =
            listOf(
                "Ga=T,f=32,s=2,v=2,i=1,p=1;${b64(rgba(2, 2))}${ESC}Ga=p,i=1,p=2,c=3${ESC}Ga=d,d=I,i=1",
                "Ga=T,s=4,v=4,i=3,m=1;${data.take(32)}${ESC}Gm=1;${data.drop(32).take(32)}${ESC}Gm=0;${data.drop(64)}",
                "Ga=t,f=100,I=3;$png${ESC}Ga=p,I=3,x=1,w=1,X=2${ESC}Ga=d,d=A${ESC}Ga=d,d=p,x=4,y=3",
                "Ga=T,o=z,f=24,s=3,v=1,i=2;${b64(Zlib.deflate(ByteArray(9)))}${ESC}Ga=q,i=4,s=1,v=1;AAAAAA${ESC}Ga=d,d=R,x=1,y=9",
            ).map { it.encodeToByteArray() }
        fuzzCases(1919, 3000) { rng ->
            val input = seeds[rng.nextInt(seeds.size)].copyOf()
            repeat(rng.nextInt(1, 6)) { input[rng.nextInt(input.size)] = rng.nextInt(256).toByte() }
            fuzzKitty(input)
        }
        fuzzCases(1920, 500) { fuzzKitty("Ga=T,i=1,".encodeToByteArray() + it.nextBytes(it.nextInt(64))) }
    }
}
