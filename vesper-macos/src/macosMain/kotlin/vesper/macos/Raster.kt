package vesper.macos

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGColorSpaceCreateWithName
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGContextRef
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGContextScaleCTM
import platform.CoreGraphics.CGContextSetGrayFillColor
import platform.CoreGraphics.CGContextTranslateCTM
import platform.CoreGraphics.CGGlyphVar
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGPoint
import platform.CoreGraphics.kCGBitmapByteOrder32Little
import platform.CoreGraphics.kCGColorSpaceSRGB
import platform.CoreText.CTFontDrawGlyphs
import platform.CoreText.CTFontGetAscent
import platform.CoreText.CTFontGetBoundingRectsForGlyphs
import platform.CoreText.CTFontGetSymbolicTraits
import platform.CoreText.CTFontGetUnderlinePosition
import platform.CoreText.CTFontGetUnderlineThickness
import platform.CoreText.CTFontGetXHeight
import platform.CoreText.CTFontRef
import platform.CoreText.kCTFontOrientationDefault
import platform.CoreText.kCTFontTraitColorGlyphs
import vesper.host.Bitmap
import vesper.host.CellMetrics
import vesper.host.CellSize
import kotlin.math.ceil
import kotlin.math.floor

/** The metrics of [cell] at [scale] pixels per point, measured from [font]. */
internal fun CellMetrics.Companion.of(
    font: CTFontRef?,
    cell: CellSize,
    scale: Double,
): CellMetrics =
    of(cell, scale, CTFontGetAscent(font), CTFontGetUnderlinePosition(font), CTFontGetUnderlineThickness(font), CTFontGetXHeight(font))

/** Draws [body] into a fresh transparent bitmap, in CoreGraphics' bottom-up coordinates. */
internal fun Bitmap.Companion.draw(
    width: Int,
    height: Int,
    left: Int,
    top: Int,
    isColor: Boolean,
    body: (CGContextRef?) -> Unit,
): Bitmap? {
    if (width !in 1..Bitmap.MAX_SIDE || height !in 1..Bitmap.MAX_SIDE) return null
    val pixels = ByteArray(width * height * 4)
    val space = CGColorSpaceCreateWithName(kCGColorSpaceSRGB) ?: return null
    try {
        val drawn =
            pixels.usePinned { pinned ->
                val info = CGImageAlphaInfo.kCGImageAlphaPremultipliedFirst.value or kCGBitmapByteOrder32Little
                val context =
                    CGBitmapContextCreate(
                        pinned.addressOf(0),
                        width.toULong(),
                        height.toULong(),
                        8u,
                        (width * 4).toULong(),
                        space,
                        info,
                    )
                        ?: return@usePinned false
                body(context)
                CGContextRelease(context)
                true
            }
        return if (drawn) Bitmap(width, height, left, top, isColor, pixels) else null
    } finally {
        CGColorSpaceRelease(space)
    }
}

/**
 * Glyph [glyph] of [font] at [scale] pixels per point. A colour font's glyphs (emoji) keep their
 * colours; others become white coverage that the shader tints.
 */
internal fun Bitmap.Companion.glyph(
    glyph: Int,
    font: CTFontRef?,
    scale: Double,
): Bitmap? =
    memScoped {
        val id = alloc<CGGlyphVar>().apply { value = glyph.toUShort() }
        val bounds = CTFontGetBoundingRectsForGlyphs(font, kCTFontOrientationDefault, id.ptr, null, 1)
        val (minX, minY, maxX, maxY) =
            bounds.useContents { listOf(origin.x, origin.y, origin.x + size.width, origin.y + size.height) }
        if (!(maxX > minX && maxY > minY) || !(maxX - minX).isFinite() || !(maxY - minY).isFinite()) return@memScoped null
        val isColor = CTFontGetSymbolicTraits(font) and kCTFontTraitColorGlyphs != 0u
        // One pixel of slack on each side: antialiasing spills past the outline's bounds.
        val left = floor(minX * scale).toInt() - 1
        val bottom = floor(minY * scale).toInt() - 1
        val width = ceil(maxX * scale).toInt() + 1 - left
        val height = ceil(maxY * scale).toInt() + 1 - bottom
        draw(width, height, left, bottom + height, isColor) { context ->
            CGContextTranslateCTM(context, -left.toDouble(), -bottom.toDouble())
            CGContextScaleCTM(context, scale, scale)
            CGContextSetGrayFillColor(context, 1.0, 1.0)
            val origin = alloc<CGPoint>()
            CTFontDrawGlyphs(font, id.ptr, origin.ptr, 1u, context)
        }
    }
