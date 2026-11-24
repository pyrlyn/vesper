package vesper.macos

import platform.AppKit.NSEvent
import platform.AppKit.NSEventTypeKeyDown
import platform.Foundation.NSMakePoint
import platform.QuartzCore.CACurrentMediaTime
import platform.darwin.DISPATCH_TIME_NOW
import platform.darwin.dispatch_after
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_time
import vesper.harness.fixed

// Key-to-screen latency, run by `vesper --latency` from a release build: the probe types into `cat`
// through the view's own key path and times the frame that shows the echo, once when the GPU has
// drawn it and once when it reached the display.

private const val KEY_A = 0x00
private const val KEY_RETURN = 0x24
private const val GAP_NANOS = 50_000_000L
private const val TIMEOUT_NANOS = 1_000_000_000L
private const val LINE = 60
private const val MILLIS = 1000.0
private const val P95 = 0.95

internal class LatencyProbe(
    private val view: TerminalView,
    private val samples: Int = 200,
    private val done: (String) -> Unit,
) {
    private val rendered = ArrayList<Double>()
    private val presented = ArrayList<Double>()
    private var missed = 0
    private var typed = 0

    fun start() = later { type() }

    private fun later(body: () -> Unit) = dispatch_after(dispatch_time(DISPATCH_TIME_NOW, GAP_NANOS), dispatch_get_main_queue(), body)

    private fun type() {
        if (typed >= samples) return done(markdown())
        val enter = ++typed % LINE == 0
        val characters = if (enter) "\r" else "a"
        val event =
            NSEvent.keyEventWithType(
                NSEventTypeKeyDown,
                NSMakePoint(0.0, 0.0),
                0u,
                0.0,
                view.window?.windowNumber ?: 0,
                null,
                characters,
                characters,
                false,
                (if (enter) KEY_RETURN else KEY_A).toUShort(),
            ) ?: return done("no key event")
        val sample = typed
        val start = CACurrentMediaTime()
        view.probeNextFrame { isPresented, time ->
            dispatch_async(dispatch_get_main_queue()) {
                if (!isPresented) {
                    rendered.add(time - start)
                } else if (sample == typed && !answered) {
                    answered = true
                    if (time > 0) presented.add(time - start) else missed++
                    later { type() }
                }
            }
        }
        answered = false
        // A frame the window server never shows may never report back; the probe moves on without it.
        dispatch_after(dispatch_time(DISPATCH_TIME_NOW, TIMEOUT_NANOS), dispatch_get_main_queue()) {
            if (sample == typed && !answered) {
                answered = true
                missed++
                type()
            }
        }
        view.keyDown(event)
    }

    private var answered = false

    private fun row(
        name: String,
        values: List<Double>,
    ): String {
        if (values.isEmpty()) return "| $name | 0 | - | - | - | - |\n"
        val sorted = values.sorted()
        val ms = { v: Double -> fixed(v * MILLIS, 2) }
        val p95 = sorted[((sorted.size - 1) * P95).toInt()]
        return "| $name | ${sorted.size} | ${ms(sorted.first())} | ${ms(sorted[sorted.size / 2])} | ${ms(p95)} | ${ms(sorted.last())} |\n"
    }

    private fun markdown(): String =
        "| key to | samples | min ms | median ms | p95 ms | max ms |\n| --- | --- | --- | --- | --- | --- |\n" +
            row("GPU done", rendered) + row("on screen", presented) +
            (if (missed > 0) "\n$missed frames were never presented.\n" else "")
}
