package vesper.macos

import platform.AppKit.NSEvent
import platform.AppKit.NSEventTypeKeyDown
import platform.Foundation.NSDate
import platform.Foundation.NSMakePoint
import platform.Foundation.NSRunLoop
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.Foundation.runUntilDate
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** A key press as AppKit hands it to a view; [characters] is both the typed and the unmodified text. */
internal fun keyEvent(
    keyCode: UShort,
    characters: String,
    flags: ULong = 0u,
): NSEvent =
    NSEvent.keyEventWithType(
        NSEventTypeKeyDown,
        NSMakePoint(0.0, 0.0),
        flags,
        0.0,
        0,
        null,
        characters,
        characters,
        false,
        keyCode,
    ) ?: fail("no key event for $characters")

/**
 * Runs the main run loop, where the sessions' wake-ups land, and reads [views]' frames until [done]
 * holds; after ten seconds it fails with what they show. The views are asked for each round, since
 * a pane may close on the way.
 */
internal fun awaitViews(
    views: () -> List<TerminalView>,
    done: () -> Boolean,
) {
    val end = TimeSource.Monotonic.markNow() + 10.seconds
    while (!done()) {
        if (end.hasPassedNow()) fail("timed out; the screens show:\n" + views().joinToString("\n--\n") { it.screenText })
        NSRunLoop.mainRunLoop.runUntilDate(NSDate.dateWithTimeIntervalSinceNow(0.05))
        views().forEach { it.refresh() }
    }
}
