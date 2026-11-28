package vesper.config

import okio.FileSystem
import okio.Path
import okio.SYSTEM
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LoadTest {
    private val fs = FileSystem.SYSTEM
    private val dir: Path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "vesper-config-${Random.nextLong().toULong()}"

    init {
        fs.createDirectories(dir)
    }

    @AfterTest
    fun cleanUp() = fs.deleteRecursively(dir)

    private fun file(bytes: ByteArray): String {
        val path = dir / "config.toml"
        fs.write(path) { write(bytes) }
        return path.toString()
    }

    @Test
    fun aMissingFileIsTheDefaults() {
        assertEquals(Settings.DEFAULT, Settings.load((dir / "absent.toml").toString()))
        assertEquals(Settings.DEFAULT, Settings.load((dir / "no-dir" / "config.toml").toString()))
    }

    @Test
    fun aFileOnDiskIsParsed() {
        val path = file("[font]\nsize = 18\n".encodeToByteArray())
        assertEquals(18.0, Settings.load(path).font.size)
    }

    @Test
    fun errorsNameTheFile() {
        val path = file("bogus = 1\n".encodeToByteArray())
        val error = assertFailsWith<ConfigException.Invalid> { Settings.load(path) }
        assertEquals(path, error.path)
        assertTrue(error.message.orEmpty().startsWith("$path: "))
    }

    @Test
    fun aHugeFileIsRefusedWithoutReadingItAll() {
        val path = file(ByteArray(MAX_FILE_BYTES * 16) { '#'.code.toByte() })
        assertFailsWith<ConfigException.TooLarge> { Settings.load(path) }
    }

    @Test
    fun bytesThatAreNotUtf8AreRefused() {
        val path = file(byteArrayOf('#'.code.toByte(), 0xC3.toByte(), 0x28))
        val error = assertFailsWith<ConfigException.Invalid> { Settings.load(path) }
        assertTrue(error.message.orEmpty().endsWith("not valid UTF-8"))
    }

    @Test
    fun aDirectoryIsAnIoError() {
        assertFailsWith<ConfigException.Io> { Settings.load(dir.toString()) }
    }
}
