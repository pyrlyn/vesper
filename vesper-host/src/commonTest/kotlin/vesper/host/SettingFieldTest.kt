package vesper.host

import vesper.config.ConfigException
import vesper.config.Font
import vesper.config.Scheme
import vesper.config.Settings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SettingFieldTest {
    private val writes = mutableListOf<Pair<String, String?>>()
    private val settings = Settings.DEFAULT.copy(font = Font("Menlo", 14.5), scheme = Scheme.SolarizedDark)

    private fun SettingField.commit(text: String) = commit(text, settings) { key, value -> writes += key to value }

    @Test
    fun aFieldShowsItsSetting() {
        assertEquals(
            listOf("Menlo", "14.5", "solarized-dark", Settings.DEFAULT.scrollback.toString()),
            SettingField.entries.map { it.shown(settings) },
        )
        assertEquals("13", SettingField.FontSize.shown(Settings.DEFAULT), "a whole size has no fraction")
    }

    @Test
    fun onlyAChangedValueIsWritten() {
        assertNull(SettingField.FontSize.commit(" 14.5 "))
        assertNull(SettingField.ColorScheme.commit("solarized-dark"))
        assertEquals(emptyList(), writes, "focus leaving an untouched field writes nothing")
        assertNull(SettingField.FontSize.commit("16"))
        assertNull(SettingField.ColorScheme.commit("vesper-light"))
        // An empty family removes the key, which is the platform's font.
        assertNull(SettingField.FontFamily.commit("  "))
        assertEquals(listOf("font.size" to "16", "colors.scheme" to "vesper-light", "font.family" to null), writes)
    }

    @Test
    fun aRefusedValueReturnsTheFilesReason() {
        val refused =
            SettingField.Scrollback.commit(
                "-5",
                settings,
            ) { _, _ -> throw ConfigException.Invalid("/c.toml", "scrollback is 0 to 1000000 rows") }
        assertEquals("/c.toml: scrollback is 0 to 1000000 rows", refused)
    }
}
