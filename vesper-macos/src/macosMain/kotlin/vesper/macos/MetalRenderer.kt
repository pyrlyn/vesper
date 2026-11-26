package vesper.macos

import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.plus
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.CoreGraphics.CGColorSpaceCreateWithName
import platform.CoreGraphics.CGSizeMake
import platform.CoreGraphics.kCGColorSpaceSRGB
import platform.Foundation.NSError
import platform.Metal.MTLBlendFactorOne
import platform.Metal.MTLBlendFactorOneMinusSourceAlpha
import platform.Metal.MTLBufferProtocol
import platform.Metal.MTLClearColorMake
import platform.Metal.MTLCommandBufferProtocol
import platform.Metal.MTLCommandQueueProtocol
import platform.Metal.MTLCreateSystemDefaultDevice
import platform.Metal.MTLDeviceProtocol
import platform.Metal.MTLLoadActionClear
import platform.Metal.MTLPixelFormatBGRA8Unorm
import platform.Metal.MTLPixelFormatRGBA8Unorm
import platform.Metal.MTLPrimitiveTypeTriangleStrip
import platform.Metal.MTLRenderCommandEncoderProtocol
import platform.Metal.MTLRenderPassDescriptor
import platform.Metal.MTLRenderPipelineDescriptor
import platform.Metal.MTLRenderPipelineStateProtocol
import platform.Metal.MTLResourceStorageModeShared
import platform.Metal.MTLStorageModeShared
import platform.Metal.MTLStoreActionStore
import platform.Metal.MTLTextureDescriptor
import platform.Metal.MTLTextureProtocol
import platform.Metal.MTLTextureUsageRenderTarget
import platform.Metal.MTLTextureUsageShaderRead
import platform.QuartzCore.CACurrentMediaTime
import platform.QuartzCore.CAMetalLayer
import platform.darwin.DISPATCH_TIME_FOREVER
import platform.darwin.dispatch_semaphore_create
import platform.darwin.dispatch_semaphore_signal
import platform.darwin.dispatch_semaphore_wait
import platform.posix.memcpy
import vesper.grid.Attrs
import vesper.grid.Color
import vesper.grid.Style
import vesper.grid.Underline
import vesper.host.AtlasEntry
import vesper.host.Bitmap
import vesper.host.isSpriteKey
import vesper.host.keyFont
import vesper.host.keyGlyph
import vesper.host.keySprite
import vesper.host.spriteKey
import vesper.term.Frame
import vesper.term.FramePlacement
import kotlin.math.floor
import kotlin.math.max

// Draws a frame with Metal: every cell background, glyph, sprite and decoration is one instanced quad.
// Rows are built on the CPU only when the damage says they changed, kept in slots and reached through
// a row-to-slot table, so a scroll only reorders the table. Each of the in-flight buffers copies a
// slot only when its copy is older than the row.

private const val FRAMES_IN_FLIGHT = 3
private const val QUAD_BYTES = QUAD_INTS * 4
private const val MAX_IMAGE_SIDE = 16384

// Compiled from source when the renderer starts (a few milliseconds, once): Kotlin/Native's build has
// no Metal compiler step. `Quad` matches [Quads].
private const val SHADERS = """
#include <metal_stdlib>
using namespace metal;

struct Quad { float4 rect; ushort2 uv; uint color; uint kind; ushort2 span; };
struct Uniforms { float2 viewport; float2 origin; };
struct Fragment { float4 position [[position]]; float2 uv; float4 color; uint kind [[flat]]; };

vertex Fragment quad_vertex(uint vid [[vertex_id]], uint iid [[instance_id]],
                            const device Quad *quads [[buffer(0)]], constant Uniforms &u [[buffer(1)]]) {
    Quad q = quads[iid];
    float2 corner = float2(vid & 1, vid >> 1);
    float2 p = u.origin + q.rect.xy + corner * q.rect.zw;
    Fragment out;
    out.position = float4(p / u.viewport * float2(2, -2) + float2(-1, 1), 0, 1);
    out.uv = float2(q.uv) + corner * (q.kind == 3 ? float2(q.span) : q.rect.zw);
    out.color = unpack_unorm4x8_to_float(q.color);
    out.kind = q.kind;
    return out;
}

// Output is premultiplied; the blend is one, one minus source alpha.
fragment float4 quad_fragment(Fragment in [[stage_in]], texture2d<float> atlas [[texture(0)]],
                              texture2d<float> image [[texture(1)]]) {
    constexpr sampler texel(coord::pixel, filter::nearest);
    constexpr sampler scaled(coord::pixel, filter::linear, address::clamp_to_edge);
    if (in.kind == 3) { float4 t = image.sample(scaled, in.uv); return float4(t.rgb * t.a, t.a); }
    if (in.kind == 0) { return float4(in.color.rgb * in.color.a, in.color.a); }
    float4 t = atlas.sample(texel, in.uv);
    if (in.kind == 2) { return t * in.color.a; }
    float a = t.a * in.color.a;
    return float4(in.color.rgb * a, a);
}
"""

private class RowContent {
    val bg = Quads()
    val fg = Quads()
    val shelves = HashSet<Int>()
    var version = 0L
}

private class InFlight(
    val buffer: MTLBufferProtocol,
    val versions: LongArray,
)

internal class MetalRenderer private constructor(
    private val device: MTLDeviceProtocol,
    private val queue: MTLCommandQueueProtocol,
    private val pipeline: MTLRenderPipelineStateProtocol,
    private val text: TextStyle,
    private val palette: Palette,
) {
    val layer = CAMetalLayer()
    private val shaper = RunShaper(text)
    private var atlas = GlyphAtlas.create(device) ?: error("no atlas texture")
    private val images = HashMap<Long, MTLTextureProtocol>()
    private var scale = 0.0
    private var metrics = CellMetrics.of(text.faces[0], text.cell, 1.0)
    private var frameNumber = 0L
    private var version = 0L
    private var cols = 0
    private var rows = 0
    private var content = emptyArray<RowContent>()
    private var slots = RowSlots(0)
    private var buffers = emptyList<InFlight>()
    private var nextBuffer = 0
    private var capacityBg = 0
    private var capacityFg = 0
    private var needsRebuild = true
    private var offscreen: MTLTextureProtocol? = null
    private val inFlight = dispatch_semaphore_create(FRAMES_IN_FLIGHT.toLong())

    /**
     * Called for the next drawn frame, on any thread: with false and the host time the GPU finished it,
     * then with true and the time it reached the screen (0 if it never did). The latency probe's marks.
     */
    var onNextFrame: ((presented: Boolean, time: Double) -> Unit)? = null

    init {
        // QuartzCore only forward-declares the Metal protocols; the objects are the same.
        layer.device = device as objcnames.protocols.MTLDeviceProtocol
        layer.pixelFormat = MTLPixelFormatBGRA8Unorm
        layer.colorspace = CGColorSpaceCreateWithName(kCGColorSpaceSRGB)
        layer.opaque = true
        layer.maximumDrawableCount = FRAMES_IN_FLIGHT.toULong()
    }

    /** Draws [frame] into [layer]; [width] and [height] in points. */
    fun draw(
        frame: Frame,
        sources: RowSources,
        width: Double,
        height: Double,
        scale: Double,
        focused: Boolean,
    ) {
        prepare(frame, sources, scale)
        val pixelsWide = snap(width * scale)
        val pixelsHigh = snap(height * scale)
        if (pixelsWide < 1 || pixelsHigh < 1) return
        layer.contentsScale = scale
        layer.drawableSize = CGSizeMake(pixelsWide, pixelsHigh)
        val drawable = layer.nextDrawable() ?: return
        val handler = onNextFrame
        onNextFrame = null
        encode(frame, drawable.texture as MTLTextureProtocol, focused) { commands ->
            if (handler != null) {
                commands.addCompletedHandler { handler(false, CACurrentMediaTime()) }
                drawable.addPresentedHandler { presented -> handler(true, presented?.presentedTime ?: 0.0) }
            }
            commands.presentDrawable(drawable)
        }
    }

    /** Renders offscreen and waits for the GPU, for benchmarks and snapshots; BGRA rows, top first. */
    fun render(
        frame: Frame,
        sources: RowSources,
        width: Double,
        height: Double,
        scale: Double,
        focused: Boolean,
        readBack: Boolean = true,
    ): Bitmap? {
        prepare(frame, sources, scale)
        val w = snap(width * scale).toInt()
        val h = snap(height * scale).toInt()
        if (w <= 0 || h <= 0) return null
        val target =
            offscreen?.takeIf { it.width.toInt() == w && it.height.toInt() == h }
                ?: device.sharedTexture(w, h, MTLTextureUsageRenderTarget or MTLTextureUsageShaderRead)?.also { offscreen = it }
                ?: return null
        val commands = encode(frame, target, focused) {} ?: return null
        commands.waitUntilCompleted()
        if (!readBack) return null
        val pixels = ByteArray(w * h * 4)
        pixels.usePinned { target.getBytes(it.addressOf(0), (w * 4).toULong(), region(0, 0, w, h), 0u) }
        return Bitmap(w, h, 0, 0, true, pixels)
    }

    private fun prepare(
        frame: Frame,
        sources: RowSources,
        scale: Double,
    ) {
        if (scale != this.scale && scale > 0) {
            this.scale = scale
            metrics = CellMetrics.of(text.faces[0], text.cell, scale)
            // Glyphs rasterised at the old scale are the wrong size.
            atlas = GlyphAtlas.create(device) ?: atlas
            needsRebuild = true
        }
        if (frame.cols != cols || frame.rows != rows) allocate(frame.cols, frame.rows)
        frameNumber++
        val dirty =
            if (needsRebuild || sources.sources.size != rows) (0 until rows).toList() else slots.remap(sources.sources)
        needsRebuild = false
        sources.drawn(rows)
        // Shelves that reused rows draw from must survive this frame's evictions.
        for (slot in slots.slotOfRow) for (shelf in content[slot].shelves) atlas.touch(shelf, frameNumber)
        var generation = atlas.generation
        dirty.forEach { build(it, frame) }
        // A grown atlas dropped every entry, so rows built before the growth point at texels that are gone.
        repeat(4) {
            if (atlas.generation == generation) return
            generation = atlas.generation
            for (row in 0 until rows) build(row, frame)
        }
    }

    private fun allocate(
        cols: Int,
        rows: Int,
    ) {
        this.cols = cols
        this.rows = rows
        // A row has at most one background per cell; the cap on the rest keeps a flood of combining
        // marks from growing the buffers.
        capacityBg = cols
        capacityFg = cols * 4 + 16
        content = Array(rows) { RowContent() }
        slots = RowSlots(rows)
        val length = max(1, rows * (capacityBg + capacityFg)) * QUAD_BYTES
        buffers =
            (0 until FRAMES_IN_FLIGHT).mapNotNull {
                device.newBufferWithLength(length.toULong(), MTLResourceStorageModeShared)?.let { InFlight(it, LongArray(rows) { -1 }) }
            }
        needsRebuild = true
    }

    private fun x(col: Int): Float = floor(col * metrics.width).toFloat()

    private fun entry(key: Long): AtlasEntry? =
        atlas.entry(key, frameNumber) {
            if (isSpriteKey(key)) {
                spriteBitmap(keySprite(key), metrics)
            } else {
                shaper.registry.font(keyFont(key))?.let { Bitmap.glyph(keyGlyph(key), it, scale) }
            }
        }

    private fun build(
        row: Int,
        frame: Frame,
    ) {
        val out = content[slots.slotOfRow[row]]
        out.bg.clear()
        out.fg.clear()
        out.shelves.clear()
        // Neighbouring cells of one colour share a quad.
        var start = 0
        var current = palette.background
        for (col in 0..cols) {
            val bg =
                if (col ==
                    cols
                ) {
                    palette.background
                } else {
                    frame.cell(row, col).let { palette.colors(style(frame, it.style), it.flags).bg }
                }
            if (bg == current && col < cols) continue
            if (current != palette.background && col > start) {
                out.bg.add(x(start), 0f, x(col) - x(start), metrics.height.toFloat(), shaderColor(current))
            }
            start = col
            current = bg
        }
        val line = frame.row(row)
        if (line != null) for (run in line.runs) addRun(run.col, run.cols, run.width, line.runText(run), style(frame, run.style), out)
        out.version = ++version
    }

    private fun style(
        frame: Frame,
        id: Int,
    ): Style = frame.style(id) ?: Style.DEFAULT

    private fun addRun(
        first: Int,
        count: Int,
        width: Int,
        runText: String,
        style: Style,
        out: RowContent,
    ) {
        if (style.attrs and Attrs.HIDDEN != 0) return
        val fg = palette.colors(style).fg
        val color = shaderColor(fg)
        val face = face(style.attrs and Attrs.BOLD != 0, style.attrs and Attrs.ITALIC != 0)
        for (glyph in shaper.shape(runText, face, width)) {
            if (out.fg.count >= capacityFg) break
            val entry = entry(glyph.key)?.takeIf { !it.isEmpty } ?: continue
            val col = (first + glyph.column) * metrics.width
            // Sprites start on the cell's pixel edge, as backgrounds do.
            val pen = if (isSpriteKey(glyph.key)) floor(col) else snap(col + glyph.dx * scale)
            val baseline = metrics.baseline - snap(glyph.dy * scale)
            out.fg.add(
                (pen + entry.left).toFloat(),
                (baseline - entry.top).toFloat(),
                entry.width.toFloat(),
                entry.height.toFloat(),
                if (entry.isColor) shaderColor(0xFFFFFF) else color,
                if (entry.isColor) COLOR_GLYPH else TINTED,
                entry.x,
                entry.y,
            )
            out.shelves.add(entry.shelf)
        }
        val end = first + count
        if (style.underline != Underline.None) {
            val entry = entry(spriteKey(underlineSprite(style.underline)))?.takeIf { !it.isEmpty }
            if (entry != null) {
                val tint = if (style.underlineColor == Color.DEFAULT) color else shaderColor(palette.resolve(style.underlineColor, fg))
                for (col in first until end) {
                    if (out.fg.count >= capacityFg) break
                    out.fg.add(
                        x(col),
                        (metrics.baseline - entry.top).toFloat(),
                        entry.width.toFloat(),
                        entry.height.toFloat(),
                        tint,
                        TINTED,
                        entry.x,
                        entry.y,
                    )
                }
                out.shelves.add(entry.shelf)
            }
        }
        val lineAt = { y: Double -> out.fg.add(x(first), snap(y).toFloat(), x(end) - x(first), metrics.thickness.toFloat(), color) }
        if (style.attrs and Attrs.STRIKE != 0 && out.fg.count < capacityFg) lineAt(metrics.baseline - metrics.strike)
        if (style.attrs and Attrs.OVERLINE != 0 && out.fg.count < capacityFg) lineAt(0.0)
    }

    private fun encode(
        frame: Frame,
        target: MTLTextureProtocol,
        focused: Boolean,
        beforeCommit: (MTLCommandBufferProtocol) -> Unit,
    ): MTLCommandBufferProtocol? {
        dispatch_semaphore_wait(inFlight, DISPATCH_TIME_FOREVER)
        val commands = queue.commandBuffer()
        if (commands == null) {
            dispatch_semaphore_signal(inFlight)
            return null
        }
        val semaphore = inFlight
        commands.addCompletedHandler { dispatch_semaphore_signal(semaphore) }
        val pass = MTLRenderPassDescriptor.renderPassDescriptor()
        val attachment = pass.colorAttachments.objectAtIndexedSubscript(0u)
        attachment.texture = target
        attachment.loadAction = MTLLoadActionClear
        attachment.storeAction = MTLStoreActionStore
        val bg = palette.background
        attachment.clearColor = MTLClearColorMake(red(bg) / 255.0, green(bg) / 255.0, blue(bg) / 255.0, 1.0)
        val encoder = commands.renderCommandEncoderWithDescriptor(pass)
        if (encoder != null) {
            encoder.setRenderPipelineState(pipeline)
            encoder.setFragmentTexture(atlas.texture, 0u)
            encoder.setFragmentTexture(atlas.texture, 1u)
            if (buffers.isNotEmpty()) drawRows(frame, encoder, target, focused)
            encoder.endEncoding()
        }
        beforeCommit(commands)
        commands.commit()
        return commands
    }

    private fun drawRows(
        frame: Frame,
        encoder: MTLRenderCommandEncoderProtocol,
        target: MTLTextureProtocol,
        focused: Boolean,
    ) {
        val index = nextBuffer
        nextBuffer = (nextBuffer + 1) % buffers.size
        upload(buffers[index])
        val pad = (PADDING * scale).toFloat()
        val uniforms = floatArrayOf(target.width.toFloat(), target.height.toFloat(), pad, pad)
        val stride = capacityBg + capacityFg
        val (under, over) = cursorQuads(frame, focused)
        preeditQuads(frame, over)
        val placed = placeImages(frame)
        for (layer in 0..1) {
            encoder.setVertexBuffer(buffers[index].buffer, 0u, 0u)
            for (row in 0 until rows) {
                val slot = slots.slotOfRow[row]
                val count = if (layer == 0) content[slot].bg.count else content[slot].fg.count
                if (count == 0) continue
                uniforms[3] = pad + (row * metrics.height).toFloat()
                setUniforms(encoder, uniforms)
                val base = slot * stride + if (layer == 0) 0 else capacityBg
                encoder.drawPrimitives(MTLPrimitiveTypeTriangleStrip, 0u, 4u, count.toULong(), base.toULong())
            }
            // Images with a negative z go under the text, the rest over it. The cursor block goes over
            // the backgrounds; the glyph under it is drawn again on top in the background colour.
            uniforms[3] = pad
            for ((texture, quad, below) in placed) {
                if (below != (layer == 0)) continue
                encoder.setFragmentTexture(texture, 1u)
                drawQuads(encoder, quad, uniforms)
            }
            drawQuads(encoder, if (layer == 0) under else over, uniforms)
        }
    }

    private fun setUniforms(
        encoder: MTLRenderCommandEncoderProtocol,
        uniforms: FloatArray,
    ) = uniforms.usePinned { encoder.setVertexBytes(it.addressOf(0), (uniforms.size * 4).toULong(), 1u) }

    private fun drawQuads(
        encoder: MTLRenderCommandEncoderProtocol,
        quads: Quads,
        uniforms: FloatArray,
    ) {
        if (quads.count == 0) return
        setUniforms(encoder, uniforms)
        quads.data.usePinned { encoder.setVertexBytes(it.addressOf(0), (quads.count * QUAD_BYTES).toULong(), 0u) }
        encoder.drawPrimitives(MTLPrimitiveTypeTriangleStrip, 0u, 4u, quads.count.toULong())
    }

    private fun upload(target: InFlight) {
        val stride = capacityBg + capacityFg
        val base = target.buffer.contents() ?: return
        for (slot in content.indices) {
            val row = content[slot]
            if (target.versions[slot] == row.version) continue
            for ((quads, offset) in listOf(row.bg to 0, row.fg to capacityBg)) {
                if (quads.count == 0) continue
                quads.data.usePinned {
                    memcpy(
                        base.reinterpret<kotlinx.cinterop.ByteVar>() + (slot * stride + offset) * QUAD_BYTES,
                        it.addressOf(0),
                        (
                            quads.count *
                                QUAD_BYTES
                        ).toULong(),
                    )
                }
            }
            target.versions[slot] = row.version
        }
    }

    /** The cursor's quads in grid pixels: what goes under the text, and what goes over it. */
    private fun cursorQuads(
        frame: Frame,
        focused: Boolean,
    ): Pair<Quads, Quads> {
        val under = Quads(4)
        val over = Quads(4)
        val cursor = frame.cursor ?: return under to over
        if (cursor.row !in 0 until rows || cursor.col !in 0 until cols) return under to over
        val cell = frame.cell(cursor.row, cursor.col)
        val top = (cursor.row * metrics.height).toFloat()
        val left = x(cursor.col)
        val right = x(cursor.col + max(1, cell.width))
        val height = metrics.height.toFloat()
        val t = metrics.thickness.toFloat()
        val color = shaderColor(palette.cursor)
        if (!focused) {
            under.add(left, top, right - left, t, color)
            under.add(left, top + height - t, right - left, t, color)
            under.add(left, top, t, height, color)
            under.add(right - t, top, t, height, color)
            return under to over
        }
        under.add(left, top, right - left, height, color)
        val glyphs = content[slots.slotOfRow[cursor.row]].fg
        for (i in 0 until glyphs.count) {
            val middle = glyphs.x(i) + glyphs.w(i) / 2
            if (glyphs.kind(i) == TINTED && middle >= left && middle < right && over.count < 16) {
                over.addCopy(glyphs, i, top, shaderColor(palette.background))
            }
        }
        return under to over
    }

    /** Underlines under the shown preedit in grid pixels: one per clause with a gap between them, thick under the target. */
    private fun preeditQuads(
        frame: Frame,
        out: Quads,
    ) {
        val preedit = frame.preedit ?: return
        if (preedit.row !in 0 until rows) return
        val t = metrics.thickness.toFloat()
        val bottom = ((preedit.row + 1) * metrics.height).toFloat()
        val color = shaderColor(palette.foreground)
        for (line in preeditLines(preedit)) {
            val height = if (line.thick) 2 * t else t
            val left = x(line.col) + t
            out.add(left, bottom - height, x(line.col + line.cols) - t - left, height, color)
        }
    }

    /** The frame's images as textured quads; textures of images no longer placed are dropped. */
    private fun placeImages(frame: Frame): List<Triple<MTLTextureProtocol, Quads, Boolean>> {
        val kept = HashMap<Long, MTLTextureProtocol>()
        val out = ArrayList<Triple<MTLTextureProtocol, Quads, Boolean>>()
        for (p in frame.placements) {
            if (p.cols <= 0 || p.rows <= 0 || p.sourceWidth <= 0 || p.sourceHeight <= 0) continue
            val texture = kept[p.image.id] ?: images[p.image.id] ?: upload(p) ?: continue
            kept[p.image.id] = texture
            // The source rectangle stays inside the pixels, and within the quad's 16-bit texel fields.
            val srcX = p.sourceX.coerceIn(0, texture.width.toInt() - 1)
            val srcY = p.sourceY.coerceIn(0, texture.height.toInt() - 1)
            val srcW = p.sourceWidth.coerceAtMost(texture.width.toInt() - srcX)
            val srcH = p.sourceHeight.coerceAtMost(texture.height.toInt() - srcY)
            val quad = Quads(1)
            quad.add(
                x(p.col) + p.offsetX,
                floor(p.row * metrics.height).toFloat() + p.offsetY,
                (p.cols * metrics.width).toFloat(),
                (p.rows * metrics.height).toFloat(),
                -1,
                IMAGE,
                srcX,
                srcY,
                srcW,
                srcH,
            )
            out.add(Triple(texture, quad, p.z < 0))
        }
        images.clear()
        images.putAll(kept)
        return out
    }

    private fun upload(placement: FramePlacement): MTLTextureProtocol? {
        val pixels = placement.image.pixels
        if (pixels.width > MAX_IMAGE_SIDE || pixels.height > MAX_IMAGE_SIDE) return null
        val descriptor =
            MTLTextureDescriptor.texture2DDescriptorWithPixelFormat(
                MTLPixelFormatRGBA8Unorm,
                pixels.width.toULong(),
                pixels.height.toULong(),
                false,
            )
        descriptor.usage = MTLTextureUsageShaderRead
        descriptor.storageMode = MTLStorageModeShared
        val texture = device.newTextureWithDescriptor(descriptor) ?: return null
        pixels.rgba.usePinned {
            texture.replaceRegion(region(0, 0, pixels.width, pixels.height), 0u, it.addressOf(0), (pixels.width * 4).toULong())
        }
        return texture
    }

    companion object {
        /** A renderer on the system's GPU, or null without one (a VM, a CI runner) or if the shaders do not build. */
        fun create(
            text: TextStyle,
            palette: Palette,
        ): MetalRenderer? {
            val device = MTLCreateSystemDefaultDevice() ?: return null
            val queue = device.newCommandQueue() ?: return null
            val pipeline =
                memScoped {
                    val error = alloc<ObjCObjectVar<NSError?>>()
                    val library = device.newLibraryWithSource(SHADERS, null, error.ptr) ?: return@memScoped null
                    val descriptor = MTLRenderPipelineDescriptor()
                    descriptor.vertexFunction = library.newFunctionWithName("quad_vertex")
                    descriptor.fragmentFunction = library.newFunctionWithName("quad_fragment")
                    val color = descriptor.colorAttachments.objectAtIndexedSubscript(0u)
                    color.pixelFormat = MTLPixelFormatBGRA8Unorm
                    color.blendingEnabled = true
                    color.sourceRGBBlendFactor = MTLBlendFactorOne
                    color.sourceAlphaBlendFactor = MTLBlendFactorOne
                    color.destinationRGBBlendFactor = MTLBlendFactorOneMinusSourceAlpha
                    color.destinationAlphaBlendFactor = MTLBlendFactorOneMinusSourceAlpha
                    device.newRenderPipelineStateWithDescriptor(descriptor, error.ptr)
                } ?: return null
            return MetalRenderer(device, queue, pipeline, text, palette)
        }
    }
}
