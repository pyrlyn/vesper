package app.vesper

import app.vesper.app.Settings
import app.vesper.app.SyncGroup
import app.vesper.app.applySyncGroups
import app.vesper.app.syncGroups
import app.vesper.ui.theme.AccentId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyncTest {
    @Test
    fun groupsHoldOnlyTheirFields() {
        val g = syncGroups(Settings(shell = "/bin/fish", keybindings = mapOf("Find" to "shift+F")), SyncGroup.entries)
        assertTrue("accent" in g.getValue("settings"))
        assertFalse("shell" in g.getValue("settings"), "the shell is per device")
        assertEquals(setOf("keybindings", "workflows"), g.getValue("keybindings").keys)
        assertEquals(setOf("hosts"), g.getValue("hosts").keys)
    }

    @Test
    fun applyingTakesOnlyEnabledGroups() {
        val remote = syncGroups(
            Settings(accent = AccentId.entries.last(), keybindings = mapOf("Find" to "shift+F"), shell = "/bin/fish"),
            SyncGroup.entries,
        )
        val local = Settings(shell = "/bin/zsh", syncedAt = 5)
        val merged = applySyncGroups(local, remote, listOf(SyncGroup.Settings))
        assertEquals(AccentId.entries.last(), merged.accent)
        assertEquals(emptyMap(), merged.keybindings, "keybindings sync is off")
        assertEquals("/bin/zsh", merged.shell)
        assertEquals(5, merged.syncedAt)
    }
}
