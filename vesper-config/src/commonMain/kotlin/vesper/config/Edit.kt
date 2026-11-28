package vesper.config

import com.akuleshov7.ktoml.TomlInputConfig
import com.akuleshov7.ktoml.parsers.TomlParser
import com.akuleshov7.ktoml.tree.nodes.TableType
import com.akuleshov7.ktoml.tree.nodes.TomlFile
import com.akuleshov7.ktoml.tree.nodes.TomlKeyValuePrimitive
import com.akuleshov7.ktoml.tree.nodes.TomlNode
import com.akuleshov7.ktoml.tree.nodes.TomlTable
import okio.FileSystem
import okio.IOException
import okio.Path.Companion.toPath
import okio.SYSTEM
import kotlin.random.Random

// Changing one setting in the file on disk, for a settings view. Only the line of that key changes:
// the rest of the file, comments included, stays as the user wrote it.

private enum class Kind { Text, Number, Integer }

// A fixed list, so a view cannot write a key the file does not have, nor a key bindings table.
private val EDITABLE =
    mapOf(
        "font.family" to Kind.Text,
        "font.size" to Kind.Number,
        "colors.scheme" to Kind.Text,
        "colors.foreground" to Kind.Text,
        "colors.background" to Kind.Text,
        "colors.cursor" to Kind.Text,
        "scrollback" to Kind.Integer,
    )

private const val UNICODE_ESCAPE_DIGITS = 4
private const val HEX = 16
private const val DEL = 0x7F

/**
 * Sets [key] (`section.name` or a top-level name) to [value] in the file at [path], or removes it
 * when [value] is null so its default applies again. The whole result is validated before anything
 * is written, so a refused edit leaves the file as it was. Returns the settings the file now holds.
 *
 * @throws ConfigException for a key that cannot be edited, a value the file would not accept, a file
 *   that is not valid TOML, or a failed write.
 */
public fun editConfig(
    path: String,
    key: String,
    value: String?,
): Settings {
    fun invalid(detail: String): Nothing = throw ConfigException.Invalid(path, detail)

    val kind = EDITABLE[key] ?: invalid("`$key` is not a setting that can be changed")
    val source = readCapped(path).orEmpty()
    val tree =
        try {
            TomlParser(TomlInputConfig()).parseString(source)
        } catch (
            // The file is outside input: whatever the parser throws on it means it cannot be edited.
            e: Exception,
        ) {
            invalid(e.message ?: "not valid TOML")
        }
    val crlf = "\r\n" in source
    val lines = source.replace("\r\n", "\n").split('\n').toMutableList()
    // A trailing newline splits into a last empty element; it comes back when the lines are joined.
    if (lines.last().isEmpty()) lines.removeAt(lines.lastIndex)

    val section = key.substringBefore('.', missingDelimiterValue = "").ifEmpty { null }
    val name = key.substringAfter('.')
    val table: TomlNode? =
        if (section == null) {
            tree
        } else {
            tree.children.firstOrNull { it is TomlTable && it.type == TableType.PRIMITIVE && it.name == section }
        }
    val line = value?.let { "$name = ${render(kind, it) ?: invalid("`$it` is not a valid $key")}" }
    val existing = table?.children?.firstOrNull { it is TomlKeyValuePrimitive && it.name == name }
    when {
        existing != null -> {
            val at = existing.lineNo - 1
            // The parser's line numbers are checked against the text, so an edit never lands elsewhere.
            if (!lines.getOrNull(at).orEmpty().startsKey(name)) invalid("cannot find the line of `$key`; edit the file by hand")
            if (line == null) lines.removeAt(at) else lines[at] = line
        }

        line == null -> {
            return Settings.parse(source, path)
        }

        section == null -> {
            lines.add(firstTableLine(tree) ?: lines.size, line)
        }

        table == null -> {
            if (lines.isNotEmpty() && lines.last().isNotBlank()) lines += ""
            lines += "[$section]"
            lines += line
        }

        else -> {
            val header = table.lineNo - 1
            if (!lines.getOrNull(header).orEmpty().isHeaderOf(section)) {
                invalid("`$section` is not written as a `[$section]` table; edit the file by hand")
            }
            lines.add(header + 1, line)
        }
    }
    val candidate = lines.joinToString(if (crlf) "\r\n" else "\n", postfix = if (crlf) "\r\n" else "\n")
    val settings = Settings.parse(candidate, path)
    writeAtomically(path, candidate)
    return settings
}

// New top-level keys go above the first table, where TOML reads them as top-level.
private fun firstTableLine(tree: TomlFile): Int? =
    tree.children
        .filter { it is TomlTable && !it.isSynthetic }
        .minOfOrNull { it.lineNo - 1 }

private fun String.startsKey(name: String): Boolean =
    Regex("""^\s*(${Regex.escape(name)}|"${Regex.escape(name)}")\s*=""").containsMatchIn(this)

private fun String.isHeaderOf(section: String): Boolean = Regex("""^\s*\[\s*${Regex.escape(section)}\s*]""").containsMatchIn(this)

private fun render(
    kind: Kind,
    text: String,
): String? =
    when (kind) {
        Kind.Text -> {
            basicString(text)
        }

        // Kotlin writes a finite Double as TOML reads it: 15.5, 1.0E20.
        Kind.Number -> {
            text
                .trim()
                .toDoubleOrNull()
                ?.takeIf { it.isFinite() }
                ?.toString()
        }

        Kind.Integer -> {
            text.trim().toLongOrNull()?.toString()
        }
    }

private fun basicString(text: String): String =
    buildString {
        append('"')
        for (c in text) {
            when {
                c == '"' || c == '\\' -> append('\\').append(c)
                c.code < ' '.code || c.code == DEL -> append("\\u").append(c.code.toString(HEX).padStart(UNICODE_ESCAPE_DIGITS, '0'))
                else -> append(c)
            }
        }
        append('"')
    }

/**
 * Replaces the file in one rename, so a reader or a crash never sees half of it. A symlinked file,
 * as dotfile managers make, is written through rather than replaced by a plain file.
 */
internal fun writeAtomically(
    path: String,
    text: String,
) {
    val fs = FileSystem.SYSTEM
    val given = path.toPath()
    val target =
        try {
            fs.canonicalize(given)
        } catch (_: IOException) {
            // A file that does not exist yet has no canonical form; it is created where it was named.
            given
        }
    val dir = target.parent ?: throw ConfigException.Io(path, "has no directory")
    val temp = dir / ".${target.name}.${Random.nextLong().toULong().toString(HEX)}.tmp"
    try {
        fs.createDirectories(dir)
        fs.write(temp) { writeUtf8(text) }
        fs.atomicMove(temp, target)
    } catch (e: IOException) {
        runCatching { fs.delete(temp, mustExist = false) }
        throw ConfigException.Io(path, e.message ?: "cannot be written", e)
    }
}
