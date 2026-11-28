package vesper.config

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.SYSTEM
import vesper.harness.TESTS_DIR_VARIABLE
import vesper.harness.environment
import kotlin.test.Test
import kotlin.test.assertEquals

// docs/config.schema.json, which editors validate the file with, rebuilt from FileConfig's shape and
// the limits Settings enforces. A key without an entry here fails, and so does a committed file
// that drifted from the model.

private const val WRITE_VARIABLE = "VESPER_WRITE_SCHEMA"

// The $defs entry a property's type is written as, and the property's own description.
private class Slot(
    val def: String,
    val description: String? = null,
)

private val slots =
    mapOf(
        "" to Slot("Config"),
        "font" to Slot("FontSection"),
        "font.family" to Slot("Family", "Family name; empty for the platform's monospace font."),
        "font.size" to Slot("FontSize", "Size in points."),
        "colors" to Slot("ColorsSection"),
        "colors.scheme" to Slot("Scheme", "The built-in scheme everything below overrides."),
        "colors.foreground" to Slot("Rgb", "Default text colour."),
        "colors.background" to Slot("Rgb", "Default background colour."),
        "colors.cursor" to Slot("Rgb", "Cursor colour."),
        "colors.ansi" to Slot("AnsiOverrides", "Palette entries 0 to 15, from the first; fewer than 16 keeps the rest."),
        "colors.ansi[]" to Slot("Rgb"),
        "scrollback" to Slot("Scrollback", "History rows kept per terminal; applies to terminals opened afterwards."),
        "keybind" to Slot("Keybinds", "Key bindings laid over the defaults."),
        "keybind[]" to Slot("Entry"),
        "keybind[].key" to Slot("Chord", "The chord that triggers the action."),
        "keybind[].action" to Slot("Action", "The action; `none` frees a default chord."),
    )

private val containerDescriptions =
    mapOf(
        "Config" to "The file as written; anything it leaves out is the default.",
        "FontSection" to "`[font]`.",
        "ColorsSection" to "`[colors]`.",
        "AnsiOverrides" to "The first colours of the palette, replacing the scheme's.",
        "Keybinds" to "The `[[keybind]]` list, checked as a whole.",
        "Entry" to "One `[[keybind]]` table.",
    )

private val maxItems = mapOf("AnsiOverrides" to MAX_ANSI_OVERRIDES, "Keybinds" to MAX_KEYBINDS)

private val schemeDescriptions =
    mapOf(
        Scheme.VesperDark to "Light text on near-black; the xterm palette.",
        Scheme.VesperLight to "Dark text on near-white.",
        Scheme.SolarizedDark to "Ethan Schoonover's Solarized, dark variant.",
    )

private val actionDescriptions =
    mapOf(
        Action.None to "Removes a default binding of the same chord.",
        Action.Paste to "Paste the clipboard.",
        Action.FontLarger to "Make the font one point larger.",
        Action.FontSmaller to "Make the font one point smaller.",
        Action.FontReset to "Back to the configured font size.",
        Action.ScrollPageUp to "Scroll the history one screen up.",
        Action.ScrollPageDown to "Scroll the history one screen down.",
        Action.ScrollToTop to "Scroll to the oldest history row.",
        Action.ScrollToBottom to "Scroll back to the screen.",
        Action.PreviousPrompt to "Scroll to the previous shell prompt (OSC 133 `A`).",
        Action.NextPrompt to "Scroll to the next shell prompt.",
    )

private fun <E : Enum<E>> oneOf(
    description: String,
    entries: List<E>,
    id: (E) -> String,
    descriptions: Map<E, String>,
) = buildJsonObject {
    put("description", description)
    putJsonArray("oneOf") {
        for (entry in entries) {
            addJsonObject {
                put("description", descriptions[entry] ?: error("$entry has no schema description"))
                put("type", "string")
                put("const", id(entry))
            }
        }
    }
}

// The descriptor says only "string" or "number"; what a string may hold comes from Settings.
private val leaves: Map<String, JsonObject> =
    mapOf(
        "Family" to
            buildJsonObject {
                put("description", "A font family name: empty for the platform's monospace font.")
                put("type", "string")
                put("maxLength", MAX_FAMILY_BYTES)
            },
        "FontSize" to
            buildJsonObject {
                put("description", "A font size in points.")
                put("type", "number")
                put("minimum", MIN_FONT_SIZE)
                put("maximum", MAX_FONT_SIZE)
            },
        "Scrollback" to
            buildJsonObject {
                put("description", "History rows to keep.")
                put("type", "integer")
                put("minimum", 0)
                put("maximum", MAX_SCROLLBACK)
            },
        "Rgb" to
            buildJsonObject {
                put("description", "A colour as `#rrggbb`.")
                put("type", "string")
                put("pattern", "^#[0-9a-fA-F]{6}$")
            },
        "Chord" to
            buildJsonObject {
                put(
                    "description",
                    "Modifiers (${MOD_NAMES.joinToString { it.first }}) and one key joined by `+`: a character, " +
                        "f1 to f$LAST_F_KEY, or one of ${KEY_NAMES.joinToString { it.first }}. " +
                        "A character needs ctrl, alt or super.",
                )
                put("type", "string")
                put("maxLength", MAX_CHORD_LENGTH)
            },
        "Scheme" to oneOf("The built-in colour schemes.", Scheme.entries, Scheme::id, schemeDescriptions),
        "Action" to oneOf("What a binding does.", Action.entries, Action::id, actionDescriptions),
    )

@OptIn(ExperimentalSerializationApi::class)
private class Renderer {
    val defs = LinkedHashMap<String, JsonObject>()
    val visited = HashSet<String>()

    fun slot(path: String): Slot {
        visited += path
        return slots[path] ?: error("`$path` has no schema entry; add it to SchemaTest")
    }

    /** Registers the type at [path] under `$defs` and returns the reference to it. */
    fun ref(
        path: String,
        descriptor: SerialDescriptor,
        default: JsonElement?,
    ): String {
        val name = slot(path).def
        if (name !in defs) {
            defs[name] =
                when (descriptor.kind) {
                    StructureKind.CLASS -> JsonObject(objectSchema(path, descriptor, default))
                    StructureKind.LIST -> arraySchema(path, descriptor)
                    else -> leaves[name] ?: error("`$name` has no schema")
                }
        }
        return "#/\$defs/$name"
    }

    fun objectSchema(
        path: String,
        descriptor: SerialDescriptor,
        default: JsonElement?,
    ): Map<String, JsonElement> =
        buildJsonObject {
            put("description", containerDescriptions.getValue(slot(path).def))
            put("type", "object")
            putJsonObject("properties") {
                for (i in 0 until descriptor.elementsCount) {
                    val name = descriptor.getElementName(i)
                    val child = if (path.isEmpty()) name else "$path.$name"
                    val childDefault = (default as? JsonObject)?.get(name)
                    putJsonObject(name) {
                        slot(child).description?.let { put("description", it) }
                        put("\$ref", ref(child, descriptor.getElementDescriptor(i), childDefault))
                        if (descriptor.isElementOptional(i)) childDefault?.let { put("default", it) }
                    }
                }
            }
            put("additionalProperties", false)
            val required = (0 until descriptor.elementsCount).filterNot(descriptor::isElementOptional)
            if (required.isNotEmpty()) putJsonArray("required") { required.forEach { add(descriptor.getElementName(it)) } }
        }

    fun arraySchema(
        path: String,
        descriptor: SerialDescriptor,
    ): JsonObject {
        val name = slot(path).def
        return buildJsonObject {
            put("description", containerDescriptions.getValue(name))
            put("type", "array")
            putJsonObject("items") { put("\$ref", ref("$path[]", descriptor.getElementDescriptor(0), null)) }
            put("maxItems", maxItems.getValue(name))
        }
    }
}

@OptIn(ExperimentalSerializationApi::class)
private val pretty =
    Json {
        prettyPrint = true
        prettyPrintIndent = "  "
    }

// Null defaults are "the scheme's colour", which the schema says by leaving the default out.
@OptIn(ExperimentalSerializationApi::class)
private val defaults =
    Json {
        encodeDefaults = true
        explicitNulls = false
    }

private fun renderSchema(): Pair<String, Set<String>> {
    val renderer = Renderer()
    val root =
        renderer.objectSchema(
            "",
            FileConfig.serializer().descriptor,
            defaults.encodeToJsonElement(FileConfig.serializer(), FileConfig()),
        )
    val schema =
        buildJsonObject {
            put("\$schema", "https://json-schema.org/draft/2020-12/schema")
            put("title", "Config")
            root.forEach { (key, value) -> put(key, value) }
            put(
                "\$defs",
                JsonObject(
                    renderer.defs.entries
                        .sortedBy { it.key }
                        .associate { it.key to it.value },
                ),
            )
        }
    return pretty.encodeToString(JsonObject.serializer(), schema) + "\n" to renderer.visited
}

class SchemaTest {
    @Test
    fun theCommittedSchemaMatchesTheModel() {
        val tests = checkNotNull(environment(TESTS_DIR_VARIABLE)) { "$TESTS_DIR_VARIABLE is not set; run the tests through Gradle" }
        val path = tests.toPath().parent!! / "docs" / "config.schema.json"
        val (rendered, _) = renderSchema()
        if (environment(WRITE_VARIABLE) != null) {
            FileSystem.SYSTEM.write(path) { writeUtf8(rendered) }
            return
        }
        val committed = FileSystem.SYSTEM.read(path) { readUtf8() }
        assertEquals(rendered, committed, "docs/config.schema.json is stale: run the tests once with $WRITE_VARIABLE=1")
    }

    @Test
    fun everySchemaEntryIsAKeyTheModelHas() {
        assertEquals(slots.keys, renderSchema().second)
    }
}
