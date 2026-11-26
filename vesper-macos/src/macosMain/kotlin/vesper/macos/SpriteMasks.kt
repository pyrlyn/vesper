package vesper.macos

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGBitmapContextCreateImage
import platform.CoreGraphics.CGColorSpaceCreateDeviceGray
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGContextClipToMask
import platform.CoreGraphics.CGContextFillRect
import platform.CoreGraphics.CGContextRef
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGContextRestoreGState
import platform.CoreGraphics.CGContextSaveGState
import platform.CoreGraphics.CGContextScaleCTM
import platform.CoreGraphics.CGContextTranslateCTM
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGImageRef
import platform.CoreGraphics.CGImageRelease
import platform.CoreGraphics.CGRectMake
import vesper.host.Bitmap
import vesper.host.CellMetrics
import vesper.host.isSprite
import vesper.host.spriteBitmap

/**
 * The shared sprites as masks for the CoreText fallback, which paints them in the context's fill
 * colour, so it has no seams either. [cell] is in device pixels, [scale] of them to a point; each
 * mask is rasterised once, since the fallback redraws every cell on every frame.
 */
internal class SpriteMasks(
    private val cell: CellMetrics,
    val scale: Double,
) : AutoCloseable {
    private val masks = HashMap<Int, Pair<CGImageRef, Bitmap>?>()

    /**
     * Paints character [code]'s sprite into [context] over the cell whose top-left corner is ([x], [y])
     * in points; [context] is top-down. False when [code] is drawn from the font.
     */
    fun draw(
        context: CGContextRef?,
        code: Int,
        x: Double,
        y: Double,
    ): Boolean {
        if (!isSprite(code)) return false
        val (mask, sprite) =
            masks.getOrPut(code) { spriteBitmap(code, cell)?.let { bitmap -> mask(bitmap)?.let { it to bitmap } } }
                ?: return true
        val width = sprite.width / scale
        val height = sprite.height / scale
        CGContextSaveGState(context)
        // CoreGraphics puts an image's first row at the top of a y-up rectangle, and the context is top-down.
        CGContextTranslateCTM(context, x, y + height)
        CGContextScaleCTM(context, 1.0, -1.0)
        val rect = CGRectMake(0.0, 0.0, width, height)
        CGContextClipToMask(context, rect, mask)
        CGContextFillRect(context, rect)
        CGContextRestoreGState(context)
        return true
    }

    override fun close() {
        masks.values.forEach { it?.first?.let(::CGImageRelease) }
        masks.clear()
    }
}

/** The coverage of [sprite] as a grey image, which a clip takes as each pixel's alpha. */
private fun mask(sprite: Bitmap): CGImageRef? {
    val coverage = ByteArray(sprite.width * sprite.height) { sprite.pixels[it * 4 + 3] }
    val grey = CGColorSpaceCreateDeviceGray() ?: return null
    try {
        return coverage.usePinned { pinned ->
            val context =
                CGBitmapContextCreate(
                    pinned.addressOf(0),
                    sprite.width.toULong(),
                    sprite.height.toULong(),
                    8u,
                    sprite.width.toULong(),
                    grey,
                    CGImageAlphaInfo.kCGImageAlphaNone.value,
                ) ?: return@usePinned null
            val image = CGBitmapContextCreateImage(context)
            CGContextRelease(context)
            image
        }
    } finally {
        CGColorSpaceRelease(grey)
    }
}
