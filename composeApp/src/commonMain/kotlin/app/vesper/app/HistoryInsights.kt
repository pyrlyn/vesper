package app.vesper.app

private const val Week = 7L * 24 * 60 * 60 * 1000

/** Numbers for History › Insights over the 7 days before `now`. */
data class HistoryInsights(
    val commands: Int,
    /** Change against the 7 days before, in percent; null without data for that week. */
    val changePercent: Int?,
    val failed: Int,
    val totalMs: Long,
    /** Command with the longest average run, with that average. */
    val longest: Pair<String, Long>?,
    val top: List<Pair<String, Int>>,
) {
    val failureRate: Double get() = if (commands == 0) 0.0 else failed * 100.0 / commands
}

fun historyInsights(history: List<HistoryEntry>, now: Long): HistoryInsights {
    val week = history.filter { it.at in (now - Week)..now }
    val before = history.count { it.at in (now - 2 * Week) until (now - Week) }
    val byKey = week.groupBy { commandKey(it.command) }
    return HistoryInsights(
        commands = week.size,
        changePercent = if (before == 0) null else ((week.size - before) * 100.0 / before).toInt(),
        failed = week.count { it.failed },
        totalMs = week.sumOf { it.durationMs },
        longest = byKey.mapValues { (_, runs) -> runs.sumOf { it.durationMs } / runs.size }
            .maxByOrNull { it.value }?.takeIf { it.value > 0 }?.toPair(),
        top = byKey.map { it.key to it.value.size }.sortedByDescending { it.second }.take(4),
    )
}

/** What groups runs of "the same command": "git status -sb" → "git status", "ls -la" → "ls". */
fun commandKey(command: String): String {
    val words = command.trim().split(Regex("\\s+"))
    val first = words.firstOrNull().orEmpty()
    val second = words.getOrNull(1)
    return if (second != null && Regex("[a-z][a-z0-9-]*").matches(second)) "$first $second" else first
}
