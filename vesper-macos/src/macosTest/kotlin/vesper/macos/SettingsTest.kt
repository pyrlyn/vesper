package vesper.macos

import okio.FileSystem
import okio.Path.Companion.toPath
import okio.SYSTEM
import platform.AppKit.NSApplication
import platform.AppKit.NSBackingStoreBuffered
import platform.AppKit.NSEventModifierFlagCommand
import platform.AppKit.NSWindow
import platform.AppKit.NSWindowStyleMaskBorderless
import platform.CoreText.CTFontCopyFamilyName
import platform.Foundation.CFBridgingRelease
import platform.Foundation.NSMakeRect
import vesper.config.Font
import vesper.config.LiveConfig
import vesper.config.Scheme
import vesper.config.Settings
import vesper.host.Cells
import vesper.host.viewSize
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val KEY_EQUAL: UShort = 0x18u
private const val KEY_ZERO: UShort = 0x1Du

class SettingsTest {
    private val fs = FileSystem.SYSTEM
    private val dir = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "vesper-settings-${Random.nextLong().toULong()}"
    private val path = dir / "config.toml"
    private val live = LiveConfig(path.toString()) {}

    @AfterTest
    fun cleanUp() {
        live.close()
        fs.deleteRecursively(dir)
    }

    private fun file() = fs.read(path) { readUtf8() }

    @Test
    fun theWindowWritesEachCommittedFieldAndRefusesBadOnes() {
        NSApplication.sharedApplication
        val settings = SettingsWindow(live)
        settings.size.stringValue = "15"
        settings.commit(settings.size)
        settings.scheme.selectItemWithTitle(Scheme.SolarizedDark.id)
        settings.commit(settings.scheme)
        assertEquals("[font]\nsize = 15.0\n\n[colors]\nscheme = \"solarized-dark\"\n", file())
        assertEquals(Scheme.SolarizedDark, live.snapshot().settings.scheme)
        assertEquals("", settings.status.stringValue)

        settings.scrollback.stringValue = "-5"
        settings.commit(settings.scrollback)
        assertTrue("scrollback" in settings.status.stringValue, settings.status.stringValue)
        assertEquals(Settings.DEFAULT.scrollback, live.snapshot().settings.scrollback, "a refused value changes nothing")

        settings.family.stringValue = "Menlo"
        settings.commit(settings.family)
        settings.family.stringValue = "  "
        settings.commit(settings.family)
        assertEquals(
            "",
            live
                .snapshot()
                .settings.font.family,
            "an empty family is the system's font again",
        )
        settings.show(live.snapshot())
        assertEquals("15", settings.size.stringValue)
    }

    @Test
    fun aFamilyThisMacLacksFallsBackToTheFixedPitchFont() {
        val menlo = TextStyle(Font("Menlo", 14.0))
        assertEquals("Menlo", CFBridgingRelease(CTFontCopyFamilyName(menlo.faces[0])) as? String)
        assertEquals(familyOf(TextStyle()), familyOf(TextStyle(Font("No Such Font Anywhere", 13.0))))
    }

    @Test
    fun theViewTakesSettingsLiveAndItsBindingsZoom() {
        NSApplication.sharedApplication
        val text = TextStyle()
        val (width, height) = viewSize(Cells(20, 4), text.cell)
        val window = NSWindow(NSMakeRect(0.0, 0.0, width, height), NSWindowStyleMaskBorderless, NSBackingStoreBuffered, false)
        val view = TerminalView(text, NSMakeRect(0.0, 0.0, width, height))
        window.contentView = view
        window.makeFirstResponder(view)
        var cells = 0
        view.onCellSize = { cells++ }

        view.apply(Settings.DEFAULT.copy(font = Font("", 20.0)))
        assertEquals(20.0, view.text.font.size)
        assertTrue(view.text.cell.height > text.cell.height)
        assertTrue(view.performKeyEquivalent(keyEvent(KEY_EQUAL, "=", NSEventModifierFlagCommand)))
        assertEquals(21.0, view.text.font.size)
        view.apply(Settings.DEFAULT.copy(font = Font("", 20.0), scheme = Scheme.VesperLight, colors = Scheme.VesperLight.theme))
        assertEquals(21.0, view.text.font.size, "a colour change keeps the zoom")
        assertTrue(view.performKeyEquivalent(keyEvent(KEY_ZERO, "0", NSEventModifierFlagCommand)))
        assertEquals(20.0, view.text.font.size, "reset goes to the configured size")
        assertEquals(3, cells)
    }

    private fun familyOf(text: TextStyle) = CFBridgingRelease(CTFontCopyFamilyName(text.faces[0])) as? String
}
