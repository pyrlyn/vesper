package vesper.macos

import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.value
import platform.CoreFoundation.CFArrayCreate
import platform.CoreFoundation.CFDataCreateWithBytesNoCopy
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionarySetValue
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFAllocatorNull
import platform.CoreFoundation.kCFTypeArrayCallBacks
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.CoreText.CTFontCreateCopyWithAttributes
import platform.CoreText.CTFontDescriptorCreateWithAttributes
import platform.CoreText.CTFontDescriptorRef
import platform.CoreText.CTFontManagerCreateFontDescriptorFromData
import platform.CoreText.CTFontRef
import platform.CoreText.kCTFontCascadeListAttribute
import vesper.macos.macho._dyld_get_image_header
import vesper.macos.macho.getsectiondata

/** Where the build puts Symbols Nerd Font Mono (see `vesper-macos/build.gradle.kts`). */
private const val SEGMENT = "__TEXT"
private const val SECTION = "__nerd_symbols"

/**
 * Symbols Nerd Font Mono, read from the running binary: Powerline extras and the Nerd Font icons
 * prompts print from the Private Use Area. Null when the binary was linked without it; the system
 * fallback then draws what it can.
 */
internal val bundledSymbols: CTFontDescriptorRef? by lazy {
    memScoped {
        val size = alloc<ULongVar>()
        // Image 0 is the executable itself, the app or a test binary alike.
        val bytes = getsectiondata(_dyld_get_image_header(0u)?.reinterpret(), SEGMENT, SECTION, size.ptr)
        if (bytes == null || size.value == 0uL) return@memScoped null
        // The section lives as long as the process, so CoreText may read it in place.
        val data = CFDataCreateWithBytesNoCopy(null, bytes, size.value.toLong(), kCFAllocatorNull) ?: return@memScoped null
        CTFontManagerCreateFontDescriptorFromData(data).also { CFRelease(data) }
    }
}

/**
 * [font] with [fallback] ahead of the system's fallback fonts, so a character [font] lacks is looked
 * for there first. [font] itself when there is no [fallback].
 */
internal fun withFallback(
    font: CTFontRef?,
    fallback: CTFontDescriptorRef?,
): CTFontRef? {
    if (font == null || fallback == null) return font
    return memScoped {
        val item = alloc<CFTypeRefVar>().apply { value = fallback }
        val list = CFArrayCreate(null, item.ptr, 1, kCFTypeArrayCallBacks.ptr)
        val attributes = CFDictionaryCreateMutable(null, 1, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr)
        CFDictionarySetValue(attributes, kCTFontCascadeListAttribute, list)
        val descriptor = CTFontDescriptorCreateWithAttributes(attributes)
        // Size 0 keeps the font's own.
        val copy = CTFontCreateCopyWithAttributes(font, 0.0, null, descriptor)
        CFRelease(descriptor)
        CFRelease(attributes)
        CFRelease(list)
        copy ?: font
    }
}
