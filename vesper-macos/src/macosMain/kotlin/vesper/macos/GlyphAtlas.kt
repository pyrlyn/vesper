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
import vesper.host.AtlasEntry
import vesper.host.AtlasTexture
import vesper.host.Bitmap
import vesper.host.GlyphCache

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

/** The glyphs and sprites in one Metal texture, cached by [GlyphCache]. */
internal class GlyphAtlas private constructor(
    private val store: Store,
    val cache: GlyphCache,
) {
    private class Store(
        val device: MTLDeviceProtocol,
    ) : AtlasTexture {
        var texture: MTLTextureProtocol? = null

        override fun resize(size: Int): Boolean {
            texture = device.sharedTexture(size, size) ?: return false
            return true
        }

        override fun upload(
            entry: AtlasEntry,
            bitmap: Bitmap,
        ) {
            bitmap.pixels.usePinned { pinned ->
                texture!!.replaceRegion(
                    region(entry.x, entry.y, bitmap.width, bitmap.height),
                    0u,
                    pinned.addressOf(0),
                    (bitmap.width * 4).toULong(),
                )
            }
        }
    }

    val texture: MTLTextureProtocol get() = store.texture!!

    companion object {
        fun create(
            device: MTLDeviceProtocol,
            size: Int = 1024,
        ): GlyphAtlas? {
            val store = Store(device)
            return GlyphCache.create(store, size)?.let { GlyphAtlas(store, it) }
        }
    }
}
