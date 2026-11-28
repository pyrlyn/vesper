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
import platform.CoreText.CTFontCopyFamilyName
import platform.CoreText.CTFontCreateCopyWithSymbolicTraits
import platform.CoreText.CTFontCreateUIFontForLanguage
import platform.CoreText.CTFontCreateWithName
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
import platform.Foundation.CFBridgingRelease
import platform.darwin.UniCharVar
import vesper.config.Font
import vesper.config.Settings
import vesper.host.CellSize
import vesper.host.FACES
import vesper.host.face
import vesper.host.isBoldFace
import vesper.host.isItalicFace
import kotlin.math.ceil

// What a fixed-pitch face's advance is, as a share of its size, should it report none.
private const val FALLBACK_ADVANCE = 0.6

/**
 * The configured [font] (the user's fixed-pitch font for an empty family or one this Mac lacks) and
 * its bold and italic faces, and one line of text drawn with CoreText. Text takes its colour from the
 * context's fill colour, so one attribute set per face serves every colour. CoreText falls back to
 * other fonts for characters this one lacks.
 */
internal class TextStyle(
    val font: Font = Settings.DEFAULT.font,
) {
    /** Regular, bold, italic and bold italic, indexed by [face]. */
    val faces: List<CTFontRef?>
    private val attributes: List<CFDictionaryRef?>

    /** The cell, from the advance of `M` and the line height. */
    val cell: CellSize

    /** Distance from a cell's top to the baseline. */
    val ascent: Double

    init {
        val regular = named(font) ?: CTFontCreateUIFontForLanguage(kCTFontUIFontUserFixedPitch, font.size, null)
        // A face the font lacks comes back null; the regular face stands in for it.
        faces =
            (0 until FACES).map { traits ->
                val mask = traitMask(traits)
                if (traits == 0) regular else CTFontCreateCopyWithSymbolicTraits(regular, 0.0, null, mask, mask) ?: regular
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
        (if (isBoldFace(traits)) kCTFontTraitBold else 0u) or (if (isItalicFace(traits)) kCTFontTraitItalic else 0u)

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
                ?: this@TextStyle.font.size * FALLBACK_ADVANCE
        }
}

// CoreText answers a name it does not know with some other font, so the family it gave is checked.
private fun named(font: Font): CTFontRef? {
    if (font.family.isEmpty()) return null
    val name = CFStringCreateWithCString(null, font.family, kCFStringEncodingUTF8) ?: return null
    val made = CTFontCreateWithName(name, font.size, null)
    CFRelease(name)
    val family = CFBridgingRelease(CTFontCopyFamilyName(made)) as? String
    if (family.equals(font.family, ignoreCase = true)) return made
    CFRelease(made)
    return null
}

/** The text of one code point, for a cell drawn on its own. */
internal fun codePointText(codePoint: Int): String =
    if (codePoint < 0x10000) {
        codePoint.toChar().toString()
    } else {
        val offset = codePoint - 0x10000
        charArrayOf((0xD800 + (offset shr 10)).toChar(), (0xDC00 + (offset and 0x3FF)).toChar()).concatToString()
    }
