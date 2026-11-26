package vesper.windows

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.FloatVar
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.UShortVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.invoke
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.value
import kotlinx.cinterop.wcstr
import vesper.windows.dx.DWRITE_FACTORY_TYPE_SHARED
import vesper.windows.dx.DWRITE_FONT_METRICS
import vesper.windows.dx.DWRITE_FONT_STRETCH_NORMAL
import vesper.windows.dx.DWRITE_FONT_STYLE_ITALIC
import vesper.windows.dx.DWRITE_FONT_STYLE_NORMAL
import vesper.windows.dx.DWRITE_GLYPH_METRICS
import vesper.windows.dx.DWriteCreateFactory
import vesper.windows.dx.IDWriteFactory2
import vesper.windows.dx.IDWriteFont
import vesper.windows.dx.IDWriteFontCollection
import vesper.windows.dx.IDWriteFontFace
import vesper.windows.dx.IDWriteFontFallback
import vesper.windows.dx.IDWriteFontFamily

// From dwrite_2.h: IDWriteFactory2 brings the system font fallback the renderer needs.
private val factory2Id = interfaceId(0x0439fc60u, 0xca44u, 0x4994u, 0x8dee3a9af7b732ecuL)

/** The shared DirectWrite factory and the system's font collection. */
class FontSystem private constructor(
    val factory: CPointer<IDWriteFactory2>,
    private val collection: CPointer<IDWriteFontCollection>,
) : AutoCloseable {
    /** The face of [family] nearest to [weight] and [italic], or null when it is not installed. */
    fun face(
        family: String,
        weight: Int = 400,
        italic: Boolean = false,
    ): FontFace? =
        memScoped {
            val index = alloc<UIntVar>()
            val exists = alloc<IntVar>()
            val calls = collection.pointed.lpVtbl!!.pointed
            checkHr(calls.FindFamilyName!!.invoke(collection, family.wcstr.ptr, index.ptr, exists.ptr), "FindFamilyName")
            if (exists.value == 0) return@memScoped null
            val fontFamily = alloc<CPointerVar<IDWriteFontFamily>>()
            checkHr(calls.GetFontFamily!!.invoke(collection, index.value, fontFamily.ptr), "GetFontFamily")
            val font = alloc<CPointerVar<IDWriteFont>>()
            try {
                val familyPointer = fontFamily.value!!
                checkHr(
                    familyPointer.pointed.lpVtbl!!.pointed.GetFirstMatchingFont!!.invoke(
                        familyPointer,
                        weight.convert(),
                        DWRITE_FONT_STRETCH_NORMAL,
                        if (italic) DWRITE_FONT_STYLE_ITALIC else DWRITE_FONT_STYLE_NORMAL,
                        font.ptr,
                    ),
                    "GetFirstMatchingFont",
                )
                faceOf(font.value!!)
            } finally {
                release(font.value)
                release(fontFamily.value)
            }
        }

    /**
     * The face that draws [text] from its start when [family] cannot: how many UTF-16 units of it
     * that face covers, and the scale that matches its size to [family]'s. The face is [family]'s
     * own when it has the characters, and null over a stretch that no installed font has.
     */
    fun fallback(
        text: String,
        family: String,
        weight: Int = 400,
        italic: Boolean = false,
    ): Fallback =
        withTextSource(text) { source, length ->
            memScoped {
                val mapped = alloc<UIntVar>()
                val font = alloc<CPointerVar<IDWriteFont>>()
                val scale = alloc<FloatVar>()
                checkHr(
                    systemFallback.pointed.lpVtbl!!.pointed.MapCharacters!!.invoke(
                        systemFallback,
                        source,
                        0u,
                        length.convert(),
                        collection,
                        family.wcstr.ptr,
                        weight.convert(),
                        if (italic) DWRITE_FONT_STYLE_ITALIC else DWRITE_FONT_STYLE_NORMAL,
                        DWRITE_FONT_STRETCH_NORMAL,
                        mapped.ptr,
                        font.ptr,
                        scale.ptr,
                    ),
                    "MapCharacters",
                )
                val face =
                    font.value?.let {
                        try {
                            faceOf(it)
                        } finally {
                            release(it)
                        }
                    }
                Fallback(face, mapped.value.toInt(), scale.value)
            }
        }

    private val fallbackOnce =
        lazy {
            memScoped {
                val out = alloc<CPointerVar<IDWriteFontFallback>>()
                checkHr(
                    factory.pointed.lpVtbl!!
                        .pointed.GetSystemFontFallback!!
                        .invoke(factory, out.ptr),
                    "GetSystemFontFallback",
                )
                out.value!!
            }
        }
    private val systemFallback by fallbackOnce

    private fun faceOf(font: CPointer<IDWriteFont>): FontFace =
        memScoped {
            val face = alloc<CPointerVar<IDWriteFontFace>>()
            checkHr(
                font.pointed.lpVtbl!!
                    .pointed.CreateFontFace!!
                    .invoke(font, face.ptr),
                "CreateFontFace",
            )
            FontFace(face.value!!)
        }

    override fun close() {
        if (fallbackOnce.isInitialized()) release(systemFallback)
        release(collection)
        release(factory)
    }

    companion object {
        fun create(): FontSystem =
            memScoped {
                val factory = alloc<CPointerVar<IDWriteFactory2>>()
                checkHr(
                    DWriteCreateFactory(DWRITE_FACTORY_TYPE_SHARED, factory2Id, factory.ptr.reinterpret()),
                    "DWriteCreateFactory",
                )
                val pointer = factory.value!!
                val collection = alloc<CPointerVar<IDWriteFontCollection>>()
                val got =
                    pointer.pointed.lpVtbl!!
                        .pointed.GetSystemFontCollection!!
                        .invoke(pointer, collection.ptr, 0)
                if (got < 0) release(pointer)
                checkHr(got, "GetSystemFontCollection")
                FontSystem(pointer, collection.value!!)
            }
    }
}

/** What [FontSystem.fallback] found: [face] draws the first [length] UTF-16 units at [scale] times the size. */
class Fallback(
    val face: FontFace?,
    val length: Int,
    val scale: Float,
)

/** One font face, measured in its design units. */
class FontFace internal constructor(
    val face: CPointer<IDWriteFontFace>,
) : AutoCloseable {
    val unitsPerEm: Int
    val ascent: Int
    val descent: Int
    val lineGap: Int

    /** Up from the baseline to the underline's middle; below it is negative. */
    val underlinePosition: Int
    val underlineThickness: Int
    val xHeight: Int

    init {
        memScoped {
            val metrics = alloc<DWRITE_FONT_METRICS>()
            face.pointed.lpVtbl!!
                .pointed.GetMetrics!!
                .invoke(face, metrics.ptr)
            unitsPerEm = metrics.designUnitsPerEm.toInt()
            ascent = metrics.ascent.toInt()
            descent = metrics.descent.toInt()
            lineGap = metrics.lineGap.toInt()
            underlinePosition = metrics.underlinePosition.toInt()
            underlineThickness = metrics.underlineThickness.toInt()
            xHeight = metrics.xHeight.toInt()
        }
    }

    /** The glyph for [codePoint], or 0 (the missing glyph) when the face has none. */
    fun glyph(codePoint: Int): Int =
        memScoped {
            val point = alloc<UIntVar> { value = codePoint.toUInt() }
            val glyph = alloc<UShortVar>()
            checkHr(
                face.pointed.lpVtbl!!
                    .pointed.GetGlyphIndices!!
                    .invoke(face, point.ptr, 1u, glyph.ptr),
                "GetGlyphIndices",
            )
            glyph.value.toInt()
        }

    /** How far [glyph] advances the pen, in design units. */
    fun advance(glyph: Int): Int =
        memScoped {
            val index = alloc<UShortVar> { value = glyph.toUShort() }
            val metrics = alloc<DWRITE_GLYPH_METRICS>()
            checkHr(
                face.pointed.lpVtbl!!
                    .pointed.GetDesignGlyphMetrics!!
                    .invoke(face, index.ptr, 1u, metrics.ptr, 0),
                "GetDesignGlyphMetrics",
            )
            metrics.advanceWidth.toInt()
        }

    override fun close() = release(face)
}
