package vesper.windows

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArrayOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.invoke
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.plus
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.value
import vesper.windows.dx.D3D11_BIND_RENDER_TARGET
import vesper.windows.dx.D3D11_CPU_ACCESS_READ
import vesper.windows.dx.D3D11_MAPPED_SUBRESOURCE
import vesper.windows.dx.D3D11_MAP_READ
import vesper.windows.dx.D3D11_TEXTURE2D_DESC
import vesper.windows.dx.D3D11_USAGE_DEFAULT
import vesper.windows.dx.D3D11_USAGE_STAGING
import vesper.windows.dx.DXGI_FORMAT_B8G8R8A8_UNORM
import vesper.windows.dx.ID3D11RenderTargetView
import vesper.windows.dx.ID3D11Texture2D

/**
 * A BGRA texture drawn into off screen, with a staging copy to read it back: how the renderer is
 * tested on WARP, where there is no window to present to.
 */
class OffscreenTarget private constructor(
    private val gpu: Gpu,
    val width: Int,
    val height: Int,
    private val texture: CPointer<ID3D11Texture2D>,
    private val staging: CPointer<ID3D11Texture2D>,
    val view: CPointer<ID3D11RenderTargetView>,
) : AutoCloseable {
    /** Fills the target with [rgb] (0xRRGGBB), opaque. */
    fun clear(rgb: Int) {
        memScoped {
            val color = allocArrayOf(channel(rgb, 16), channel(rgb, 8), channel(rgb, 0), 1f)
            val context = gpu.context
            context.pointed.lpVtbl!!
                .pointed.ClearRenderTargetView!!
                .invoke(context, view, color)
        }
    }

    /** The pixels, top row first, each 0xAARRGGBB. */
    fun read(): IntArray =
        memScoped {
            val context = gpu.context
            val calls = context.pointed.lpVtbl!!.pointed
            calls.CopyResource!!.invoke(context, staging.reinterpret(), texture.reinterpret())
            val mapped = alloc<D3D11_MAPPED_SUBRESOURCE>()
            checkHr(calls.Map!!.invoke(context, staging.reinterpret(), 0u, D3D11_MAP_READ, 0u, mapped.ptr), "Map")
            try {
                // Rows may be padded past the width, so each starts at its own pitch.
                val base = mapped.pData!!.reinterpret<kotlinx.cinterop.ByteVar>()
                IntArray(width * height) { i ->
                    val at = base + ((i / width) * mapped.RowPitch.toInt() + (i % width) * 4)
                    at!!.reinterpret<IntVar>().pointed.value
                }
            } finally {
                calls.Unmap!!.invoke(context, staging.reinterpret(), 0u)
            }
        }

    override fun close() {
        release(view)
        release(staging)
        release(texture)
    }

    companion object {
        fun create(
            gpu: Gpu,
            width: Int,
            height: Int,
        ): OffscreenTarget =
            memScoped {
                val desc =
                    alloc<D3D11_TEXTURE2D_DESC> {
                        Width = width.convert()
                        Height = height.convert()
                        MipLevels = 1u
                        ArraySize = 1u
                        Format = DXGI_FORMAT_B8G8R8A8_UNORM
                        SampleDesc.Count = 1u
                        Usage = D3D11_USAGE_DEFAULT
                        BindFlags = D3D11_BIND_RENDER_TARGET.convert()
                    }
                val device = gpu.device
                val calls = device.pointed.lpVtbl!!.pointed
                val texture = alloc<CPointerVar<ID3D11Texture2D>>()
                checkHr(calls.CreateTexture2D!!.invoke(device, desc.ptr, null, texture.ptr), "CreateTexture2D")
                desc.Usage = D3D11_USAGE_STAGING
                desc.BindFlags = 0u
                desc.CPUAccessFlags = D3D11_CPU_ACCESS_READ.convert()
                val staging = alloc<CPointerVar<ID3D11Texture2D>>()
                checkHr(calls.CreateTexture2D!!.invoke(device, desc.ptr, null, staging.ptr), "CreateTexture2D")
                val view = alloc<CPointerVar<ID3D11RenderTargetView>>()
                checkHr(
                    calls.CreateRenderTargetView!!.invoke(device, texture.value!!.reinterpret(), null, view.ptr),
                    "CreateRenderTargetView",
                )
                OffscreenTarget(gpu, width, height, texture.value!!, staging.value!!, view.value!!)
            }

        private fun channel(
            rgb: Int,
            shift: Int,
        ) = ((rgb shr shift) and 0xFF) / 255f
    }
}
