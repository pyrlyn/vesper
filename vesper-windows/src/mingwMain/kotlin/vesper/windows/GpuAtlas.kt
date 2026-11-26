package vesper.windows

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.invoke
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import vesper.host.AtlasEntry
import vesper.host.AtlasTexture
import vesper.host.Bitmap
import vesper.windows.dx.D3D11_BIND_SHADER_RESOURCE
import vesper.windows.dx.D3D11_BOX
import vesper.windows.dx.ID3D11ShaderResourceView
import vesper.windows.dx.ID3D11Texture2D

/** The glyph atlas on the GPU: a BGRA texture the shaders sample through [view]. */
class GpuAtlas(
    private val gpu: Gpu,
) : AtlasTexture,
    AutoCloseable {
    var texture: CPointer<ID3D11Texture2D>? = null
        private set
    var view: CPointer<ID3D11ShaderResourceView>? = null
        private set

    /** The texture's side. */
    var size: Int = 0
        private set

    override fun resize(size: Int): Boolean {
        close()
        val texture =
            try {
                gpu.texture(size, size, D3D11_BIND_SHADER_RESOURCE.toInt())
            } catch (_: ComException) {
                return false
            }
        val view =
            memScoped {
                val out = alloc<CPointerVar<ID3D11ShaderResourceView>>()
                val got =
                    gpu.device.pointed.lpVtbl!!
                        .pointed.CreateShaderResourceView!!
                        .invoke(gpu.device, texture.reinterpret(), null, out.ptr)
                if (got < 0) null else out.value
            }
        if (view == null) {
            release(texture)
            return false
        }
        this.texture = texture
        this.view = view
        this.size = size
        return true
    }

    override fun upload(
        entry: AtlasEntry,
        bitmap: Bitmap,
    ) {
        val texture = texture ?: return
        memScoped {
            val box =
                alloc<D3D11_BOX> {
                    left = entry.x.convert()
                    top = entry.y.convert()
                    front = 0u
                    right = (entry.x + bitmap.width).convert()
                    bottom = (entry.y + bitmap.height).convert()
                    back = 1u
                }
            bitmap.pixels.usePinned { pinned ->
                gpu.context.pointed.lpVtbl!!
                    .pointed.UpdateSubresource!!
                    .invoke(gpu.context, texture.reinterpret(), 0u, box.ptr, pinned.addressOf(0), (bitmap.width * 4).convert(), 0u)
            }
        }
    }

    override fun close() {
        release(view)
        release(texture)
        view = null
        texture = null
        size = 0
    }
}
