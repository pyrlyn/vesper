package vesper.windows

private const val FILE_SCHEME = "file://"
private const val HEX = 16

/**
 * The Windows path an OSC 7 URI names, or null when it names none on this computer: another
 * scheme, another host (a remote shell's, or a WSL distribution's Linux paths), no drive letter,
 * a broken escape or bytes that are not UTF-8. [computerName] is this computer's NetBIOS name.
 */
fun windowsPath(
    uri: String,
    computerName: String,
): String? {
    if (!uri.startsWith(FILE_SCHEME, ignoreCase = true)) return null
    val rest = uri.substring(FILE_SCHEME.length)
    val slash = rest.indexOf('/')
    if (slash < 0) return null
    val host = rest.substring(0, slash)
    if (host.isNotEmpty() && !host.equals("localhost", ignoreCase = true) && !host.equals(computerName, ignoreCase = true)) return null
    val path = percentDecode(rest.substring(slash)) ?: return null
    // "/C:" or "/C:/…": the drive is the path's first segment.
    if (path.length < 3 || !path[1].isAsciiLetter() || path[2] != ':' || (path.length > 3 && path[3] != '/')) return null
    if (path.any { it < ' ' }) return null
    val drive = path.substring(1).replace('/', '\\')
    return if (drive.length == 2) "$drive\\" else drive
}

private fun Char.isAsciiLetter() = this in 'A'..'Z' || this in 'a'..'z'

/** [text] with its `%XX` escapes turned back into the UTF-8 they encode; null when either is broken. */
private fun percentDecode(text: String): String? {
    val bytes = ArrayList<Byte>(text.length)
    var i = 0
    while (i < text.length) {
        val char = text[i]
        if (char == '%') {
            val byte = text.substring(i + 1, minOf(i + 3, text.length)).takeIf { it.length == 2 }?.toIntOrNull(HEX) ?: return null
            bytes += byte.toByte()
            i += 3
        } else {
            bytes += char.toString().encodeToByteArray().toList()
            i++
        }
    }
    return try {
        bytes.toByteArray().decodeToString(throwOnInvalidSequence = true)
    } catch (_: CharacterCodingException) {
        null
    }
}

/**
 * Whether a session's output is held while the user drags the window's border, as on macOS: the
 * shell hears of one size, the one the drag ends on, rather than reflowing at every step of it.
 * `WM_ENTERSIZEMOVE` starts a move as well as a resize, so only `WM_SIZING` holds the output.
 */
class LiveResize {
    private var inLoop = false

    var held = false
        private set

    /** `WM_ENTERSIZEMOVE`. */
    fun enter() {
        inLoop = true
    }

    /** `WM_SIZING`: true when output should pause now. */
    fun sizing(): Boolean {
        if (!inLoop || held) return false
        held = true
        return true
    }

    /** `WM_EXITSIZEMOVE`: true when the session should take the window's size and resume. */
    fun exit(): Boolean {
        inLoop = false
        val was = held
        held = false
        return was
    }
}
