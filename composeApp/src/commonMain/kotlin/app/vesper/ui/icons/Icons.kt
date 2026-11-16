package app.vesper.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * (Lucide geometry at the design's native sizes); the second group uses Lucide's 24-grid.
 * All are drawn in black and tinted at the call site.
 */
object Icons {
    private class Builder(val b: ImageVector.Builder) {
        fun stroked(d: String, width: Float) {
            b.addPath(
                pathData = addPathNodes(d),
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = width,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }

        fun lucide(vararg d: String) = d.forEach { stroked(it, 2f) }
    }

    private fun icon(name: String, w: Float, h: Float, block: Builder.() -> Unit): ImageVector {
        val b = ImageVector.Builder(name, w.dp, h.dp, w, h)
        Builder(b).block()
        return b.build()
    }

    private fun lucide(name: String, vararg d: String) = icon(name, 24f, 24f) { lucide(*d) }

    // ── Icons used in the design (Lucide geometry) ─────────────────────────
    val History by lazy { lucide("History", "M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8", "M3 3v5h5", "M12 7v5l4 2") }
    val Folder by lazy { lucide("Folder", "M20 20a2 2 0 0 0 2-2V8a2 2 0 0 0-2-2h-7.9a2 2 0 0 1-1.69-.9L9.6 3.9A2 2 0 0 0 7.93 3H4a2 2 0 0 0-2 2v13a2 2 0 0 0 2 2Z") }
    val GitBranch by lazy {
        lucide("GitBranch", "M6 3v12", "M21 6a3 3 0 1 1-6 0 3 3 0 0 1 6 0z", "M9 18a3 3 0 1 1-6 0 3 3 0 0 1 6 0z", "M18 9a9 9 0 0 1-9 9")
    }
    val CheckCircle by lazy { lucide("CheckCircle", "M22 12a10 10 0 1 1-20 0 10 10 0 0 1 20 0z", "m9 12 2 2 4-4") }
    val Loader by lazy { lucide("Loader", "M21 12a9 9 0 1 1-6.219-8.56") }
    val Stop by lazy { lucide("Stop", "M7 5h10a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2z") }
    val Sparkles by lazy {
        lucide(
            "Sparkles",
            "M9.94 15.5A2 2 0 0 0 8.5 14.06l-6.13-1.58a.5.5 0 0 1 0-.96L8.5 9.94A2 2 0 0 0 9.94 8.5l1.58-6.14a.5.5 0 0 1 .96 0L14.06 8.5A2 2 0 0 0 15.5 9.94l6.14 1.58a.5.5 0 0 1 0 .96L15.5 14.06a2 2 0 0 0-1.44 1.44l-1.58 6.14a.5.5 0 0 1-.96 0z",
            "M20 3v4", "M22 5h-4",
        )
    }
    val Terminal by lazy { lucide("Terminal", "m4 17 6-6-6-6", "M12 19h8") }
    val Server by lazy {
        lucide(
            "Server", "M4 2h16a2 2 0 0 1 2 2v4a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2z",
            "M4 14h16a2 2 0 0 1 2 2v4a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2v-4a2 2 0 0 1 2-2z", "M6 6h.01", "M6 18h.01",
        )
    }
    val Database by lazy {
        lucide("Database", "M21 5c0 1.66-4.03 3-9 3S3 6.66 3 5s4.03-3 9-3 9 1.34 9 3z", "M3 5v14a9 3 0 0 0 18 0V5", "M3 12a9 3 0 0 0 18 0")
    }
    val Logs by lazy { lucide("Logs", "M13 12h8", "M13 18h8", "M13 6h8", "M3 12h1", "M3 18h1", "M3 6h1", "M8 12h1", "M8 18h1", "M8 6h1") }
    val Play by lazy { lucide("Play", "M6 4.6a1 1 0 0 1 1.5-.87l12 7.4a1 1 0 0 1 0 1.74l-12 7.4A1 1 0 0 1 6 19.4z") }
    val Settings by lazy {
        lucide(
            "Settings",
            "M12.22 2h-.44a2 2 0 0 0-2 2v.18a2 2 0 0 1-1 1.73l-.43.25a2 2 0 0 1-2 0l-.15-.08a2 2 0 0 0-2.73.73l-.22.38a2 2 0 0 0 .73 2.73l.15.1a2 2 0 0 1 1 1.72v.51a2 2 0 0 1-1 1.74l-.15.09a2 2 0 0 0-.73 2.73l.22.38a2 2 0 0 0 2.73.73l.15-.08a2 2 0 0 1 2 0l.43.25a2 2 0 0 1 1 1.73V20a2 2 0 0 0 2 2h.44a2 2 0 0 0 2-2v-.18a2 2 0 0 1 1-1.73l.43-.25a2 2 0 0 1 2 0l.15.08a2 2 0 0 0 2.73-.73l.22-.39a2 2 0 0 0-.73-2.73l-.15-.08a2 2 0 0 1-1-1.74v-.5a2 2 0 0 1 1-1.74l.15-.09a2 2 0 0 0 .73-2.73l-.22-.38a2 2 0 0 0-2.73-.73l-.15.08a2 2 0 0 1-2 0l-.43-.25a2 2 0 0 1-1-1.73V4a2 2 0 0 0-2-2z",
            "M15 12a3 3 0 1 1-6 0 3 3 0 0 1 6 0z",
        )
    }
    val Search by lazy { lucide("Search", "m21 21-4.34-4.34", "M19 11a8 8 0 1 1-16 0 8 8 0 0 1 16 0z") }
    val Plus by lazy { lucide("Plus", "M5 12h14", "M12 5v14") }
    val PanelRight by lazy { lucide("PanelRight", "M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z", "M15 3v18") }
    val More by lazy {
        lucide("More", "M13 12a1 1 0 1 1-2 0 1 1 0 0 1 2 0z", "M20 12a1 1 0 1 1-2 0 1 1 0 0 1 2 0z", "M6 12a1 1 0 1 1-2 0 1 1 0 0 1 2 0z")
    }

    // ── Lucide (24-grid) ───────────────────────────────────────────────────
    val X by lazy { lucide("X", "M18 6 6 18", "m6 6 12 12") }
    val Minus by lazy { lucide("Minus", "M5 12h14") }
    val Square by lazy { lucide("Square", "M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z") }
    val Copy by lazy {
        lucide("Copy", "M10 8h10a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H10a2 2 0 0 1-2-2V10a2 2 0 0 1 2-2z", "M4 16c-1.1 0-2-.9-2-2V4c0-1.1.9-2 2-2h10c1.1 0 2 .9 2 2")
    }
    val ChevronDown by lazy { lucide("ChevronDown", "m6 9 6 6 6-6") }
    val ChevronRight by lazy { lucide("ChevronRight", "m9 18 6-6-6-6") }
    val ChevronUp by lazy { lucide("ChevronUp", "m18 15-6-6-6 6") }
    val Check by lazy { lucide("Check", "M20 6 9 17l-5-5") }
    val XCircle by lazy { lucide("XCircle", "M22 12a10 10 0 1 1-20 0 10 10 0 0 1 20 0z", "m15 9-6 6", "m9 9 6 6") }
    val Trash by lazy { lucide("Trash", "M3 6h18", "M19 6v14c0 1-1 2-2 2H7c-1 0-2-1-2-2V6", "M8 6V4c0-1 1-2 2-2h4c1 0 2 1 2 2v2") }
    val Zap by lazy {
        lucide("Zap", "M4 14a1 1 0 0 1-.78-1.63l9.9-10.2a.5.5 0 0 1 .86.46l-1.92 6.02A1 1 0 0 0 13 10h7a1 1 0 0 1 .78 1.63l-9.9 10.2a.5.5 0 0 1-.86-.46l1.92-6.02A1 1 0 0 0 11 14z")
    }
    val Globe by lazy { lucide("Globe", "M22 12a10 10 0 1 1-20 0 10 10 0 0 1 20 0z", "M12 2a14.5 14.5 0 0 0 0 20 14.5 14.5 0 0 0 0-20", "M2 12h20") }
    val Keyboard by lazy {
        lucide(
            "Keyboard", "M4 4h16a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z",
            "M6 8h.01", "M10 8h.01", "M14 8h.01", "M18 8h.01", "M8 12h.01", "M12 12h.01", "M16 12h.01", "M7 16h10",
        )
    }
    val Palette by lazy {
        lucide(
            "Palette", "M12 22a10 10 0 1 1 10-10c0 2.2-1.8 3-3.5 3H16a2 2 0 0 0-1.5 3.3A1.6 1.6 0 0 1 12 22z",
            "M13.5 6.5h.01", "M17.5 10.5h.01", "M8.5 7.5h.01", "M6.5 12.5h.01",
        )
    }
    val User by lazy { lucide("User", "M19 21v-2a4 4 0 0 0-4-4H9a4 4 0 0 0-4 4v2", "M16 7a4 4 0 1 1-8 0 4 4 0 0 1 8 0z") }
    val Lock by lazy { lucide("Lock", "M5 11h14a2 2 0 0 1 2 2v7a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-7a2 2 0 0 1 2-2z", "M7 11V7a5 5 0 0 1 10 0v4") }
    val Download by lazy { lucide("Download", "M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4", "m7 10 5 5 5-5", "M12 15V3") }
    val Upload by lazy { lucide("Upload", "M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4", "m17 8-5-5-5 5", "M12 3v12") }
    val RotateCcw by lazy { lucide("RotateCcw", "M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8", "M3 3v5h5") }
    val Columns by lazy { lucide("Columns", "M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z", "M12 3v18") }
    val Rows by lazy { lucide("Rows", "M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z", "M3 12h18") }
    val Maximize by lazy { lucide("Maximize", "M15 3h6v6", "M9 21H3v-6", "m21 3-7 7", "m3 21 7-7") }
    val ArrowUpDown by lazy { lucide("ArrowUpDown", "m21 16-4 4-4-4", "M17 20V4", "m3 8 4-4 4 4", "M7 4v16") }
    val CornerDownLeft by lazy { lucide("CornerDownLeft", "M20 4v7a4 4 0 0 1-4 4H4", "m9 10-5 5 5 5") }
    val ArrowRightToLine by lazy { lucide("ArrowRightToLine", "M17 12H3", "m11 18 6-6-6-6", "M21 5v14") }
    val ArrowDown by lazy { lucide("ArrowDown", "M12 5v14", "m19 12-7 7-7-7") }
    val ArrowUp by lazy { lucide("ArrowUp", "m5 12 7-7 7 7", "M12 19V5") }
    val Pencil by lazy {
        lucide("Pencil", "M21.17 6.81a1 1 0 0 0-3.99-3.99L3.84 16.17a2 2 0 0 0-.5.83l-1.32 4.35a.5.5 0 0 0 .62.62l4.35-1.32a2 2 0 0 0 .83-.5z")
    }
    val Info by lazy { lucide("Info", "M22 12a10 10 0 1 1-20 0 10 10 0 0 1 20 0z", "M12 16v-4", "M12 8h.01") }
    val AlertTriangle by lazy {
        lucide("AlertTriangle", "m21.73 18-8-14a2 2 0 0 0-3.48 0l-8 14A2 2 0 0 0 4 21h16a2 2 0 0 0 1.73-3", "M12 9v4", "M12 17h.01")
    }
    val Key by lazy {
        lucide("Key", "m15.5 7.5 2.3 2.3a1 1 0 0 0 1.4 0l2.1-2.1a1 1 0 0 0 0-1.4L19 4", "m21 2-9.6 9.6", "M13 15.5a5.5 5.5 0 1 1-11 0 5.5 5.5 0 0 1 11 0z")
    }
    val Share by lazy { lucide("Share", "M4 12v8a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-8", "m16 6-4-4-4 4", "M12 2v13") }
    val Activity by lazy { lucide("Activity", "M22 12h-4l-3 9L9 3l-3 9H2") }
    val Clock by lazy { lucide("Clock", "M22 12a10 10 0 1 1-20 0 10 10 0 0 1 20 0z", "M12 6v6l4 2") }
    val Box by lazy {
        lucide(
            "Box", "M21 8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16Z",
            "m3.3 7 8.7 5 8.7-5", "M12 22V12",
        )
    }
    val Link by lazy { lucide("Link", "M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71", "M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71") }
    val Sliders by lazy { lucide("Sliders", "M4 21v-7", "M4 10V3", "M12 21v-9", "M12 8V3", "M20 21v-5", "M20 12V3", "M2 14h4", "M10 8h4", "M18 16h4") }
    val Shield by lazy { lucide("Shield", "M20 13c0 5-3.5 7.5-7.66 8.95a1 1 0 0 1-.67-.01C7.5 20.5 4 18 4 13V6a1 1 0 0 1 1-1c2 0 4.5-1.2 6.24-2.72a1.17 1.17 0 0 1 1.52 0C14.51 3.81 17 5 19 5a1 1 0 0 1 1 1z") }
    val Refresh by lazy { lucide("Refresh", "M3 12a9 9 0 0 1 9-9 9.75 9.75 0 0 1 6.74 2.74L21 8", "M21 3v5h-5", "M21 12a9 9 0 0 1-9 9 9.75 9.75 0 0 1-6.74-2.74L3 16", "M8 16H3v5") }
    val Filter by lazy { lucide("Filter", "M22 3H2l8 9.46V19l4 2v-8.54L22 3z") }
}
