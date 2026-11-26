package vesper.windows

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.FloatVar
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.UShortVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.invoke
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.value
import platform.windows.RECT
import vesper.host.Bitmap
import vesper.windows.dx.DWRITE_COLOR_GLYPH_RUN
import vesper.windows.dx.DWRITE_GLYPH_RUN
import vesper.windows.dx.DWRITE_GRID_FIT_MODE_DEFAULT
import vesper.windows.dx.DWRITE_MEASURING_MODE_NATURAL
import vesper.windows.dx.DWRITE_RENDERING_MODE_NATURAL_SYMMETRIC
import vesper.windows.dx.DWRITE_TEXTURE_ALIASED_1x1
import vesper.windows.dx.DWRITE_TEXTURE_CLEARTYPE_3x1
import vesper.windows.dx.DWRITE_TEXT_ANTIALIAS_MODE_GRAYSCALE
import vesper.windows.dx.IDWriteColorGlyphRunEnumerator
import vesper.windows.dx.IDWriteGlyphRunAnalysis
import kotlin.math.roundToInt

// A colour layer whose palette index is this takes the text's own colour.
private const val TEXT_COLOR_LAYER = 0xFFFF

/** One glyph run's coverage, 0..255 per pixel, over [left]..[right] and [top]..[bottom] from the run's origin, y down. */
private class Coverage(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val alpha: ByteArray,
) {
    fun at(
        x: Int,
        y: Int,
    ): Int = if (x < left || x >= right || y < top || y >= bottom) 0 else alpha[(y - top) * (right - left) + (x - left)].toInt() and 0xFF
}

/**
 * Glyph [glyph] of [face] at [emSize] pixels to the em. A colour font's glyphs (emoji) keep their
 * colours; others become white coverage that the shader tints. Null when the glyph draws nothing.
 */
fun FontSystem.glyphBitmap(
    face: FontFace,
    glyph: Int,
    emSize: Float,
): Bitmap? =
    memScoped {
        val index = alloc<UShortVar> { value = glyph.toUShort() }
        val run =
            alloc<DWRITE_GLYPH_RUN> {
                fontFace = face.face
                fontEmSize = emSize
                glyphCount = 1u
                glyphIndices = index.ptr
            }
        colorBitmap(run.ptr) ?: coverage(run.ptr, 0f, 0f)?.let { white(it) }
    }

private fun white(coverage: Coverage): Bitmap? {
    val width = coverage.right - coverage.left
    val height = coverage.bottom - coverage.top
    if (width > Bitmap.MAX_SIDE || height > Bitmap.MAX_SIDE) return null
    val pixels = ByteArray(width * height * 4)
    for (i in 0 until width * height) {
        val a = coverage.alpha[i]
        for (c in 0 until 4) pixels[i * 4 + c] = a
    }
    return Bitmap(width, height, coverage.left, -coverage.top, false, pixels)
}

/** The colour layers of [run] stacked into one bitmap; null when its font has none for it. */
private fun FontSystem.colorBitmap(run: CPointer<DWRITE_GLYPH_RUN>): Bitmap? {
    val layers = ArrayList<Pair<Coverage, FloatArray>>()
    memScoped {
        val layersOut = alloc<CPointerVar<IDWriteColorGlyphRunEnumerator>>()
        // Any failure, DWRITE_E_NOCOLOR above all, means the glyph is drawn as plain coverage.
        val got =
            factory.pointed.lpVtbl!!
                .pointed.TranslateColorGlyphRun!!
                .invoke(factory, 0f, 0f, run, null, DWRITE_MEASURING_MODE_NATURAL, null, 0u, layersOut.ptr)
        val enumerator = layersOut.value
        if (got < 0 || enumerator == null) return null
        try {
            val calls = enumerator.pointed.lpVtbl!!.pointed
            val hasRun = alloc<IntVar>()
            val layer = alloc<CPointerVar<DWRITE_COLOR_GLYPH_RUN>>()
            while (true) {
                checkHr(calls.MoveNext!!.invoke(enumerator, hasRun.ptr), "MoveNext")
                if (hasRun.value == 0) break
                checkHr(calls.GetCurrentRun!!.invoke(enumerator, layer.ptr), "GetCurrentRun")
                val current = layer.value!!.pointed
                val coverage = coverage(current.glyphRun.ptr, current.baselineOriginX, current.baselineOriginY) ?: continue
                // D3DCOLORVALUE's members sit in unnamed unions; its memory is four floats, r g b a.
                val rgba = current.runColor.ptr.reinterpret<FloatVar>()
                val color =
                    if (current.paletteIndex.toInt() == TEXT_COLOR_LAYER) {
                        floatArrayOf(1f, 1f, 1f, 1f)
                    } else {
                        floatArrayOf(rgba[0], rgba[1], rgba[2], rgba[3])
                    }
                layers.add(coverage to color)
            }
        } finally {
            release(enumerator)
        }
    }
    if (layers.isEmpty()) return null
    val left = layers.minOf { it.first.left }
    val top = layers.minOf { it.first.top }
    val width = layers.maxOf { it.first.right } - left
    val height = layers.maxOf { it.first.bottom } - top
    if (width > Bitmap.MAX_SIDE || height > Bitmap.MAX_SIDE) return null
    // Premultiplied, each layer over those before it, as the font orders them.
    val pixels = FloatArray(width * height * 4)
    for ((coverage, color) in layers) {
        for (y in 0 until height) {
            for (x in 0 until width) {
                val alpha = coverage.at(x + left, y + top) / 255f * color[3]
                if (alpha == 0f) continue
                val at = (y * width + x) * 4
                // BGRA, as the atlas texture holds it.
                pixels[at] = color[2] * alpha + pixels[at] * (1 - alpha)
                pixels[at + 1] = color[1] * alpha + pixels[at + 1] * (1 - alpha)
                pixels[at + 2] = color[0] * alpha + pixels[at + 2] * (1 - alpha)
                pixels[at + 3] = alpha + pixels[at + 3] * (1 - alpha)
            }
        }
    }
    val bytes = ByteArray(pixels.size) { (pixels[it] * 255f).roundToInt().coerceIn(0, 255).toByte() }
    return Bitmap(width, height, left, -top, true, bytes)
}

/**
 * The grayscale coverage of [run] with its baseline origin at ([x], [y]); null when it covers
 * nothing. Grayscale antialiasing fills a ClearType-shaped texture with three equal values per
 * pixel, and an aliased one fills the 1x1 texture instead, so whichever has bounds is read.
 */
private fun FontSystem.coverage(
    run: CPointer<DWRITE_GLYPH_RUN>,
    x: Float,
    y: Float,
): Coverage? =
    memScoped {
        val analysisOut = alloc<CPointerVar<IDWriteGlyphRunAnalysis>>()
        checkHr(
            factory.pointed.lpVtbl!!.pointed.IDWriteFactory2_CreateGlyphRunAnalysis!!.invoke(
                factory,
                run,
                null,
                DWRITE_RENDERING_MODE_NATURAL_SYMMETRIC,
                DWRITE_MEASURING_MODE_NATURAL,
                DWRITE_GRID_FIT_MODE_DEFAULT,
                DWRITE_TEXT_ANTIALIAS_MODE_GRAYSCALE,
                x,
                y,
                analysisOut.ptr,
            ),
            "CreateGlyphRunAnalysis",
        )
        val analysis = analysisOut.value!!
        try {
            val calls = analysis.pointed.lpVtbl!!.pointed
            val bounds = alloc<RECT>()
            for ((type, bytesPerPixel) in listOf(DWRITE_TEXTURE_CLEARTYPE_3x1 to 3, DWRITE_TEXTURE_ALIASED_1x1 to 1)) {
                checkHr(calls.GetAlphaTextureBounds!!.invoke(analysis, type, bounds.ptr), "GetAlphaTextureBounds")
                val width = bounds.right - bounds.left
                val height = bounds.bottom - bounds.top
                if (width <= 0 || height <= 0) continue
                if (width > Bitmap.MAX_SIDE || height > Bitmap.MAX_SIDE) return@memScoped null
                val size = width * height * bytesPerPixel
                val buffer = allocArray<ByteVar>(size)
                checkHr(
                    calls.CreateAlphaTexture!!.invoke(analysis, type, bounds.ptr, buffer.reinterpret(), size.toUInt()),
                    "CreateAlphaTexture",
                )
                val alpha =
                    ByteArray(width * height) { i ->
                        var sum = 0
                        for (c in 0 until bytesPerPixel) sum += buffer[i * bytesPerPixel + c].toInt() and 0xFF
                        (sum / bytesPerPixel).toByte()
                    }
                return@memScoped Coverage(bounds.left, bounds.top, bounds.right, bounds.bottom, alpha)
            }
            null
        } finally {
            release(analysis)
        }
    }
