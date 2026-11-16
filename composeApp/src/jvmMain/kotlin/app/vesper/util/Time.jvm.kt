package app.vesper.util

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val withSeconds = DateTimeFormatter.ofPattern("HH:mm:ss")
private val withoutSeconds = DateTimeFormatter.ofPattern("HH:mm")

actual fun nowMillis(): Long = System.currentTimeMillis()

actual fun formatClock(epochMillis: Long, seconds: Boolean): String =
    Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).format(if (seconds) withSeconds else withoutSeconds)
