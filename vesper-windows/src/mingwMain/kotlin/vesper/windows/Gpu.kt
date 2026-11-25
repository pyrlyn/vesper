package vesper.windows

import kotlinx.cinterop.ByteVar
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
import vesper.windows.dx.D3D11CreateDevice
import vesper.windows.dx.D3D11_CPU_ACCESS_READ
import vesper.windows.dx.D3D11_CREATE_DEVICE_BGRA_SUPPORT
import vesper.windows.dx.D3D11_MAPPED_SUBRESOURCE
import vesper.windows.dx.D3D11_MAP_READ
import vesper.windows.dx.D3D11_SDK_VERSION
import vesper.windows.dx.D3D11_TEXTURE2D_DESC
import vesper.windows.dx.D3D11_USAGE
import vesper.windows.dx.D3D11_USAGE_DEFAULT
import vesper.windows.dx.D3D11_USAGE_STAGING
import vesper.windows.dx.D3D_DRIVER_TYPE_HARDWARE
import vesper.windows.dx.D3D_DRIVER_TYPE_WARP
import vesper.windows.dx.DXGI_FORMAT_B8G8R8A8_UNORM
import vesper.windows.dx.ID3D11Device
import vesper.windows.dx.ID3D11DeviceContext
import vesper.windows.dx.ID3D11RenderTargetView
import vesper.windows.dx.ID3D11Texture2D

/**
 * A Direct3D 11 device and its immediate context. [isWarp] is the software rasteriser: tests use
 * it, as a CI runner has no GPU, and the app falls back to it when the hardware device fails.
 */
class Gpu private constructor(
    val device: CPointer<ID3D11Device>,
    val context: CPointer<ID3D11DeviceContext>,
    val isWarp: Boolean,
) : AutoCloseable {
    /** A BGRA texture of one mip level; the caller releases it. */
    internal fun texture(
        width: Int,
        height: Int,
        bind: Int,
        usage: D3D11_USAGE = D3D11_USAGE_DEFAULT,
        cpuAccess: Int = 0,
    ): CPointer<ID3D11Texture2D> =
        memScoped {
            val desc =
                alloc<D3D11_TEXTURE2D_DESC> {
                    Width = width.convert()
                    Height = height.convert()
                    MipLevels = 1u
                    ArraySize = 1u
                    Format = DXGI_FORMAT_B8G8R8A8_UNORM
                    SampleDesc.Count = 1u
                    Usage = usage
                    BindFlags = bind.convert()
                    CPUAccessFlags = cpuAccess.convert()
                }
            val texture = alloc<CPointerVar<ID3D11Texture2D>>()
            checkHr(
                device.pointed.lpVtbl!!
                    .pointed.CreateTexture2D!!
                    .invoke(device, desc.ptr, null, texture.ptr),
                "CreateTexture2D",
            )
            texture.value!!
        }

    /** A view to draw into [texture] through; the caller releases it. */
    internal fun renderView(texture: CPointer<ID3D11Texture2D>): CPointer<ID3D11RenderTargetView> =
        memScoped {
            val view = alloc<CPointerVar<ID3D11RenderTargetView>>()
            checkHr(
                device.pointed.lpVtbl!!
                    .pointed.CreateRenderTargetView!!
                    .invoke(device, texture.reinterpret(), null, view.ptr),
                "CreateRenderTargetView",
            )
            view.value!!
        }

    /** Fills [view] with [rgb] (0xRRGGBB), opaque. */
    fun clear(
        view: CPointer<ID3D11RenderTargetView>,
        rgb: Int,
    ) {
        memScoped {
            val color = allocArrayOf(channel(rgb, 16), channel(rgb, 8), channel(rgb, 0), 1f)
            context.pointed.lpVtbl!!
                .pointed.ClearRenderTargetView!!
                .invoke(context, view, color)
        }
    }

    /** The pixels of [texture], top row first, each 0xAARRGGBB, through a staging copy. */
    fun read(
        texture: CPointer<ID3D11Texture2D>,
        width: Int,
        height: Int,
    ): IntArray {
        val staging = texture(width, height, 0, D3D11_USAGE_STAGING, D3D11_CPU_ACCESS_READ.toInt())
        try {
            return memScoped {
                val calls = context.pointed.lpVtbl!!.pointed
                calls.CopyResource!!.invoke(context, staging.reinterpret(), texture.reinterpret())
                val mapped = alloc<D3D11_MAPPED_SUBRESOURCE>()
                checkHr(calls.Map!!.invoke(context, staging.reinterpret(), 0u, D3D11_MAP_READ, 0u, mapped.ptr), "Map")
                try {
                    // Rows may be padded past the width, so each starts at its own pitch.
                    val base = mapped.pData!!.reinterpret<ByteVar>()
                    IntArray(width * height) { i ->
                        val at = base + ((i / width) * mapped.RowPitch.toInt() + (i % width) * 4)
                        at!!.reinterpret<IntVar>().pointed.value
                    }
                } finally {
                    calls.Unmap!!.invoke(context, staging.reinterpret(), 0u)
                }
            }
        } finally {
            release(staging)
        }
    }

    override fun close() {
        release(context)
        release(device)
    }

    companion object {
        fun create(warp: Boolean = false): Gpu =
            memScoped {
                val device = alloc<CPointerVar<ID3D11Device>>()
                val context = alloc<CPointerVar<ID3D11DeviceContext>>()
                checkHr(
                    D3D11CreateDevice(
                        null,
                        if (warp) D3D_DRIVER_TYPE_WARP else D3D_DRIVER_TYPE_HARDWARE,
                        null,
                        // DirectWrite and the swap chain trade in BGRA.
                        D3D11_CREATE_DEVICE_BGRA_SUPPORT.convert(),
                        null,
                        0u,
                        D3D11_SDK_VERSION.convert(),
                        device.ptr,
                        null,
                        context.ptr,
                    ),
                    "D3D11CreateDevice",
                )
                Gpu(device.value!!, context.value!!, warp)
            }

        /** The hardware device, or WARP where there is no usable GPU or driver. */
        fun best(): Gpu =
            try {
                create()
            } catch (_: ComException) {
                create(warp = true)
            }

        private fun channel(
            rgb: Int,
            shift: Int,
        ) = ((rgb shr shift) and 0xFF) / 255f
    }
}
