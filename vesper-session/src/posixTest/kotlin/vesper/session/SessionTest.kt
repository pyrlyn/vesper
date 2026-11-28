package vesper.session

import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.usleep
import vesper.input.Key
import vesper.input.KeyEvent
import vesper.pty.SpawnOptions
import vesper.term.Event
import vesper.term.Frame
import vesper.term.SelectionKind
import vesper.term.TextRange
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** Long enough for any wait that should succeed, even on a loaded CI machine. */
private val GENEROUS = 10.seconds

private fun shell(script: String) = SpawnOptions.command("/bin/sh", "-c", script)

@OptIn(ExperimentalForeignApi::class)
private fun pause(duration: Duration) {
    usleep(duration.inWholeMicroseconds.toUInt())
}

private fun Frame.text(): String = (0 until rows).joinToString("\n") { row(it)?.text.orEmpty().trimEnd() }

/** Updates [frame] until its text satisfies [done], draining events as a host would; fails after [GENEROUS]. */
private fun Session.await(
    frame: Frame,
    done: (String) -> Boolean,
): String {
    val end = TimeSource.Monotonic.markNow() + GENEROUS
    while (true) {
        update(frame)
        while (true) {
            val event = pollEvent() ?: break
            assertFalse(event is Event.ClipboardRead, "a clipboard read reached the host")
        }
        val text = frame.text()
        if (done(text)) return text
        if (end.hasPassedNow()) fail("timed out; the screen shows:\n$text")
        pause(10.milliseconds)
    }
}

@OptIn(ExperimentalAtomicApi::class)
class SessionTest {
    @Test
    fun outputReachesTheFrameAndTheExitIsKept() {
        Session.spawn(shell("printf 'hello\\n'; exit 3")) {}.use { session ->
            val frame = Frame()
            session.await(frame) { it.startsWith("hello") }
            val end = TimeSource.Monotonic.markNow() + GENEROUS
            while (session.exitStatus == null && !end.hasPassedNow()) pause(10.milliseconds)
            assertEquals(3u, session.exitStatus?.code)
            assertNull(session.failure)
        }
    }

    @Test
    fun typedTextComesBackThroughCat() {
        Session.spawn(SpawnOptions.command("cat")) {}.use { session ->
            session.text("abc")
            session.key(KeyEvent(Key.Enter))
            // The line discipline echoes the line, then cat writes it back.
            session.await(Frame()) { text -> text.lines().count { it == "abc" } == 2 }
        }
    }

    @Test
    fun aSelectionInViewportCellsReadsBackInTheViewportText() {
        Session.spawn(SpawnOptions.command("cat")) {}.use { session ->
            session.text("hello world")
            session.await(Frame()) { it.startsWith("hello world") }
            session.select(SelectionKind.Word, 0, 7)
            val text = session.viewportText()
            assertEquals(listOf(TextRange(6, 11)), text.selected)
            session.selectClear()
            assertEquals(emptyList(), session.viewportText().selected)
        }
    }

    @Test
    fun theViewWalksTheShellsPromptMarks() {
        val script = "printf '\\033]133;A\\007one\\n'; seq 40; printf '\\033]133;A\\007two\\n'; seq 30; echo done; sleep 30"
        Session.spawn(shell(script)) {}.use { session ->
            val frame = Frame()
            session.await(frame) { it.lines().any { line -> line == "done" } }
            assertEquals(true, session.scrollToPrompt(forward = false))
            assertEquals("two", session.await(frame) { it.startsWith("two") }.lines().first())
            assertEquals(true, session.scrollToPrompt(forward = false))
            session.await(frame) { it.startsWith("one") }
            assertEquals(false, session.scrollToPrompt(forward = false), "no prompt above the first")
            assertEquals(true, session.scrollToPrompt(forward = true))
            session.await(frame) { it.startsWith("two") }
        }
    }

    @Test
    fun theSelectionCopiesAndASearchStepsAndCounts() {
        Session.spawn(SpawnOptions.command("cat")) {}.use { session ->
            assertEquals(false, session.hasSelection)
            assertEquals("", session.selectionText())
            session.text("one Two two")
            session.await(Frame()) { it.startsWith("one Two two") }
            session.select(SelectionKind.Word, 0, 5)
            assertEquals(true, session.hasSelection)
            assertEquals("Two", session.selectionText())
            session.search("two", caseSensitive = false)
            assertEquals(2, session.searchCount())
            assertEquals(true, session.searchStep(forward = true))
            session.search("two", caseSensitive = true)
            assertEquals(1, session.searchCount())
            session.search("", caseSensitive = true)
            assertEquals(0, session.searchCount(), "an empty pattern ends the search")
            assertEquals(false, session.searchStep(forward = false))
        }
    }

    @Test
    fun oneWakeupUntilTheHostLooks() {
        val wakeups = AtomicInt(0)
        val script = "printf a; sleep 0.1; printf b; sleep 0.1; printf c; sleep 30"
        Session.spawn(shell(script)) { wakeups.incrementAndFetch() }.use { session ->
            val end = TimeSource.Monotonic.markNow() + GENEROUS
            while (wakeups.load() == 0 && !end.hasPassedNow()) pause(10.milliseconds)
            pause(500.milliseconds)
            assertEquals(1, wakeups.load(), "woken again before the host looked")
            session.await(Frame()) { it.startsWith("abc") }
        }
    }

    @Test
    fun aClipboardReadIsDeniedAndAnswered() {
        // raw keeps the reply off the screen as typed text; od shows the bytes that came back.
        val script = "stty raw -echo; printf '\\033]52;c;?\\007'; head -c 5 | od -An -c; sleep 30"
        Session.spawn(shell(script)) {}.use { session ->
            session.await(Frame()) { it.contains("033   ]   5   2   ;") }
        }
    }

    @Test
    fun resizeReachesTheFrameAndTheChild() {
        Session.spawn(shell("read -r _; stty size; sleep 30")) {}.use { session ->
            session.resize(100, 30, 8, 16)
            session.key(KeyEvent(Key.Enter))
            val frame = Frame()
            session.await(frame) { it.lines().any { line -> line == "30 100" } }
            assertEquals(100, frame.cols)
            assertEquals(30, frame.rows)
        }
    }
}
