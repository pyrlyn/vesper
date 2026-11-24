package vesper.macos

import kotlinx.cinterop.DoubleVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.set
import kotlinx.cinterop.value
import platform.CoreFoundation.CFAttributedStringCreate
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFDictionarySetValue
import platform.CoreFoundation.CFNumberCreate
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringCreateWithCString
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFNumberCGFloatType
import platform.CoreFoundation.kCFStringEncodingUTF8
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.CoreGraphics.CGContextRef
import platform.CoreGraphics.CGContextSetTextPosition
import platform.CoreGraphics.CGGlyphVar
import platform.CoreText.CTFontCreateCopyWithSymbolicTraits
import platform.CoreText.CTFontCreateUIFontForLanguage
import platform.CoreText.CTFontGetAdvancesForGlyphs
import platform.CoreText.CTFontGetAscent
import platform.CoreText.CTFontGetDescent
import platform.CoreText.CTFontGetGlyphsForCharacters
import platform.CoreText.CTFontGetLeading
import platform.CoreText.CTFontRef
import platform.CoreText.CTLineCreateWithAttributedString
import platform.CoreText.CTLineDraw
import platform.CoreText.kCTFontAttributeName
import platform.CoreText.kCTFontOrientationHorizontal
import platform.CoreText.kCTFontTraitBold
import platform.CoreText.kCTFontTraitItalic
import platform.CoreText.kCTFontUIFontUserFixedPitch
import platform.CoreText.kCTForegroundColorFromContextAttributeName
import platform.CoreText.kCTKernAttributeName
import platform.darwin.UniCharVar
import kotlin.math.ceil

/** Points; T20 makes it a setting. */
private const val FONT_SIZE = 13.0

// What a fixed-pitch face's advance is, as a share of its size, should it report none.
private const val FALLBACK_ADVANCE = 0.6

private const val BOLD = 1
private const val ITALIC = 2

/**
 * The user's fixed-pitch font and its bold and italic faces, and one line of text drawn with
 * CoreText. Text takes its colour from the context's fill colour, so one attribute set per face
 * serves every colour. CoreText falls back to other fonts for characters this one lacks, to the
 * bundled Nerd Font symbols ([bundledSymbols]) before the system's.
 */
internal class TextStyle {
    /** Regular, bold, italic and bold italic, indexed by [face]. */
    val faces: List<CTFontRef?>
    private val attributes: List<CFDictionaryRef?>

    /** The cell, from the advance of `M` and the line height. */
    val cell: CellSize

    /** Distance from a cell's top to the baseline. */
    val ascent: Double

    init {
        val regular = CTFontCreateUIFontForLanguage(kCTFontUIFontUserFixedPitch, FONT_SIZE, null)
        // A face the font lacks comes back null; the regular face stands in for it.
        faces =
            (0..(BOLD or ITALIC)).map { traits ->
                val mask = traitMask(traits)
                val face = if (traits == 0) regular else CTFontCreateCopyWithSymbolicTraits(regular, 0.0, null, mask, mask) ?: regular
                withFallback(face, bundledSymbols)
            }
        ascent = CTFontGetAscent(regular)
        val height = ceil(ascent + CTFontGetDescent(regular) + CTFontGetLeading(regular))
        // Whole points keep the grid crisp; each face is then kerned up to the cell, or a run of
        // a hundred spaces would end visibly short of the column after it.
        cell = CellSize(ceil(advanceOfM(regular)), height)
        attributes = faces.map { face -> attributesFor(face, cell.width - advanceOfM(face)) }
    }

    /** Draws [text] with its baseline starting at ([x], [baseline]), in the context's fill colour. */
    fun draw(
        context: CGContextRef?,
        text: String,
        x: Double,
        baseline: Double,
        bold: Boolean,
        italic: Boolean,
    ) {
        val string = CFStringCreateWithCString(null, text, kCFStringEncodingUTF8) ?: return
        val attributed = CFAttributedStringCreate(null, string, attributes[face(bold, italic)])
        val line = CTLineCreateWithAttributedString(attributed)
        CGContextSetTextPosition(context, x, baseline)
        CTLineDraw(line, context)
        CFRelease(line)
        CFRelease(attributed)
        CFRelease(string)
    }

    private fun traitMask(traits: Int): UInt =
        (if (traits and BOLD != 0) kCTFontTraitBold else 0u) or (if (traits and ITALIC != 0) kCTFontTraitItalic else 0u)

    private fun attributesFor(
        face: CTFontRef?,
        kern: Double,
    ): CFDictionaryRef? =
        memScoped {
            val dictionary = CFDictionaryCreateMutable(null, 3, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr)
            CFDictionarySetValue(dictionary, kCTFontAttributeName, face)
            CFDictionarySetValue(dictionary, kCTForegroundColorFromContextAttributeName, kCFBooleanTrue)
            val extra = alloc<DoubleVar>().apply { value = kern }
            val number = CFNumberCreate(null, kCFNumberCGFloatType, extra.ptr)
            CFDictionarySetValue(dictionary, kCTKernAttributeName, number)
            CFRelease(number)
            dictionary
        }

    private fun advanceOfM(font: CTFontRef?): Double =
        memScoped {
            val chars = allocArray<UniCharVar>(1)
            chars[0] = 'M'.code.toUShort()
            val glyph = alloc<CGGlyphVar>()
            CTFontGetGlyphsForCharacters(font, chars, glyph.ptr, 1)
            CTFontGetAdvancesForGlyphs(font, kCTFontOrientationHorizontal, glyph.ptr, null, 1).takeIf { it > 0 }
                ?: FONT_SIZE * FALLBACK_ADVANCE
        }
}

/** The index in [TextStyle.faces] of a style's face. */
internal fun face(
    bold: Boolean,
    italic: Boolean,
): Int = (if (bold) BOLD else 0) or (if (italic) ITALIC else 0)

/** The text of one code point, for a cell drawn on its own. */
internal fun codePointText(codePoint: Int): String =
    if (codePoint < 0x10000) {
        codePoint.toChar().toString()
    } else {
        val offset = codePoint - 0x10000
        charArrayOf((0xD800 + (offset shr 10)).toChar(), (0xDC00 + (offset and 0x3FF)).toChar()).concatToString()
    }
