package vesper.config

import okio.FileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import okio.SYSTEM
import vesper.sync.Monitor
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.experimental.ExperimentalNativeApi
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

private val PATIENCE = 20.seconds
private const val SHARING_RETRIES = 100

private fun sleep(duration: Duration) {
    val monitor = Monitor(1)
    val end = TimeSource.Monotonic.markNow() + duration
    monitor.locked { while (end.hasNotPassedNow()) monitor.await(0, -end.elapsedNow()) }
}

@OptIn(ExperimentalAtomicApi::class)
class LiveConfigTest {
    private val fs = FileSystem.SYSTEM
    private val dir: Path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "vesper-live-${Random.nextLong().toULong()}"
    private val opened = ArrayList<LiveConfig>()
    private val wakeups = AtomicInt(0)

    init {
        fs.createDirectories(dir)
    }

    @AfterTest
    fun cleanUp() {
        opened.forEach(LiveConfig::close)
        fs.deleteRecursively(dir)
    }

    private fun open(
        name: String = "config.toml",
        resync: Duration = RESYNC,
        stamp: (String) -> Any? = ::fileStamp,
    ): LiveConfig = LiveConfig((dir / name).toString(), { wakeups.incrementAndFetch() }, POLL, resync, stamp).also { opened += it }

    private val LiveConfig.file: Path get() = path.toPath()

    private fun read(path: Path) = fs.read(path) { readUtf8() }

    private fun write(
        path: Path,
        text: String,
    ) = fs.write(path) { writeUtf8(text) }

    // An editor's save: written elsewhere, renamed over, so no reader sees a truncated file.
    private fun replace(
        path: Path,
        text: String,
    ) {
        val draft = path.parent!! / "${path.name}.draft"
        write(draft, text)
        whileWindowsHoldsIt { fs.atomicMove(draft, path) }
    }

    // Windows refuses to rename over or delete a file another thread has open, and the watcher
    // reads it every poll; an editor there retries the same way.
    private fun whileWindowsHoldsIt(action: () -> Unit) {
        repeat(SHARING_RETRIES) {
            try {
                return action()
            } catch (_: IOException) {
                sleep(5.milliseconds)
            }
        }
        action()
    }

    /** Polls until [done] holds for the snapshot, as a host does between wakeups. */
    private fun LiveConfig.waitFor(
        what: String,
        done: (Snapshot) -> Boolean,
    ): Snapshot {
        val start = TimeSource.Monotonic.markNow()
        while (true) {
            val snapshot = snapshot()
            if (done(snapshot)) return snapshot
            if (start.elapsedNow() > PATIENCE) fail("timed out waiting for $what: $snapshot")
            sleep(20.milliseconds)
        }
    }

    @Test
    fun aMissingFileOpensAsTheDefaultsAndIsWatched() {
        val snapshot = open("sub/config.toml").snapshot()
        assertEquals(Snapshot(1, Settings.DEFAULT, null, watching = true), snapshot)
        assertEquals(0, wakeups.load(), "opening is not a change")
    }

    @Test
    fun editingTheFileChangesTheSettingsWithoutARestart() {
        val live = open()
        write(live.file, "[font]\nsize = 18\n[colors]\nscheme = \"vesper-light\"\n")
        val snapshot = live.waitFor("the new size") { it.settings.font.size == 18.0 }
        assertEquals(2, snapshot.generation)
        assertEquals(Scheme.VesperLight.theme, snapshot.settings.colors)
        assertTrue(wakeups.load() >= 1)

        replace(live.file, "scrollback = 42\n")
        live.waitFor("the renamed file") { it.settings.scrollback == 42 }

        whileWindowsHoldsIt { fs.delete(live.file) }
        assertNull(live.waitFor("the defaults") { it.settings == Settings.DEFAULT }.error)
    }

    @Test
    fun aBrokenEditKeepsTheLastGoodSettingsAndSaysWhy() {
        val live = open()
        write(live.file, "scrollback = 7\n")
        live.waitFor("7") { it.settings.scrollback == 7 }

        replace(live.file, "scrollback = \"many\"\n")
        val broken = live.waitFor("the error") { it.error != null }
        assertEquals(7, broken.settings.scrollback)
        assertTrue(broken.error.orEmpty().startsWith(live.path), broken.error)

        replace(live.file, "scrollback = 8\n")
        assertEquals(8, live.waitFor("the fix") { it.error == null }.settings.scrollback)
    }

    @Test
    fun aFileThatIsBadAtStartupIsAnErrorWithTheDefaults() {
        write(dir / "config.toml", "bogus = 1\n")
        val snapshot = open().snapshot()
        assertEquals(Settings.DEFAULT, snapshot.settings)
        assertTrue("bogus" in snapshot.error.orEmpty(), snapshot.error)
    }

    @Test
    fun reloadingAnUnchangedFileRaisesNoWakeup() {
        write(dir / "config.toml", "scrollback = 3\n")
        val live = open()
        live.reload()
        live.reload()
        assertEquals(1L to 0, live.snapshot().generation to wakeups.load())
    }

    @Test
    fun aChangeTheStampCannotSeeStillAppliesOnTheResync() {
        // A file system with a coarse clock and a save of the same size: the stamp never moves.
        val live = open(resync = 300.milliseconds, stamp = { 0 })
        write(live.file, "scrollback = 5\n")
        live.waitFor("the resync") { it.settings.scrollback == 5 }
    }

    @Test
    fun aFloodOfWritesAndDirectoryEventsEndsInTheLastWrite() {
        // T30: the directory churns with other files while the config is saved over and over, each
        // save the same size, so only the content tells them apart. The last one must win.
        val live = open()
        val last = 1999
        for (i in 1000..last) {
            replace(live.file, "scrollback = $i\n")
            val junk = dir / "junk-$i"
            write(junk, "x")
            fs.delete(junk)
        }
        val snapshot = live.waitFor("the last write") { it.settings.scrollback == last }
        assertNull(snapshot.error)
        // Nothing older comes back once the last write is in.
        sleep(RESYNC + POLL * 3)
        assertEquals(snapshot, live.snapshot())
    }

    @Test
    fun setEditsOneKeyInPlaceAndAppliesItAtOnce() {
        val path = dir / "config.toml"
        write(path, "# my terminal\nscrollback = 5 # short\n\n[font]\nsize = 12\n")
        val live = open()
        live.set("font.size", "15.5")
        live.set("font.family", "Menlo")
        live.set("colors.scheme", "solarized-dark")
        val snapshot = live.snapshot()
        assertEquals(Font("Menlo", 15.5), snapshot.settings.font)
        assertEquals(Scheme.SolarizedDark, snapshot.settings.scheme)
        assertEquals(
            "# my terminal\nscrollback = 5 # short\n\n[font]\nfamily = \"Menlo\"\nsize = 15.5\n\n[colors]\nscheme = \"solarized-dark\"\n",
            read(path),
        )

        live.set("font.family", null)
        live.set("colors.foreground", "#00ff00")
        live.set("colors.foreground", null)
        live.set("scrollback", "9")
        assertEquals(
            "",
            live
                .snapshot()
                .settings.font.family,
        )
        assertEquals(
            "# my terminal\nscrollback = 9\n\n[font]\nsize = 15.5\n\n[colors]\nscheme = \"solarized-dark\"\n",
            read(path),
        )
    }

    @Test
    fun setPutsANewTopLevelKeyAboveTheTables() {
        val path = dir / "config.toml"
        write(path, "[font]\nsize = 12\n")
        open().set("scrollback", "100")
        assertEquals("scrollback = 100\n[font]\nsize = 12\n", read(path))
        assertEquals(100, Settings.load(path.toString()).scrollback)
    }

    @Test
    fun setEscapesTextSoItReadsBackAsWritten() {
        val live = open()
        val family = "My \"Mono\" \\ Nerd"
        live.set("font.family", family)
        assertEquals(
            family,
            live
                .snapshot()
                .settings.font.family,
        )
    }

    @Test
    fun setRefusesWhatWouldMakeTheFileInvalidAndWritesNothing() {
        val path = dir / "config.toml"
        write(path, "scrollback = 5\n")
        val live = open()
        val refused =
            listOf(
                "font.size" to "999",
                "font.size" to "big",
                "font.size" to "nan",
                "font.size" to "inf",
                "scrollback" to "-1",
                "scrollback" to "2000000",
                "scrollback" to "1.5",
                "colors.scheme" to "neon",
                "colors.cursor" to "red",
                "font.family" to "tab\there",
                "font.nope" to "1",
                "keybind" to "x",
                "colors.ansi" to "[]",
                "" to "x",
            )
        for ((key, value) in refused) assertFailsWith<ConfigException.Invalid>("$key=$value") { live.set(key, value) }
        assertEquals("scrollback = 5\n", read(path))
        assertEquals(1, live.snapshot().generation)
    }

    @Test
    fun setCreatesTheFileAndDirectoryAndLeavesNoTemporaryFile() {
        val live = open("a/b/config.toml")
        live.set("scrollback", "9")
        assertEquals("scrollback = 9\n", read(live.file))
        assertEquals(listOf("config.toml"), fs.list(live.file.parent!!).map { it.name })
    }

    @Test
    fun setDoesNotEditAFileThatIsAlreadyBroken() {
        val path = dir / "config.toml"
        write(path, "[font\n")
        assertFailsWith<ConfigException.Invalid> { open().set("scrollback", "9") }
        assertEquals("[font\n", read(path))
    }

    @OptIn(ExperimentalNativeApi::class)
    @Test
    fun setWritesThroughASymlinkInsteadOfReplacingIt() {
        // Creating a symlink on Windows needs a privilege CI does not grant.
        if (Platform.osFamily == OsFamily.WINDOWS) return
        val real = dir / "real.toml"
        write(real, "scrollback = 1\n")
        fs.createSymlink(dir / "config.toml", real)
        val live = open()
        live.set("scrollback", "2")
        assertTrue(fs.metadata(live.file).symlinkTarget != null)
        assertEquals("scrollback = 2\n", read(real))
    }

    @Test
    fun keybindsSurviveALiveReload() {
        val live = open()
        write(live.file, "[[keybind]]\nkey = \"super+v\"\naction = \"none\"\n")
        val snapshot = live.waitFor("the unbound paste") { it.settings.bindings.size == DEFAULT_BINDINGS.size - 1 }
        assertTrue(snapshot.settings.bindings.none { it.action == Action.Paste })
    }

    @Test
    fun closingStopsTheWatcherForGood() {
        val live = open()
        live.close()
        assertFalse(live.snapshot().watching)
        write(live.file, "scrollback = 1\n")
        sleep(POLL * 4)
        assertEquals(0, wakeups.load())
        assertEquals(1, live.snapshot().generation)
    }
}
