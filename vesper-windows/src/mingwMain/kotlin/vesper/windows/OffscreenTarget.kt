package vesper.windows

import kotlinx.cinterop.CPointer
import vesper.windows.dx.D3D11_BIND_RENDER_TARGET
import vesper.windows.dx.ID3D11RenderTargetView
import vesper.windows.dx.ID3D11Texture2D

/**
 * A BGRA texture drawn into off screen and read back: how the renderer is tested on WARP, where
 * there is no window to present to.
 */
class OffscreenTarget private constructor(
    private val gpu: Gpu,
    val width: Int,
    val height: Int,
    private val texture: CPointer<ID3D11Texture2D>,
    val view: CPointer<ID3D11RenderTargetView>,
) : AutoCloseable {
    /** Fills the target with [rgb] (0xRRGGBB), opaque. */
    fun clear(rgb: Int) = gpu.clear(view, rgb)

    /** The pixels, top row first, each 0xAARRGGBB. */
    fun read(): IntArray = gpu.read(texture, width, height)

    override fun close() {
        release(view)
        release(texture)
    }

    companion object {
        fun create(
            gpu: Gpu,
            width: Int,
            height: Int,
        ): OffscreenTarget {
            val texture = gpu.texture(width, height, D3D11_BIND_RENDER_TARGET.toInt())
            val view =
                try {
                    gpu.renderView(texture)
                } catch (e: ComException) {
                    release(texture)
                    throw e
                }
            return OffscreenTarget(gpu, width, height, texture, view)
        }
    }
}
