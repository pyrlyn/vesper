package vesper.config

import com.akuleshov7.ktoml.Toml
import com.akuleshov7.ktoml.TomlInputConfig
import com.akuleshov7.ktoml.parsers.TomlParser
import com.akuleshov7.ktoml.tree.nodes.TomlArrayOfTablesElement
import com.akuleshov7.ktoml.tree.nodes.TomlNode
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

// The file as written, before validation. ktoml refuses keys these classes do not have; every
// range is checked afterwards in Settings, where the message can name the key.

@Serializable
internal data class FileConfig(
    val font: FontSection = FontSection(),
    val colors: ColorsSection = ColorsSection(),
    val scrollback: Long = DEFAULT_SCROLLBACK.toLong(),
)

@Serializable
internal data class FontSection(
    val family: String = "",
    @Serializable(with = Points::class) val size: Double = DEFAULT_FONT_SIZE,
)

@Serializable
internal data class ColorsSection(
    val scheme: String = Scheme.VesperDark.id,
    val foreground: String? = null,
    val background: String? = null,
    val cursor: String? = null,
    val ansi: List<String> = emptyList(),
)

// TOML keeps integers and floats apart, so `size = 13` is an integer; people write sizes that way.
private object Points : KSerializer<Double> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("vesper.config.Points", PrimitiveKind.DOUBLE)

    override fun deserialize(decoder: Decoder): Double =
        try {
            decoder.decodeDouble()
        } catch (notFloat: SerializationException) {
            try {
                decoder.decodeLong().toDouble()
            } catch (_: SerializationException) {
                throw notFloat
            }
        }

    override fun serialize(
        encoder: Encoder,
        value: Double,
    ) = encoder.encodeDouble(value)
}

private val toml = Toml(TomlInputConfig(ignoreUnknownNames = false))

/** Decodes [text], or throws [ConfigException.Invalid] for [origin]. */
internal fun decode(
    text: String,
    origin: String,
): FileConfig =
    try {
        refuseDuplicateKeys(TomlParser(TomlInputConfig()).parseString(text))
        toml.decodeFromString(FileConfig.serializer(), text)
    } catch (e: ConfigException) {
        throw e
    } catch (
        // The text is outside input, so whatever the parser throws on it is a bad file, never a crash.
        e: Exception,
    ) {
        throw ConfigException.Invalid(origin, e.message ?: e::class.simpleName.orEmpty())
    }

// TOML forbids setting a key twice; ktoml would keep the last value, hiding a mistake in the file.
private fun refuseDuplicateKeys(node: TomlNode) {
    val seen = HashSet<String>()
    for (child in node.children) {
        if (child !is TomlArrayOfTablesElement && !seen.add(child.name)) {
            throw SerializationException("Line ${child.lineNo}: `${child.name}` is set twice")
        }
        refuseDuplicateKeys(child)
    }
}
