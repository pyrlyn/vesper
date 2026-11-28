package vesper.windows

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.MemScope
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.allocArrayOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.cstr
import kotlinx.cinterop.get
import kotlinx.cinterop.invoke
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import vesper.host.GlyphCache
import vesper.host.PADDING
import vesper.host.Palette
import vesper.host.QUAD_INTS
import vesper.host.QuadBuilder
import vesper.host.Quads
import vesper.host.RowSources
import vesper.term.Frame
import vesper.windows.dx.D3D11_BIND_CONSTANT_BUFFER
import vesper.windows.dx.D3D11_BIND_VERTEX_BUFFER
import vesper.windows.dx.D3D11_BLEND_DESC
import vesper.windows.dx.D3D11_BLEND_INV_SRC_ALPHA
import vesper.windows.dx.D3D11_BLEND_ONE
import vesper.windows.dx.D3D11_BLEND_OP_ADD
import vesper.windows.dx.D3D11_BOX
import vesper.windows.dx.D3D11_BUFFER_DESC
import vesper.windows.dx.D3D11_COLOR_WRITE_ENABLE_ALL
import vesper.windows.dx.D3D11_CULL_NONE
import vesper.windows.dx.D3D11_FILL_SOLID
import vesper.windows.dx.D3D11_INPUT_ELEMENT_DESC
import vesper.windows.dx.D3D11_INPUT_PER_INSTANCE_DATA
import vesper.windows.dx.D3D11_RASTERIZER_DESC
import vesper.windows.dx.D3D11_USAGE_DEFAULT
import vesper.windows.dx.D3D11_VIEWPORT
import vesper.windows.dx.D3DCOMPILE_OPTIMIZATION_LEVEL3
import vesper.windows.dx.D3DCompile
import vesper.windows.dx.D3D_PRIMITIVE_TOPOLOGY_TRIANGLESTRIP
import vesper.windows.dx.DXGI_FORMAT
import vesper.windows.dx.DXGI_FORMAT_R32G32B32A32_FLOAT
import vesper.windows.dx.DXGI_FORMAT_R32_UINT
import vesper.windows.dx.DXGI_FORMAT_R8G8B8A8_UNORM
import vesper.windows.dx.ID3D10Blob
import vesper.windows.dx.ID3D11BlendState
import vesper.windows.dx.ID3D11Buffer
import vesper.windows.dx.ID3D11InputLayout
import vesper.windows.dx.ID3D11PixelShader
import vesper.windows.dx.ID3D11RasterizerState
import vesper.windows.dx.ID3D11RenderTargetView
import vesper.windows.dx.ID3D11VertexShader

private const val QUAD_BYTES = QUAD_INTS * 4

// The cursor's quads: a block or four sides under the text, and at most 16 glyphs over it.
private const val EXTRA_QUADS = 32

// Compiled when the renderer starts, as the Metal shaders are: Kotlin/Native's build has no HLSL
// step. Each quad is one instance read from the vertex buffer as Quads lays it out, the corner
// from the vertex id; the pixel shader writes premultiplied colour (blend one, one minus source alpha).
private const val SHADERS = """
cbuffer Uniforms : register(b0) { float2 viewport; float2 origin; };
Texture2D<float4> atlas : register(t0);

struct Quad { float4 rect : RECT; uint uv : UV; float4 color : COLOR; uint kind : KIND; };
struct Fragment { float4 position : SV_Position; float2 uv : TEXCOORD0; float4 color : COLOR; nointerpolation uint kind : KIND; };

Fragment quad_vertex(Quad q, uint vid : SV_VertexID) {
    float2 corner = float2(vid & 1, vid >> 1);
    float2 p = origin + q.rect.xy + corner * q.rect.zw;
    Fragment f;
    f.position = float4(p / viewport * float2(2, -2) + float2(-1, 1), 0, 1);
    f.uv = float2(q.uv & 0xFFFF, q.uv >> 16) + corner * q.rect.zw;
    f.color = q.color;
    f.kind = q.kind;
    return f;
}

float4 quad_pixel(Fragment f) : SV_Target {
    if (f.kind == 0) { return float4(f.color.rgb * f.color.a, f.color.a); }
    float4 t = atlas.Load(int3(floor(f.uv), 0));
    if (f.kind == 2) { return t * f.color.a; }
    float a = t.a * f.color.a;
    return float4(f.color.rgb * a, a);
}
"""

/** One pane's part of a picture: [frame] drawn into the [width] by [height] pixels at ([x], [y]) of the target. */
class PaneDraw(
    val quads: D3DRenderer.PaneQuads,
    val frame: Frame,
    val sources: RowSources,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val focused: Boolean,
)

/**
 * Draws frames with Direct3D 11. [QuadBuilder] turns each into quads per row slot; a pane's slots live
 * in one vertex buffer, and a slot is copied there only when its row was built again. The panes of a
 * window share the shaders, the glyph atlas and its cache, so a glyph is rasterised once per window.
 */
class D3DRenderer private constructor(
    private val gpu: Gpu,
    private var text: TextStyle,
    private var palette: Palette,
    private val vertex: CPointer<ID3D11VertexShader>,
    private val pixel: CPointer<ID3D11PixelShader>,
    private val layout: CPointer<ID3D11InputLayout>,
    private val blend: CPointer<ID3D11BlendState>,
    private val raster: CPointer<ID3D11RasterizerState>,
    private val constants: CPointer<ID3D11Buffer>,
    private val extra: CPointer<ID3D11Buffer>,
) : AutoCloseable {
    private val atlas = GpuAtlas(gpu)
    private var cache = newCache()
    private var scale = 0.0
    private var metrics = text.metrics(1.0)

    // One count for every pane of a picture, so a pane's glyphs are never evicted while it shows them.
    private var frameNumber = 0L
    private val panes = ArrayList<PaneQuads>()
    private val single = paneQuads()

    private fun newCache() = GlyphCache.create(atlas) ?: error("no atlas texture")

    private fun newBuilder() = QuadBuilder(palette, text, cache, metrics, scale)

    /** One pane's quads and their GPU copy; made by [paneQuads], closed by the pane. */
    inner class PaneQuads internal constructor() : AutoCloseable {
        internal var builder = newBuilder()
        internal var slots: CPointer<ID3D11Buffer>? = null
        private var versions = LongArray(0)

        internal fun prepare(
            frame: Frame,
            sources: RowSources,
        ) {
            if (!builder.update(frame, sources, frameNumber)) return
            release(slots)
            slots = null
            val bytes = maxOf(1, builder.rows * (builder.capacityBg + builder.capacityFg)) * QUAD_BYTES
            slots = gpu.buffer(bytes, D3D11_BIND_VERTEX_BUFFER.toInt())
            versions = LongArray(builder.rows) { -1 }
        }

        /** Copies each slot whose row was built since its last copy. */
        internal fun upload(slots: CPointer<ID3D11Buffer>) {
            val stride = builder.capacityBg + builder.capacityFg
            for (slot in builder.content.indices) {
                val row = builder.content[slot]
                if (versions[slot] == row.version) continue
                write(slots, slot * stride, row.bg)
                write(slots, slot * stride + builder.capacityBg, row.fg)
                versions[slot] = row.version
            }
        }

        override fun close() {
            release(slots)
            slots = null
            panes.remove(this)
        }
    }

    /** Quads for one more pane, drawn with this renderer's text and palette. */
    fun paneQuads(): PaneQuads = PaneQuads().also { panes += it }

    /**
     * Draws with [text] and [palette] from the next frame on. Every glyph and colour baked into the
     * quads is stale, so each pane's builder starts over: its first update rebuilds every row and the
     * slots are made again at the new cell size.
     */
    fun restyle(
        text: TextStyle,
        palette: Palette,
    ) {
        this.text = text
        this.palette = palette
        cache = newCache()
        scale = 0.0
        metrics = text.metrics(1.0)
        panes.forEach { it.builder = newBuilder() }
    }

    /** Draws [frame] into the whole of [view], [width] by [height] pixels at [scale] pixels per DIP. */
    fun render(
        frame: Frame,
        sources: RowSources,
        view: CPointer<ID3D11RenderTargetView>,
        width: Int,
        height: Int,
        scale: Double,
        focused: Boolean,
    ) = render(view, width, height, scale, listOf(PaneDraw(single, frame, sources, 0, 0, width, height, focused)))

    /** Draws each of [panes] into its part of [view], the rest in the background colour. */
    fun render(
        view: CPointer<ID3D11RenderTargetView>,
        width: Int,
        height: Int,
        scale: Double,
        panes: List<PaneDraw>,
    ) {
        rescale(scale)
        frameNumber++
        panes.forEach { it.quads.prepare(it.frame, it.sources) }
        gpu.clear(view, palette.background)
        if (width <= 0 || height <= 0) return
        val context = gpu.context
        val calls = context.pointed.lpVtbl!!.pointed
        memScoped {
            calls.RSSetState!!.invoke(context, raster)
            calls.OMSetRenderTargets!!.invoke(context, 1u, allocArrayOf(view), null)
            calls.OMSetBlendState!!.invoke(context, blend, null, 0xFFFFFFFFu)
            calls.IASetInputLayout!!.invoke(context, layout)
            calls.IASetPrimitiveTopology!!.invoke(context, D3D_PRIMITIVE_TOPOLOGY_TRIANGLESTRIP)
            calls.VSSetShader!!.invoke(context, vertex, null, 0u)
            calls.VSSetConstantBuffers!!.invoke(context, 0u, 1u, allocArrayOf(constants))
            calls.PSSetShader!!.invoke(context, pixel, null, 0u)
            calls.PSSetShaderResources!!.invoke(context, 0u, 1u, allocArrayOf(atlas.view))
            for (pane in panes) {
                val slots = pane.quads.slots ?: continue
                if (pane.width <= 0 || pane.height <= 0) continue
                pane.quads.upload(slots)
                // The shader maps into the viewport, so a pane draws as a window of its own size would.
                val viewport =
                    alloc<D3D11_VIEWPORT> {
                        TopLeftX = pane.x.toFloat()
                        TopLeftY = pane.y.toFloat()
                        Width = pane.width.toFloat()
                        Height = pane.height.toFloat()
                        MaxDepth = 1f
                    }
                calls.RSSetViewports!!.invoke(context, 1u, viewport.ptr)
                drawRows(pane.quads.builder, pane.frame, slots, pane.width, pane.height, pane.focused)
            }
        }
    }

    // Glyphs rasterised at the old scale are the wrong size; a new cache starts the texture afresh.
    private fun rescale(scale: Double) {
        if (scale == this.scale || scale <= 0) return
        val cache = GlyphCache.create(atlas) ?: return
        this.cache = cache
        this.scale = scale
        metrics = text.metrics(scale)
        panes.forEach { it.builder.rescale(scale, metrics, cache) }
    }

    private fun MemScope.drawRows(
        builder: QuadBuilder,
        frame: Frame,
        slots: CPointer<ID3D11Buffer>,
        width: Int,
        height: Int,
        focused: Boolean,
    ) {
        val context = gpu.context
        val calls = context.pointed.lpVtbl!!.pointed
        val pad = (PADDING * builder.scale).toFloat()
        val uniforms = floatArrayOf(width.toFloat(), height.toFloat(), pad, pad)
        val stride = builder.capacityBg + builder.capacityFg
        val (under, over) = builder.cursorQuads(frame, focused)
        write(extra, 0, under)
        write(extra, under.count, over)
        val strides = alloc<UIntVar> { value = QUAD_BYTES.toUInt() }
        val offsets = alloc<UIntVar> { value = 0u }
        for (layer in 0..1) {
            calls.IASetVertexBuffers!!.invoke(context, 0u, 1u, allocArrayOf(slots), strides.ptr, offsets.ptr)
            for (row in 0 until builder.rows) {
                val slot = builder.slots.slotOfRow[row]
                val count = if (layer == 0) builder.content[slot].bg.count else builder.content[slot].fg.count
                if (count == 0) continue
                uniforms[3] = pad + (row * builder.metrics.height).toFloat()
                setUniforms(uniforms)
                val base = slot * stride + if (layer == 0) 0 else builder.capacityBg
                calls.DrawInstanced!!.invoke(context, 4u, count.convert(), 0u, base.convert())
            }
            // The cursor block goes over the backgrounds; the glyphs under it are drawn again on top.
            val cursor = if (layer == 0) under else over
            if (cursor.count == 0) continue
            uniforms[3] = pad
            setUniforms(uniforms)
            calls.IASetVertexBuffers!!.invoke(context, 0u, 1u, allocArrayOf(extra), strides.ptr, offsets.ptr)
            calls.DrawInstanced!!.invoke(context, 4u, cursor.count.convert(), 0u, (if (layer == 0) 0 else under.count).convert())
        }
    }

    private fun setUniforms(uniforms: FloatArray) {
        uniforms.usePinned {
            gpu.context.pointed.lpVtbl!!
                .pointed.UpdateSubresource!!
                .invoke(gpu.context, constants.reinterpret(), 0u, null, it.addressOf(0), 0u, 0u)
        }
    }

    /** Copies [quads] into [buffer] from quad [at]. */
    private fun write(
        buffer: CPointer<ID3D11Buffer>,
        at: Int,
        quads: Quads,
    ) {
        if (quads.count == 0) return
        memScoped {
            val box =
                alloc<D3D11_BOX> {
                    left = (at * QUAD_BYTES).convert()
                    right = ((at + quads.count) * QUAD_BYTES).convert()
                    bottom = 1u
                    back = 1u
                }
            quads.data.usePinned {
                gpu.context.pointed.lpVtbl!!
                    .pointed.UpdateSubresource!!
                    .invoke(gpu.context, buffer.reinterpret(), 0u, box.ptr, it.addressOf(0), 0u, 0u)
            }
        }
    }

    override fun close() {
        panes.toList().forEach { it.close() }
        atlas.close()
        listOf(extra, constants, raster, blend, layout, pixel, vertex).forEach(::release)
    }

    companion object {
        /** A renderer on [gpu] drawing [text] in [palette]; throws [ComException] when a shader or state cannot be made. */
        fun create(
            gpu: Gpu,
            text: TextStyle,
            palette: Palette,
        ): D3DRenderer {
            val made = ArrayList<CPointer<*>>()
            try {
                val vertexCode = compile("quad_vertex", "vs_4_0").also(made::add)
                val pixelCode = compile("quad_pixel", "ps_4_0").also(made::add)
                val calls =
                    gpu.device.pointed.lpVtbl!!
                        .pointed
                val vertex =
                    gpu
                        .make<ID3D11VertexShader>("CreateVertexShader") { out ->
                            calls.CreateVertexShader!!.invoke(gpu.device, vertexCode.bytes(), vertexCode.size(), null, out)
                        }.also(made::add)
                val pixel =
                    gpu
                        .make<ID3D11PixelShader>("CreatePixelShader") { out ->
                            calls.CreatePixelShader!!.invoke(gpu.device, pixelCode.bytes(), pixelCode.size(), null, out)
                        }.also(made::add)
                val layout =
                    gpu
                        .make<ID3D11InputLayout>("CreateInputLayout") { out ->
                            val elements = allocArray<D3D11_INPUT_ELEMENT_DESC>(4)
                            // Offsets into Quads' eight ints: rect, texel, colour, kind.
                            element(elements, 0, "RECT", DXGI_FORMAT_R32G32B32A32_FLOAT, 0)
                            element(elements, 1, "UV", DXGI_FORMAT_R32_UINT, 16)
                            element(elements, 2, "COLOR", DXGI_FORMAT_R8G8B8A8_UNORM, 20)
                            element(elements, 3, "KIND", DXGI_FORMAT_R32_UINT, 24)
                            calls.CreateInputLayout!!.invoke(gpu.device, elements, 4u, vertexCode.bytes(), vertexCode.size(), out)
                        }.also(made::add)
                val blend =
                    gpu
                        .make<ID3D11BlendState>("CreateBlendState") { out ->
                            val desc = alloc<D3D11_BLEND_DESC>()
                            desc.RenderTarget[0].apply {
                                BlendEnable = 1
                                SrcBlend = D3D11_BLEND_ONE
                                DestBlend = D3D11_BLEND_INV_SRC_ALPHA
                                BlendOp = D3D11_BLEND_OP_ADD
                                SrcBlendAlpha = D3D11_BLEND_ONE
                                DestBlendAlpha = D3D11_BLEND_INV_SRC_ALPHA
                                BlendOpAlpha = D3D11_BLEND_OP_ADD
                                RenderTargetWriteMask = D3D11_COLOR_WRITE_ENABLE_ALL.convert()
                            }
                            calls.CreateBlendState!!.invoke(gpu.device, desc.ptr, out)
                        }.also(made::add)
                val raster =
                    gpu
                        .make<ID3D11RasterizerState>("CreateRasterizerState") { out ->
                            // Corners go in strip order, whichever way that winds once y is flipped.
                            val desc =
                                alloc<D3D11_RASTERIZER_DESC> {
                                    FillMode = D3D11_FILL_SOLID
                                    CullMode = D3D11_CULL_NONE
                                    DepthClipEnable = 1
                                }
                            calls.CreateRasterizerState!!.invoke(gpu.device, desc.ptr, out)
                        }.also(made::add)
                val constants = gpu.buffer(16, D3D11_BIND_CONSTANT_BUFFER.toInt()).also(made::add)
                val extra = gpu.buffer(EXTRA_QUADS * QUAD_BYTES, D3D11_BIND_VERTEX_BUFFER.toInt()).also(made::add)
                release(vertexCode)
                release(pixelCode)
                return D3DRenderer(gpu, text, palette, vertex, pixel, layout, blend, raster, constants, extra)
            } catch (e: Throwable) {
                made.forEach(::release)
                throw e
            }
        }

        /** Describes the quad field [semantic] at byte [offset]; the name lives as long as [this] scope. */
        private fun MemScope.element(
            elements: CPointer<D3D11_INPUT_ELEMENT_DESC>,
            index: Int,
            semantic: String,
            format: DXGI_FORMAT,
            offset: Int,
        ) {
            elements[index].apply {
                SemanticName = semantic.cstr.getPointer(this@element)
                Format = format
                AlignedByteOffset = offset.convert()
                InputSlotClass = D3D11_INPUT_PER_INSTANCE_DATA
                InstanceDataStepRate = 1u
            }
        }

        /** [SHADERS]' function [entry] compiled for [target]; the caller releases the bytecode. */
        private fun compile(
            entry: String,
            target: String,
        ): CPointer<ID3D10Blob> =
            memScoped {
                val code = alloc<CPointerVar<ID3D10Blob>>()
                val errors = alloc<CPointerVar<ID3D10Blob>>()
                val source = SHADERS.cstr
                val result =
                    D3DCompile(
                        source,
                        (source.size - 1).convert(),
                        "vesper.hlsl",
                        null,
                        null,
                        entry,
                        target,
                        D3DCOMPILE_OPTIMIZATION_LEVEL3.convert(),
                        0u,
                        code.ptr,
                        errors.ptr,
                    )
                val message =
                    errors.value?.let {
                        it
                            .bytes()
                            .readBytes(it.size().toInt())
                            .decodeToString()
                            .trimEnd('\u0000')
                    }
                release(errors.value)
                if (result < 0 || code.value == null) {
                    release(code.value)
                    throw ComException("D3DCompile $entry: ${message ?: "no message"}", result)
                }
                code.value!!
            }

        private fun CPointer<ID3D10Blob>.bytes() =
            pointed.lpVtbl!!
                .pointed.GetBufferPointer!!
                .invoke(this)!!

        private fun CPointer<ID3D10Blob>.size() =
            pointed.lpVtbl!!
                .pointed.GetBufferSize!!
                .invoke(this)
    }
}

/** A buffer of [bytes] bound as [bind], filled from the CPU with UpdateSubresource; the caller releases it. */
internal fun Gpu.buffer(
    bytes: Int,
    bind: Int,
): CPointer<ID3D11Buffer> =
    make("CreateBuffer") { out ->
        val desc =
            alloc<D3D11_BUFFER_DESC> {
                ByteWidth = bytes.convert()
                Usage = D3D11_USAGE_DEFAULT
                BindFlags = bind.convert()
            }
        device.pointed.lpVtbl!!
            .pointed.CreateBuffer!!
            .invoke(device, desc.ptr, null, out)
    }

/** Runs [create], which writes a new object to its argument, and answers the object; throws as [call] when it fails. */
internal inline fun <reified T : kotlinx.cinterop.CPointed> Gpu.make(
    call: String,
    create: MemScope.(CPointer<CPointerVar<T>>) -> Int,
): CPointer<T> =
    memScoped {
        val out = alloc<CPointerVar<T>>()
        checkHr(create(out.ptr), call)
        out.value!!
    }
