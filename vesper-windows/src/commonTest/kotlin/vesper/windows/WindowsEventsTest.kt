package vesper.windows

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WindowsEventsTest {
    @Test
    fun aLocalFileUriWithADriveIsAWindowsPath() {
        assertEquals("C:\\Users\\me", windowsPath("file:///C:/Users/me", "PC"))
        assertEquals("C:\\Users\\me", windowsPath("file://localhost/C:/Users/me", "PC"))
        assertEquals("d:\\work", windowsPath("FILE://pc/d:/work", "PC"))
        assertEquals("C:\\", windowsPath("file:///C:", "PC"))
        assertEquals("C:\\", windowsPath("file:///C:/", "PC"))
        assertEquals("C:\\Program Files\\ü", windowsPath("file:///C:/Program%20Files/%C3%BC", "PC"))
    }

    @Test
    fun anythingElseNamesNoDirectoryHere() {
        assertNull(windowsPath("http:///C:/x", "PC"))
        // Another machine's directory, and WSL's Linux paths.
        assertNull(windowsPath("file://server/C:/x", "PC"))
        assertNull(windowsPath("file://wsl/home/me", "PC"))
        assertNull(windowsPath("file:///home/me", "PC"))
        assertNull(windowsPath("file://", "PC"))
        assertNull(windowsPath("file:///C:x", "PC"))
        assertNull(windowsPath("file:///1:/x", "PC"))
        // Broken escapes, bytes that are not UTF-8, and control characters.
        assertNull(windowsPath("file:///C:/a%2", "PC"))
        assertNull(windowsPath("file:///C:/a%zz", "PC"))
        assertNull(windowsPath("file:///C:/%FF", "PC"))
        assertNull(windowsPath("file:///C:/a%00b", "PC"))
    }

    @Test
    fun onlyABorderDragHoldsTheOutput() {
        val resize = LiveResize()
        // A move: in and out of the loop without sizing.
        resize.enter()
        assertFalse(resize.exit())

        resize.enter()
        assertTrue(resize.sizing())
        assertFalse(resize.sizing())
        assertTrue(resize.held)
        assertTrue(resize.exit())
        assertFalse(resize.held)

        // Sizing outside the loop (a program's own SetWindowPos) is applied at once.
        assertFalse(resize.sizing())
    }
}
