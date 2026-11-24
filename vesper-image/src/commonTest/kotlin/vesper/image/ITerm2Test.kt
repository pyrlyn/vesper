package vesper.image

import vesper.png.Png
import kotlin.io.encoding.Base64
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

private const val FILE_CAP = 4096
private const val PIXEL_CAP = 1L shl 16

private val RGBA_3X2 = ByteArray(3 * 2 * RGBA) { (it * 7).toByte() }
private val PNG = Png.encode(3, 2, RGBA_3X2)

private fun b64(bytes: ByteArray) = Base64.encode(bytes)

private fun osc(text: String) = text.encodeToByteArray()

/** `OSC 1337 ; File=` with [args] and [file]. */
private fun file(
    args: String,
    file: ByteArray = PNG,
) = osc("1337;File=$args:${b64(file)}")

private fun decoder() = ITerm2Images(FILE_CAP, PIXEL_CAP)

private fun ITerm2Images.image(payload: ByteArray) = assertNotNull(accept(payload))

class ITerm2Test {
    @Test
    fun anInlineFileDecodesWithItsArguments() {
        val name = b64("cat é.png".encodeToByteArray())
        val image = decoder().image(file("name=$name;size=${PNG.size};width=10;height=20px;preserveAspectRatio=0;inline=1"))
        assertEquals(3 to 2, image.pixels.width to image.pixels.height)
        assertContentEquals(RGBA_3X2, image.pixels.rgba)
        assertEquals("cat é.png", image.name)
        assertEquals(Extent.Cells(10), image.width)
        assertEquals(Extent.Pixels(20), image.height)
        assertEquals(false, image.preserveAspectRatio)
    }

    @Test
    fun extentsAndDefaults() {
        val image = decoder().image(file("inline=1;width=50%;height=auto"))
        assertEquals(Extent.Percent(50) to Extent.Auto, image.width to image.height)
        assertEquals(true, image.preserveAspectRatio)
        assertNull(image.name)
        // Values the protocol does not define fall back to auto rather than refuse the image.
        for (bad in listOf("0", "-3", "x", "px", "12em", "99999999999", "")) {
            assertEquals(Extent.Auto, decoder().image(file("inline=1;width=$bad;height=$bad;name=!!")).width, bad)
        }
    }

    @Test
    fun aDownloadAndOtherSequencesGiveNoImage() {
        assertNull(decoder().accept(file("name=YQ==")), "inline defaults to 0, a download")
        assertNull(decoder().accept(file("inline=2")))
        assertNull(decoder().accept(osc("1337;SetMark")))
        assertNull(decoder().accept(osc("1337;CurrentDir=/tmp")))
        assertNull(decoder().accept(osc("52;c;aGk=")))
        assertNull(decoder().accept(osc("1337")))
    }

    @Test
    fun base64PaddingIsOptional() {
        val unpadded = b64(PNG).trimEnd('=')
        assertContentEquals(RGBA_3X2, decoder().image(osc("1337;File=inline=1:$unpadded")).pixels.rgba)
    }

    @Test
    fun malformedSequencesAreRefused() {
        assertFailsWith<ImageError.Malformed> { decoder().accept(osc("1337;File=inline=1")) }
        assertFailsWith<ImageError.Malformed> { decoder().accept(osc("1337;File=inline=1:not base64!")) }
        assertFailsWith<ImageError.Malformed> { decoder().accept(osc("1337;File=inline=1:QQ=A")) }
        assertFailsWith<ImageError.Decode> { decoder().accept(file("inline=1", PNG.copyOf(PNG.size - 20))) }
    }

    @Test
    fun onlyPngIsDecoded() {
        val jpeg = byteArrayOf(-1, -40, -1, -32, 0, 16)
        assertEquals("JPEG", assertFailsWith<ImageError.Unsupported> { decoder().accept(file("inline=1", jpeg)) }.format)
        val gif = "GIF89a".encodeToByteArray()
        assertEquals("GIF", assertFailsWith<ImageError.Unsupported> { decoder().accept(file("inline=1", gif)) }.format)
        assertFailsWith<ImageError.Unsupported> { decoder().accept(file("inline=1", ByteArray(0))) }
    }

    @Test
    fun capsAreCheckedBeforeTheDataIsDecoded() {
        // The declared size alone is enough to refuse, before a byte of the file is looked at.
        val e = assertFailsWith<ImageError.TooLarge> { decoder().accept(osc("1337;File=inline=1;size=${FILE_CAP + 1}:")) }
        assertEquals(FILE_CAP + 1L, e.needed)
        assertFailsWith<ImageError.TooLarge> { decoder().accept(file("inline=1", ByteArray(FILE_CAP + 3))) }
        // A small file whose pixels pass the cap: refused by the PNG header.
        val wide = Png.encode(256, 64, ByteArray(256 * 64 * RGBA))
        assertFailsWith<ImageError.TooLarge> { decoder().accept(file("inline=1", wide)) }
    }

    @Test
    fun aMultipartFileArrivesInPieces() {
        val d = decoder()
        val text = b64(PNG)
        assertNull(d.accept(osc("1337;MultipartFile=inline=1;width=4;name=${b64("a".encodeToByteArray())}")))
        // Cuts that do not fall on base64 quads, as tmux makes them.
        for (piece in text.chunked(7)) assertNull(d.accept(osc("1337;FilePart=$piece")))
        val image = d.image(osc("1337;FileEnd"))
        assertContentEquals(RGBA_3X2, image.pixels.rgba)
        assertEquals(Extent.Cells(4) to "a", image.width to image.name)
        assertNull(d.accept(osc("1337;FileEnd")), "the transfer is over")
    }

    @Test
    fun strayAndCancelledPartsAreDropped() {
        val d = decoder()
        assertNull(d.accept(osc("1337;FilePart=${b64(PNG)}")), "no transfer started")
        assertNull(d.accept(osc("1337;FileEnd")))
        d.accept(osc("1337;MultipartFile=inline=1"))
        d.accept(osc("1337;FilePart=AAAA"))
        // A whole file in the middle ends the transfer it interrupts.
        assertContentEquals(RGBA_3X2, d.image(file("inline=1")).pixels.rgba)
        assertNull(d.accept(osc("1337;FileEnd")))
        // A download over multipart gives nothing either.
        d.accept(osc("1337;MultipartFile=name=YQ=="))
        d.accept(osc("1337;FilePart=${b64(PNG)}"))
        assertNull(d.accept(osc("1337;FileEnd")))
    }

    @Test
    fun aMultipartFilePastTheCapIsDropped() {
        val d = decoder()
        assertFailsWith<ImageError.TooLarge> { d.accept(osc("1337;MultipartFile=inline=1;size=${FILE_CAP * 2}")) }
        d.accept(osc("1337;MultipartFile=inline=1"))
        val part = osc("1337;FilePart=" + "A".repeat(1024))
        assertFailsWith<ImageError.TooLarge> { repeat(8) { d.accept(part) } }
        assertNull(d.accept(osc("1337;FileEnd")), "the failed transfer was dropped")
    }

    @Test
    fun mutatedSequencesOnlyEverFailAsImageErrors() {
        val rng = Random(1337)
        val seeds =
            listOf(
                file("inline=1;width=5;height=50%"),
                osc("1337;MultipartFile=inline=1\u0007") + b64(PNG).chunked(9).joinToString("") { "1337;FilePart=$it\u0007" }.let(::osc) +
                    osc("1337;FileEnd"),
            )
        repeat(3000) {
            val input = seeds[rng.nextInt(seeds.size)].copyOf()
            repeat(rng.nextInt(1, 6)) { input[rng.nextInt(input.size)] = rng.nextInt(256).toByte() }
            fuzzInlineImages(input)
        }
        repeat(500) { fuzzInlineImages(osc("1337;File=inline=1:") + rng.nextBytes(rng.nextInt(64))) }
    }
}
