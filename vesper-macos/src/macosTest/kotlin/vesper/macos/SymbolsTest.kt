package vesper.macos

import platform.CoreText.CTFontCopyPostScriptName
import platform.CoreText.CTFontCreateWithFontDescriptor
import platform.CoreText.CTFontRef
import platform.Foundation.CFBridgingRelease
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private fun postScriptName(font: CTFontRef?): String? = CFBridgingRelease(CTFontCopyPostScriptName(font)) as? String

class SymbolsTest {
    private val text = TextStyle()
    private val shaper = RunShaper(text)

    private fun fontOf(text: String): String? = postScriptName(shaper.registry.font(keyFont(shaper.shape(text, 0, 1).single().key)))

    @Test
    fun theBinaryCarriesSymbolsNerdFontMono() {
        val descriptor = assertNotNull(bundledSymbols, "the link step embeds the font")
        assertEquals("SymbolsNFM", postScriptName(CTFontCreateWithFontDescriptor(descriptor, 13.0, null)))
    }

    @Test
    fun iconsComeFromTheBundledFontInEveryFace() {
        // nf-pl-branch, nf-fa-folder_open, nf-dev-apple, and nf-md-git past the Basic Multilingual Plane.
        for (icon in listOf("", "", "", codePointText(0xF02A2))) {
            assertEquals("SymbolsNFM", fontOf(icon), "U+${icon.codePointAt(0).toString(16)}")
            val glyph = shaper.shape(icon, face(bold = true, italic = true), 1).single()
            assertEquals("SymbolsNFM", postScriptName(shaper.registry.font(keyFont(glyph.key))), "bold italic")
            val bitmap = assertNotNull(Bitmap.glyph(keyGlyph(glyph.key), shaper.registry.font(keyFont(glyph.key)), 2.0))
            assertFalse(bitmap.isColor)
        }
    }

    @Test
    fun lettersEmojiAndSeparatorsAreLeftAlone() {
        assertNotEquals("SymbolsNFM", fontOf("a"))
        // Emoji keep their colour font, even those the symbols font also draws.
        for (emoji in listOf("😀", "⚡", "♥️")) {
            val glyph = shaper.shape(emoji, 0, 2).first()
            assertNotEquals("SymbolsNFM", postScriptName(shaper.registry.font(keyFont(glyph.key))), emoji)
        }
        // Powerline separators stay sprites, edge to edge with the cells beside them.
        assertTrue(isSpriteKey(shaper.shape("", 0, 1).single().key))
    }
}

private fun String.codePointAt(index: Int): Int =
    if (this[index].isHighSurrogate()) {
        0x10000 + ((this[index].code - 0xD800) shl 10) + (this[index + 1].code - 0xDC00)
    } else {
        this[index].code
    }
