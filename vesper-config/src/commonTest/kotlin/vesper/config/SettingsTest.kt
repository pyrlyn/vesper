package vesper.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val FILE = "/home/me/.config/vesper/config.toml"

private fun parse(text: String) = Settings.parse(text, FILE)

private inline fun <reified E : ConfigException> refused(
    text: String,
    vararg fragments: String,
) {
    val error = assertFailsWith<E>(text) { parse(text) }
    val message = error.message.orEmpty()
    assertTrue(message.startsWith("$FILE: "), message)
    for (fragment in fragments) assertTrue(fragment in message, "`$fragment` not in: $message")
}

class SettingsTest {
    @Test
    fun anEmptyFileIsTheDefaults() {
        val settings = parse("")
        assertEquals(Settings.DEFAULT, settings)
        assertEquals(Font("", 13.0), settings.font)
        assertEquals(Scheme.VesperDark, settings.scheme)
        assertEquals(Scheme.VesperDark.theme, settings.colors)
        assertEquals(10_000, settings.scrollback)
    }

    @Test
    fun everyKeyIsReadAndOverridesTheScheme() {
        val settings =
            parse(
                """
                # a comment
                scrollback = 500

                [font]
                family = "JetBrains Mono"
                size = 15.5

                [colors]
                scheme = "solarized-dark"
                foreground = "#AABBCC"
                cursor = "#010203"
                ansi = ["#000001", "#000002"]
                """.trimIndent(),
            )
        assertEquals(Font("JetBrains Mono", 15.5), settings.font)
        assertEquals(500, settings.scrollback)
        assertEquals(Scheme.SolarizedDark, settings.scheme)
        val solarized = Scheme.SolarizedDark.theme
        assertEquals(Rgb.of(0xAABBCC), settings.colors.foreground)
        assertEquals(solarized.background, settings.colors.background)
        assertEquals(Rgb.of(0x010203), settings.colors.cursor)
        // Fewer than 16 overrides keep the scheme's remaining entries.
        assertEquals(listOf(Rgb.of(1), Rgb.of(2)) + solarized.ansi.drop(2), settings.colors.ansi)
    }

    @Test
    fun anIntegerFontSizeIsPoints() {
        assertEquals(14.0, parse("[font]\nsize = 14").font.size)
        assertEquals(Font("", 4.0), parse("[font]\nsize = 4").font)
        assertEquals(200.0, parse("[font]\nsize = 200.0").font.size)
    }

    @Test
    fun keysTheSchemaDoesNotHaveAreRefused() {
        refused<ConfigException.Invalid>("bogus = 1", "bogus")
        refused<ConfigException.Invalid>("[font]\nsiz = 13", "siz")
        refused<ConfigException.Invalid>("[window]\nwidth = 1", "window")
    }

    @Test
    fun aKeySetTwiceIsRefused() {
        refused<ConfigException.Invalid>("scrollback = 1\nscrollback = 2", "Line 2", "`scrollback` is set twice")
        refused<ConfigException.Invalid>("[font]\nsize = 12\n[font]\nsize = 14", "`size` is set twice")
    }

    @Test
    fun malformedTomlAndWrongTypesAreRefused() {
        refused<ConfigException.Invalid>("[font\nsize = 1", "Line 1")
        refused<ConfigException.Invalid>("scrollback = \"many\"", "scrollback")
        refused<ConfigException.Invalid>("[font]\nfamily = 3", "family")
        refused<ConfigException.Invalid>("[font]\nsize = \"big\"", "size")
    }

    @Test
    fun theFontFamilyIsCapped() {
        assertEquals("a".repeat(MAX_FAMILY_BYTES), parse("[font]\nfamily = \"${"a".repeat(MAX_FAMILY_BYTES)}\"").font.family)
        refused<ConfigException.Invalid>("[font]\nfamily = \"${"a".repeat(MAX_FAMILY_BYTES + 1)}\"", "font.family", "128 bytes")
        // The cap counts bytes: 43 three-byte characters are 129 of them.
        refused<ConfigException.Invalid>("[font]\nfamily = \"${"\u4e2d".repeat(43)}\"", "font.family")
        refused<ConfigException.Invalid>("[font]\nfamily = \"a\\tb\"", "control characters")
    }

    @Test
    fun theFontSizeHasARange() {
        for (size in listOf("3.9", "200.5", "0", "-13", "inf", "nan")) {
            refused<ConfigException.Invalid>("[font]\nsize = $size", "font.size is 4.0 to 200.0 points")
        }
    }

    @Test
    fun scrollbackHasARange() {
        assertEquals(0, parse("scrollback = 0").scrollback)
        assertEquals(MAX_SCROLLBACK, parse("scrollback = $MAX_SCROLLBACK").scrollback)
        refused<ConfigException.Invalid>("scrollback = ${MAX_SCROLLBACK + 1}", "scrollback is 0 to 1000000 rows, not 1000001")
        refused<ConfigException.Invalid>("scrollback = -1", "not -1")
        refused<ConfigException.Invalid>("scrollback = 99999999999", "not 99999999999")
    }

    @Test
    fun schemesAndColoursMustBeKnownAndWellFormed() {
        for (scheme in Scheme.entries) assertEquals(scheme, parse("[colors]\nscheme = \"${scheme.id}\"").scheme)
        refused<ConfigException.Invalid>(
            "[colors]\nscheme = \"scull-dark\"",
            "`scull-dark` is not one of vesper-dark, vesper-light, solarized-dark",
        )
        for (bad in listOf("123456", "#12345", "#1234567", "#12345g", "#+12345", "#-12345", "")) {
            refused<ConfigException.Invalid>("[colors]\nbackground = \"$bad\"", "colors.background `$bad` is not a colour")
        }
        refused<ConfigException.Invalid>("[colors]\nansi = [\"#000000\", \"red\"]", "colors.ansi[1] `red`")
        val seventeen = List(MAX_ANSI_OVERRIDES + 1) { "\"#000000\"" }.joinToString()
        refused<ConfigException.Invalid>("[colors]\nansi = [$seventeen]", "at most 16 colours, not 17")
    }

    @Test
    fun aFileOverTheCapIsRefusedBeforeParsing() {
        val padding = "#".repeat(MAX_FILE_BYTES)
        refused<ConfigException.TooLarge>("$padding\n", "larger than $MAX_FILE_BYTES bytes")
        // Just under the cap is still a file.
        assertEquals(Settings.DEFAULT, parse("#".repeat(MAX_FILE_BYTES - 1) + "\n"))
    }

    @Test
    fun rgbPrintsAsTheFileSpellsIt() {
        assertEquals("#0a0b0c", Rgb.of(0x0A0B0C).toString())
        assertEquals(Rgb.of(0x123456), Rgb.of(0x7F123456))
        assertEquals(Rgb.of(0xABCDEF), Rgb.parse("#AbCdEf"))
        assertNull(Scheme.byId("Vesper-Dark"))
        for (scheme in Scheme.entries) assertEquals(16, scheme.theme.ansi.size)
    }

    @Test
    fun theDefaultPathFollowsEachPlatform() {
        fun unix(vararg vars: Pair<String, String>) = defaultConfigPath(false, mapOf(*vars)::get)
        assertEquals("/x/vesper/config.toml", unix("XDG_CONFIG_HOME" to "/x", "HOME" to "/h"))
        assertEquals("/h/.config/vesper/config.toml", unix("HOME" to "/h"))
        assertEquals("/h/.config/vesper/config.toml", unix("XDG_CONFIG_HOME" to "rel", "HOME" to "/h"))
        assertEquals("/h/.config/vesper/config.toml", unix("XDG_CONFIG_HOME" to "", "HOME" to "/h"))
        assertNull(unix("XDG_CONFIG_HOME" to "rel", "HOME" to "rel"))
        assertNull(unix())
        val appData = mapOf("APPDATA" to "C:\\Users\\me\\AppData\\Roaming", "HOME" to "/h")
        assertEquals("C:\\Users\\me\\AppData\\Roaming\\vesper\\config.toml", defaultConfigPath(true, appData::get))
        assertNull(defaultConfigPath(true, mapOf("HOME" to "/h")::get))
        assertNull(defaultConfigPath(true, mapOf("APPDATA" to "Roaming")::get))
    }
}
