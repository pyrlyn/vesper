package vesper.windows

import kotlinx.cinterop.COpaquePointerVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.invoke
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.value
import platform.windows.HWND
import vesper.windows.dx.DXGI_ALPHA_MODE_IGNORE
import vesper.windows.dx.DXGI_FORMAT_B8G8R8A8_UNORM
import vesper.windows.dx.DXGI_FORMAT_UNKNOWN
import vesper.windows.dx.DXGI_MWA_NO_ALT_ENTER
import vesper.windows.dx.DXGI_SCALING_NONE
import vesper.windows.dx.DXGI_SWAP_CHAIN_DESC1
import vesper.windows.dx.DXGI_SWAP_EFFECT_FLIP_SEQUENTIAL
import vesper.windows.dx.DXGI_USAGE_RENDER_TARGET_OUTPUT
import vesper.windows.dx.ID3D11RenderTargetView
import vesper.windows.dx.ID3D11Texture2D
import vesper.windows.dx.IDXGIAdapter
import vesper.windows.dx.IDXGIDevice
import vesper.windows.dx.IDXGIFactory2
import vesper.windows.dx.IDXGISwapChain1
import vesper.windows.dx.IID_ID3D11Texture2D
import vesper.windows.dx.IID_IDXGIDevice
import kotlin.math.max

// From dxgi1_2.h; the sysroot's dxguid library predates it.
private val factory2Id = interfaceId(0x50c83a1cu, 0xe072u, 0x4c48u, 0x87b03630fa36a6d0uL)

/** Present's success code when the window cannot be seen, so nothing was shown. */
private const val DXGI_STATUS_OCCLUDED = 0x087A0001

/** A flip-model swap chain on a window's client area, with a view of the buffer drawn next. */
class SwapChain private constructor(
    private val gpu: Gpu,
    private val chain: CPointer<IDXGISwapChain1>,
    width: Int,
    height: Int,
) : AutoCloseable {
    var width = width
        private set
    var height = height
        private set
    var view: CPointer<ID3D11RenderTargetView> = createView()
        private set

    /** Resizes the buffers to [width] by [height] physical pixels; at least one pixel each way. */
    fun resize(
        width: Int,
        height: Int,
    ) {
        val w = max(1, width)
        val h = max(1, height)
        if (w == this.width && h == this.height) return
        // ResizeBuffers fails while anything still holds a buffer, the pipeline's binding included.
        release(view)
        gpu.context.pointed.lpVtbl!!
            .pointed.OMSetRenderTargets!!
            .invoke(gpu.context, 0u, null, null)
        checkHr(
            chain.pointed.lpVtbl!!
                .pointed.ResizeBuffers!!
                .invoke(chain, 0u, w.convert(), h.convert(), DXGI_FORMAT_UNKNOWN, 0u),
            "ResizeBuffers",
        )
        this.width = w
        this.height = h
        view = createView()
    }

    /** Fills the next buffer with [rgb] (0xRRGGBB). */
    fun clear(rgb: Int) = gpu.clear(view, rgb)

    /** Shows the drawn buffer at the next vertical blank; false when the window is hidden. */
    fun present(): Boolean {
        val result =
            chain.pointed.lpVtbl!!
                .pointed.Present!!
                .invoke(chain, 1u, 0u)
        checkHr(result, "Present")
        return result != DXGI_STATUS_OCCLUDED
    }

    /** The pixels of the buffer drawn next, as [Gpu.read] gives them; for tests. */
    internal fun readBackBuffer(): IntArray {
        val buffer = backBuffer()
        try {
            return gpu.read(buffer, width, height)
        } finally {
            release(buffer)
        }
    }

    override fun close() {
        release(view)
        release(chain)
    }

    private fun backBuffer(): CPointer<ID3D11Texture2D> =
        memScoped {
            val buffer = alloc<COpaquePointerVar>()
            checkHr(
                chain.pointed.lpVtbl!!
                    .pointed.GetBuffer!!
                    .invoke(chain, 0u, IID_ID3D11Texture2D.ptr, buffer.ptr),
                "GetBuffer",
            )
            buffer.value!!.reinterpret()
        }

    private fun createView(): CPointer<ID3D11RenderTargetView> {
        val buffer = backBuffer()
        try {
            return gpu.renderView(buffer)
        } finally {
            release(buffer)
        }
    }

    companion object {
        fun create(
            gpu: Gpu,
            hwnd: HWND,
            width: Int,
            height: Int,
        ): SwapChain {
            // The factory that made the device's adapter is the one that may make its swap chains.
            val device = query<IDXGIDevice>(gpu.device, IID_IDXGIDevice.ptr, "QueryInterface(IDXGIDevice)")
            var adapter: CPointer<IDXGIAdapter>? = null
            var factory: CPointer<IDXGIFactory2>? = null
            try {
                adapter =
                    memScoped {
                        val out = alloc<CPointerVar<IDXGIAdapter>>()
                        checkHr(
                            device.pointed.lpVtbl!!
                                .pointed.GetAdapter!!
                                .invoke(device, out.ptr),
                            "GetAdapter",
                        )
                        out.value!!
                    }
                factory =
                    memScoped {
                        val out = alloc<COpaquePointerVar>()
                        checkHr(
                            adapter.pointed.lpVtbl!!
                                .pointed.GetParent!!
                                .invoke(adapter, factory2Id, out.ptr),
                            "GetParent",
                        )
                        out.value!!.reinterpret()
                    }
                val chain =
                    memScoped {
                        val desc =
                            alloc<DXGI_SWAP_CHAIN_DESC1> {
                                Width = max(1, width).convert()
                                Height = max(1, height).convert()
                                Format = DXGI_FORMAT_B8G8R8A8_UNORM
                                SampleDesc.Count = 1u
                                BufferUsage = DXGI_USAGE_RENDER_TARGET_OUTPUT.convert()
                                BufferCount = 2u
                                // During a live resize the old frame stays put instead of stretching.
                                Scaling = DXGI_SCALING_NONE
                                SwapEffect = DXGI_SWAP_EFFECT_FLIP_SEQUENTIAL
                                AlphaMode = DXGI_ALPHA_MODE_IGNORE
                            }
                        val out = alloc<CPointerVar<IDXGISwapChain1>>()
                        checkHr(
                            factory.pointed.lpVtbl!!
                                .pointed.CreateSwapChainForHwnd!!
                                .invoke(factory, gpu.device.reinterpret(), hwnd, desc.ptr, null, null, out.ptr),
                            "CreateSwapChainForHwnd",
                        )
                        out.value!!
                    }
                // Alt+Enter is the terminal's to bind, not a switch to exclusive full screen.
                factory.pointed.lpVtbl!!
                    .pointed.MakeWindowAssociation!!
                    .invoke(factory, hwnd, DXGI_MWA_NO_ALT_ENTER.convert())
                return try {
                    SwapChain(gpu, chain, max(1, width), max(1, height))
                } catch (e: ComException) {
                    release(chain)
                    throw e
                }
            } finally {
                release(factory)
                release(adapter)
                release(device)
            }
        }
    }
}
