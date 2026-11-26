package vesper.windows

import kotlinx.cinterop.COpaquePointerVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.LongVar
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.UShortVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.plus
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.toLong
import kotlinx.cinterop.value
import kotlinx.cinterop.wcstr
import platform.posix._GUID
import platform.windows.GetUserDefaultLocaleName
import vesper.windows.dx.DWRITE_READING_DIRECTION_LEFT_TO_RIGHT
import vesper.windows.dx.IDWriteNumberSubstitution
import vesper.windows.dx.IDWriteTextAnalysisSource
import vesper.windows.dx.IDWriteTextAnalysisSourceVtbl

private const val S_OK = 0
private const val E_NOINTERFACE = 0x80004002.toInt()
private const val LOCALE_NAME_MAX_LENGTH = 85

// The object is three words: the vtable, then the text's address and its length in UTF-16 units.
private const val TEXT_SLOT = 1
private const val LENGTH_SLOT = 2

private fun CPointer<IDWriteTextAnalysisSource>?.slot(index: Int): Long = this!!.reinterpret<LongVar>()[index]

/** The user's locale, which steers fallback between the CJK fonts. */
private val locale: CPointer<UShortVar> by lazy {
    val name = nativeHeap.allocArray<UShortVar>(LOCALE_NAME_MAX_LENGTH)
    if (GetUserDefaultLocaleName(name, LOCALE_NAME_MAX_LENGTH) == 0) name[0] = 0u
    name
}

/**
 * The calls DirectWrite makes into a text source. Fallback holds the source only during the one
 * call it is passed to, so reference counting has nothing to free and answers a constant.
 */
private val vtable: CPointer<IDWriteTextAnalysisSourceVtbl> by lazy {
    nativeHeap
        .alloc<IDWriteTextAnalysisSourceVtbl> {
            QueryInterface =
                staticCFunction { _: CPointer<IDWriteTextAnalysisSource>?, _: CPointer<_GUID>?, out: CPointer<COpaquePointerVar>? ->
                    out?.set(0, null)
                    E_NOINTERFACE
                }
            AddRef = staticCFunction { _: CPointer<IDWriteTextAnalysisSource>? -> 1u }
            Release = staticCFunction { _: CPointer<IDWriteTextAnalysisSource>? -> 1u }
            GetTextAtPosition =
                staticCFunction {
                    self: CPointer<IDWriteTextAnalysisSource>?,
                    position: UInt,
                    text: CPointer<CPointerVar<UShortVar>>?,
                    length: CPointer<UIntVar>?,
                    ->
                    val total = self.slot(LENGTH_SLOT)
                    val start = self.slot(TEXT_SLOT).toCPointer<UShortVar>()
                    if (position.toLong() < total) {
                        text!![0] = start!! + position.toLong()
                        length!![0] = (total - position.toLong()).convert()
                    } else {
                        text!![0] = null
                        length!![0] = 0u
                    }
                    S_OK
                }
            GetTextBeforePosition =
                staticCFunction {
                    self: CPointer<IDWriteTextAnalysisSource>?,
                    position: UInt,
                    text: CPointer<CPointerVar<UShortVar>>?,
                    length: CPointer<UIntVar>?,
                    ->
                    val total = self.slot(LENGTH_SLOT)
                    if (position > 0u && position.toLong() <= total) {
                        text!![0] = self.slot(TEXT_SLOT).toCPointer()
                        length!![0] = position
                    } else {
                        text!![0] = null
                        length!![0] = 0u
                    }
                    S_OK
                }
            GetParagraphReadingDirection =
                staticCFunction { _: CPointer<IDWriteTextAnalysisSource>? -> DWRITE_READING_DIRECTION_LEFT_TO_RIGHT }
            GetLocaleName =
                staticCFunction {
                    self: CPointer<IDWriteTextAnalysisSource>?,
                    position: UInt,
                    length: CPointer<UIntVar>?,
                    name: CPointer<CPointerVar<UShortVar>>?,
                    ->
                    length!![0] = (self.slot(LENGTH_SLOT) - position.toLong()).coerceAtLeast(0).convert()
                    name!![0] = locale
                    S_OK
                }
            GetNumberSubstitution =
                staticCFunction {
                    self: CPointer<IDWriteTextAnalysisSource>?,
                    position: UInt,
                    length: CPointer<UIntVar>?,
                    substitution: CPointer<CPointerVar<IDWriteNumberSubstitution>>?,
                    ->
                    length!![0] = (self.slot(LENGTH_SLOT) - position.toLong()).coerceAtLeast(0).convert()
                    substitution!![0] = null
                    S_OK
                }
        }.ptr
}

/** Runs [body] with [text] as a DirectWrite text source, valid only until [body] returns. */
internal fun <R> withTextSource(
    text: String,
    body: (source: CPointer<IDWriteTextAnalysisSource>, length: Int) -> R,
): R =
    memScoped {
        val chars = text.wcstr.ptr
        val self = allocArray<LongVar>(3)
        self[0] = vtable.toLong()
        self[TEXT_SLOT] = chars.toLong()
        self[LENGTH_SLOT] = text.length.toLong()
        body(self.reinterpret(), text.length)
    }
