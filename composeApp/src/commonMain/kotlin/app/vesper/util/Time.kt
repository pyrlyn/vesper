package app.vesper.util

expect fun nowMillis(): Long

/** Local wall-clock time, e.g. "14:29:58" (seconds) or "14:30". */
expect fun formatClock(epochMillis: Long, seconds: Boolean = true): String

fun formatDuration(ms: Long): String = when {
    ms < 1_000 -> "${(ms / 10) / 100.0}s".let { if (ms < 10) "0.01s" else it }
    ms < 10_000 -> "${(ms / 10) / 100.0}s"
    ms < 60_000 -> "${(ms / 100) / 10.0}s"
    else -> {
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        if (h > 0) "${h}h ${m.pad()}m" else "${m}m ${s.pad()}s"
    }
}

/** Running timer as shown on the running badge: "00:42", "12:03", "1:02:03". */
fun formatElapsed(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "$h:${m.pad()}:${s.pad()}" else "${m.pad()}:${s.pad()}"
}

/** "now", "2m", "3h", "yesterday", "4d". */
fun formatAgo(epochMillis: Long, now: Long = nowMillis()): String {
    val d = (now - epochMillis).coerceAtLeast(0) / 1000
    return when {
        d < 45 -> "now"
        d < 3600 -> "${(d + 30) / 60}m"
        d < 86_400 -> "${d / 3600}h"
        d < 2 * 86_400 -> "yesterday"
        else -> "${d / 86_400}d"
    }
}

private fun Long.pad() = toString().padStart(2, '0')
