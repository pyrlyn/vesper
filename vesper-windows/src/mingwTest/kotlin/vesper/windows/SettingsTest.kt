package vesper.windows

import kotlinx.cinterop.convert
import kotlinx.cinterop.toLong
import okio.FileSystem
import okio.Path
import platform.windows.CBN_SELCHANGE
import platform.windows.CB_SETCURSEL
import platform.windows.EN_KILLFOCUS
import platform.windows.SendMessageW
import platform.windows.SetWindowTextW
import platform.windows.Sleep
import platform.windows.WM_COMMAND
import platform.windows.WM_SYSCOMMAND
import vesper.config.Action
import vesper.config.Font
import vesper.config.LiveConfig
import vesper.config.Scheme
import vesper.config.Settings
import vesper.host.SettingField
import vesper.input.Key
import vesper.input.KeyEvent
import vesper.input.Modifiers
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

private const val SC_SETTINGS = 0x0010

class SettingsTest {
    private val fs = FileSystem.SYSTEM
    private val dir = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "vesper-settings-${Random.nextLong().toULong()}"
    private val path: Path = dir / "config.toml"

    @AfterTest
    fun cleanUp() {
        fs.deleteRecursively(dir)
    }

    private fun file() = fs.read(path) { readUtf8() }

    private fun write(text: String) {
        fs.createDirectories(dir)
        fs.write(path) { writeUtf8(text) }
    }

    /** What leaving [field] after typing [text] sends the dialog. */
    private fun SettingsDialog.type(
        field: SettingField,
        text: String,
    ) {
        val control = control(field)
        SetWindowTextW(control, text)
        command(field, EN_KILLFOCUS, control.toLong())
    }

    private fun SettingsDialog.command(
        field: SettingField,
        code: Int,
        control: Long,
    ) {
        val wParam = ((code.toLong() shl 16) or (FIRST_SETTING_ID + field.ordinal).toLong()).toULong()
        SendMessageW(window.hwnd, WM_COMMAND.convert(), wParam, control)
    }

    @Test
    fun theDialogWritesEachCommittedFieldAndRefusesBadOnes() {
        val owner = Window("Vesper test", 100, 100, object : WindowListener {})
        try {
            LiveConfig(path.toString()) {}.use { live ->
                SettingsDialog(live, owner.hwnd).use { dialog ->
                    assertEquals("13", textOf(dialog.control(SettingField.FontSize)))
                    dialog.type(SettingField.FontSize, "15")
                    val scheme = dialog.control(SettingField.ColorScheme)
                    SendMessageW(scheme, CB_SETCURSEL.convert(), Scheme.entries.indexOf(Scheme.SolarizedDark).convert(), 0)
                    dialog.command(SettingField.ColorScheme, CBN_SELCHANGE, scheme.toLong())
                    assertEquals("[font]\nsize = 15.0\n\n[colors]\nscheme = \"solarized-dark\"\n", file())
                    assertEquals(Scheme.SolarizedDark, live.snapshot().settings.scheme)
                    assertEquals("", dialog.statusText)

                    dialog.type(SettingField.Scrollback, "-5")
                    assertTrue("scrollback" in dialog.statusText, dialog.statusText)
                    assertEquals(Settings.DEFAULT.scrollback, live.snapshot().settings.scrollback, "a refused value changes nothing")

                    // A field left untouched writes nothing, however often focus passes through it.
                    val before = file()
                    dialog.type(SettingField.FontSize, "15")
                    assertEquals(before, file())

                    // An edit made in the file shows up in the dialog.
                    write("scrollback = 500\n")
                    live.reload()
                    dialog.show(live.snapshot())
                    assertEquals("500", textOf(dialog.control(SettingField.Scrollback)))
                    assertEquals("", dialog.statusText)
                }
            }
        } finally {
            owner.close()
        }
    }

    @Test
    fun aFamilyThisMachineLacksFallsBackToConsolas() {
        FontSystem.create().use { fonts ->
            assertNotNull(TextStyle.of(fonts, Font("Courier New", 14.0))).use { assertEquals(Font("Courier New", 14.0), it.font) }
            assertNotNull(TextStyle.of(fonts, Font("No Such Font Anywhere", 13.0))).use { assertEquals("Consolas", it.font.family) }
            assertNotNull(TextStyle.of(fonts, Font("", 16.0))).use { assertEquals(Font("Consolas", 16.0), it.font) }
        }
    }

    @Test
    fun theAppTakesTheFileLiveAndItsBindingsZoom() {
        write("[font]\nsize = 20\n")
        Gpu.create(warp = true).use { gpu ->
            App(gpu, quitOnClose = false, configPath = path.toString()).use { app ->
                val defaultCell = FontSystem.create().use { fonts -> assertNotNull(TextStyle.of(fonts)).use { it.cell.height } }
                assertEquals(20.0, app.text.font.size)
                assertTrue(app.text.cell.height > defaultCell)

                // The Windows defaults: zoom on Ctrl, and plain typing stays the program's.
                val equals = Key.Character('='.code)
                assertTrue(app.shortcut(KeyEvent(equals, mods = Modifiers.CTRL + Modifiers.NUM_LOCK)))
                assertEquals(21.0, app.text.font.size)
                assertFalse(app.shortcut(KeyEvent(equals)))
                assertFalse(app.shortcut(KeyEvent(Key.Character('v'.code), mods = Modifiers.SUPER)), "Win+V is the system's")

                write("[font]\nsize = 20\n\n[colors]\nscheme = \"vesper-light\"\n")
                val deadline = TimeSource.Monotonic.markNow() + 10.seconds
                while (app.applied.scheme != Scheme.VesperLight) {
                    assertTrue(deadline.hasNotPassedNow(), "the watcher never posted the change")
                    Window.pumpMessages()
                    Sleep(10u)
                }
                assertEquals(21.0, app.text.font.size, "a colour change keeps the zoom")
                app.perform(Action.FontReset)
                assertEquals(20.0, app.text.font.size, "reset goes to the configured size")
                app.apply(app.applied.copy(font = Font("Courier New", 18.0)))
                assertEquals(Font("Courier New", 18.0), app.text.font)
                app.paint()

                assertNull(app.settingsDialog)
                SendMessageW(app.window.hwnd, WM_SYSCOMMAND.convert(), SC_SETTINGS.convert(), 0)
                val dialog = assertNotNull(app.settingsDialog)
                assertEquals("20", textOf(dialog.control(SettingField.FontSize)), "the dialog shows the file")
                assertTrue(app.shortcut(KeyEvent(Key.Character(','.code), mods = Modifiers.CTRL)))
            }
        }
    }
}
