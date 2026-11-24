package vesper.windows

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.invoke
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import vesper.windows.dx.D3D11CreateDevice
import vesper.windows.dx.D3D11_CREATE_DEVICE_BGRA_SUPPORT
import vesper.windows.dx.D3D11_SDK_VERSION
import vesper.windows.dx.D3D_DRIVER_TYPE_HARDWARE
import vesper.windows.dx.D3D_DRIVER_TYPE_WARP
import vesper.windows.dx.ID3D11Device
import vesper.windows.dx.ID3D11DeviceContext

/**
 * A Direct3D 11 device and its immediate context. [warp] picks the software rasteriser: tests use
 * it, as a CI runner has no GPU, and the app falls back to it when the hardware device fails.
 */
class Gpu private constructor(
    val device: CPointer<ID3D11Device>,
    val context: CPointer<ID3D11DeviceContext>,
) : AutoCloseable {
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
                Gpu(device.value!!, context.value!!)
            }
    }
}
