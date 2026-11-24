package vesper.macos

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.cValue
import kotlinx.cinterop.usePinned
import platform.Metal.MTLDeviceProtocol
import platform.Metal.MTLPixelFormatBGRA8Unorm
import platform.Metal.MTLRegion
import platform.Metal.MTLStorageModeShared
import platform.Metal.MTLTextureDescriptor
import platform.Metal.MTLTextureProtocol
import platform.Metal.MTLTextureUsageShaderRead

/** A [width] by [height] region of a texture from ([x], [y]). */
internal fun region(
    x: Int,
    y: Int,
    width: Int,
    height: Int,
) = cValue<MTLRegion> {
    origin.x = x.toULong()
    origin.y = y.toULong()
    origin.z = 0u
    size.width = width.toULong()
    size.height = height.toULong()
    size.depth = 1u
}

/** A BGRA texture the shaders sample, written from the CPU. */
internal fun MTLDeviceProtocol.sharedTexture(
    width: Int,
    height: Int,
    usage: ULong = MTLTextureUsageShaderRead,
): MTLTextureProtocol? {
    val descriptor =
        MTLTextureDescriptor.texture2DDescriptorWithPixelFormat(
            MTLPixelFormatBGRA8Unorm,
            width.toULong(),
            height.toULong(),
            false,
        )
    descriptor.storageMode = MTLStorageModeShared
    descriptor.usage = usage
    return newTextureWithDescriptor(descriptor)
}

/** The glyphs and sprites in one texture, laid out by [AtlasLayout]. */
internal class GlyphAtlas private constructor(
    private val device: MTLDeviceProtocol,
    private val layout: AtlasLayout,
    texture: MTLTextureProtocol,
) {
    var texture: MTLTextureProtocol = texture
        private set

    /** See [AtlasLayout.generation]: entries handed out under an older one are gone. */
    val generation: Int get() = layout.generation

    /** The entry for [key], rasterised by [make] the first time; null when it fits nowhere. */
    fun entry(
        key: Long,
        frame: Long,
        make: () -> Bitmap?,
    ): AtlasEntry? {
        layout.get(key, frame)?.let { return it }
        val bitmap = make()
        if (bitmap == null) {
            layout.putEmpty(key)
            return AtlasEntry.EMPTY
        }
        val size = layout.size
        val entry = layout.place(key, bitmap.width, bitmap.height, bitmap.left, bitmap.top, bitmap.isColor, frame) ?: return null
        if (layout.size != size) texture = device.sharedTexture(layout.size, layout.size) ?: return null
        bitmap.pixels.usePinned { pinned ->
            texture.replaceRegion(
                region(entry.x, entry.y, bitmap.width, bitmap.height),
                0u,
                pinned.addressOf(0),
                (bitmap.width * 4).toULong(),
            )
        }
        return entry
    }

    fun touch(
        shelf: Int,
        frame: Long,
    ) = layout.touch(shelf, frame)

    companion object {
        fun create(
            device: MTLDeviceProtocol,
            size: Int = 1024,
        ): GlyphAtlas? {
            val layout = AtlasLayout(size)
            return device.sharedTexture(size, size)?.let { GlyphAtlas(device, layout, it) }
        }
    }
}
