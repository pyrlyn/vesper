package app.vesper.ui.theme

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.SystemFont

/** Uses an installed system font when the user picked one other than the bundled JetBrains Mono. */
actual fun resolveMonoFamily(name: String): FontFamily? {
    if (name.isBlank() || name == BUNDLED_MONO) return null
    return FontFamily(SystemFont(name, FontWeight.Normal), SystemFont(name, FontWeight.Bold))
}

const val BUNDLED_MONO = "JetBrains Mono"
