package vesper.host

import vesper.config.ConfigException
import vesper.config.Settings

/**
 * A setting both hosts' settings windows edit, written to the config file under [key] the moment
 * its field is committed; [label] is the text beside the field.
 */
public enum class SettingField(
    public val key: String,
    public val label: String,
) {
    FontFamily("font.family", "Font family:"),
    FontSize("font.size", "Font size:"),
    ColorScheme("colors.scheme", "Colour scheme:"),
    Scrollback("scrollback", "Scrollback lines:"),
    ;

    /** The field's text for [settings]. */
    public fun shown(settings: Settings): String =
        when (this) {
            FontFamily -> settings.font.family
            FontSize -> settings.font.size.let { if (it % 1.0 == 0.0) it.toLong().toString() else it.toString() }
            ColorScheme -> settings.scheme.id
            Scrollback -> settings.scrollback.toString()
        }

    /**
     * Writes [text] through [set] (`LiveConfig.set`), or nothing when it is what [current] already
     * shows, since a field commits every time focus leaves it. Returns why the file refused the
     * value, or null when it took it.
     */
    public fun commit(
        text: String,
        current: Settings,
        set: (key: String, value: String?) -> Unit,
    ): String? {
        val value = text.trim()
        if (value == shown(current)) return null
        return try {
            // An empty family is the platform's monospace font, which the file says by leaving the key out.
            set(key, if (this == FontFamily) value.ifEmpty { null } else value)
            null
        } catch (e: ConfigException) {
            e.message.orEmpty()
        }
    }
}
