package vesper.config

import okio.FileNotFoundException
import okio.FileSystem
import okio.IOException
import okio.Path.Companion.toPath
import okio.SYSTEM
import okio.buffer
import okio.use

/** The largest file read; a config is a few hundred bytes, so anything near this is not one. */
public const val MAX_FILE_BYTES: Int = 64 * 1024

/** The longest font family name, in UTF-8 bytes. */
public const val MAX_FAMILY_BYTES: Int = 128

/** The smallest font size, in points. */
public const val MIN_FONT_SIZE: Double = 4.0

/** The largest font size, in points. */
public const val MAX_FONT_SIZE: Double = 200.0

/** The most history rows the file may ask for; the core clamps further to what its ring holds. */
public const val MAX_SCROLLBACK: Int = 1_000_000

/** The palette entries the file may override: colours 0 to 15. */
public const val MAX_ANSI_OVERRIDES: Int = 16

internal const val DEFAULT_FONT_SIZE = 13.0
internal const val DEFAULT_SCROLLBACK = 10_000

/** The font a host asks the platform for; an empty [family] is the platform's monospace font. */
public data class Font(
    val family: String,
    val size: Double,
)

/** Everything the file decides, with defaults filled in. Nothing here names a toolkit type. */
public data class Settings(
    val font: Font,
    /** The scheme the colours start from, which a settings view shows. */
    val scheme: Scheme,
    val colors: Theme,
    /** History rows for terminals opened from now on. */
    val scrollback: Int,
) {
    public companion object {
        /** What a fresh install, with no file, runs with. */
        public val DEFAULT: Settings = resolve(FileConfig(), "")

        /** Parses and validates [text]; [origin] only names the file in errors. */
        public fun parse(
            text: String,
            origin: String,
        ): Settings {
            if (text.encodedLength() > MAX_FILE_BYTES) throw ConfigException.TooLarge(origin)
            return resolve(decode(text, origin), origin)
        }

        /** Reads [path]. A missing file is not an error: it is [DEFAULT]. */
        public fun load(path: String): Settings = readCapped(path)?.let { parse(it, path) } ?: DEFAULT
    }
}

/** The file's text, never more than [MAX_FILE_BYTES] of it in memory, or `null` if it is missing. */
internal fun readCapped(path: String): String? {
    val bytes =
        try {
            FileSystem.SYSTEM.source(path.toPath()).buffer().use { source ->
                // Buffers one byte past the cap at most, however large the file is.
                if (source.request(MAX_FILE_BYTES + 1L)) throw ConfigException.TooLarge(path)
                source.readByteArray()
            }
        } catch (_: FileNotFoundException) {
            return null
        } catch (e: IOException) {
            throw ConfigException.Io(path, e.message ?: "cannot be read", e)
        }
    return try {
        bytes.decodeToString(throwOnInvalidSequence = true)
    } catch (_: CharacterCodingException) {
        throw ConfigException.Invalid(path, "not valid UTF-8")
    }
}

private fun String.encodedLength(): Int = encodeToByteArray().size

private fun resolve(
    file: FileConfig,
    origin: String,
): Settings {
    fun invalid(detail: String): Nothing = throw ConfigException.Invalid(origin, detail)

    fun color(
        key: String,
        text: String,
    ): Rgb = Rgb.parse(text) ?: invalid("$key `$text` is not a colour like #1a2b3c")

    val family = file.font.family
    if (family.encodedLength() > MAX_FAMILY_BYTES || family.any(Char::isISOControl)) {
        invalid("font.family is at most $MAX_FAMILY_BYTES bytes with no control characters")
    }
    val size = file.font.size
    // Written as a negated range so NaN fails too.
    if (!(size >= MIN_FONT_SIZE && size <= MAX_FONT_SIZE)) {
        invalid("font.size is $MIN_FONT_SIZE to $MAX_FONT_SIZE points, not $size")
    }
    if (file.scrollback !in 0..MAX_SCROLLBACK) invalid("scrollback is 0 to $MAX_SCROLLBACK rows, not ${file.scrollback}")

    val c = file.colors
    val scheme =
        Scheme.byId(c.scheme)
            ?: invalid("colors.scheme `${c.scheme}` is not one of ${Scheme.entries.joinToString { it.id }}")
    if (c.ansi.size > MAX_ANSI_OVERRIDES) invalid("colors.ansi has at most $MAX_ANSI_OVERRIDES colours, not ${c.ansi.size}")
    val base = scheme.theme
    val ansi = base.ansi.toMutableList()
    c.ansi.forEachIndexed { i, text -> ansi[i] = color("colors.ansi[$i]", text) }
    val colors =
        Theme(
            foreground = c.foreground?.let { color("colors.foreground", it) } ?: base.foreground,
            background = c.background?.let { color("colors.background", it) } ?: base.background,
            cursor = c.cursor?.let { color("colors.cursor", it) } ?: base.cursor,
            ansi = ansi,
        )
    return Settings(Font(family, size), scheme, colors, file.scrollback.toInt())
}
