package app.vesper.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/** Design tokens from the Figma "Modern terminal" file (var(--…) names kept as property names). */
@Immutable
data class VesperColors(
    val isDark: Boolean,
    val bgApp: Color,
    val bgWindow: Color,
    val bgSidebar: Color,
    val bgPanel: Color,
    val bgSurface: Color,
    val bgInset: Color,
    val bgElevated: Color,
    val bgActive: Color,
    val borderSubtle: Color,
    val borderDefault: Color,
    val borderStrong: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val textCode: Color,
    val iconDefault: Color,
    val accent: Color,
    val accentStrong: Color,
    val accentDeep: Color,
    val accentText: Color,
    val accentSoft: Color,
    val success: Color,
    val successSoft: Color,
    val warning: Color,
    val danger: Color,
    val info: Color,
    val magenta: Color,
    /** ANSI 0–15. */
    val ansi: List<Color>,
) {
    val borderFocus: Color get() = accentStrong
    val textOnAccent: Color get() = Color.White
    val dangerSoft: Color get() = danger.copy(alpha = 0.14f)
    val warningSoft: Color get() = warning.copy(alpha = 0.14f)
    val infoSoft: Color get() = info.copy(alpha = 0.14f)
}

enum class ThemeId(val title: String) {
    VesperDark("Vesper Dark"),
    VesperLight("Vesper Light"),
    Nord("Nord"),
    Solarized("Solarized"),
}

/** Accent options shown in Settings → Appearance. */
enum class AccentId(val swatch: Color) {
    Violet(Color(0xFF7C6CFF)),
    Blue(Color(0xFF3B82F6)),
    Green(Color(0xFF22C55E)),
    Amber(Color(0xFFF59E0B)),
    Pink(Color(0xFFEC4899)),
    Cyan(Color(0xFF06B6D4)),
}

private fun hex(v: Long) = Color(v)

private val darkAnsi = listOf(
    hex(0xFF1D2230), hex(0xFFF87171), hex(0xFF4ADE9A), hex(0xFFF6C35B),
    hex(0xFF7C6CFF), hex(0xFFF28FD0), hex(0xFF67D3F5), hex(0xFFB5BBCC),
    hex(0xFF5B6275), hex(0xFFFCA5A5), hex(0xFF86EFAC), hex(0xFFFDE68A),
    hex(0xFFA5B4FC), hex(0xFFF9A8D4), hex(0xFFA5F3FC), hex(0xFFE7E9F0),
)

private val lightAnsi = listOf(
    hex(0xFF151821), hex(0xFFDC3D3D), hex(0xFF16A36A), hex(0xFFB9800F),
    hex(0xFF5B4FD6), hex(0xFFC2459A), hex(0xFF1B8FB8), hex(0xFF4D5468),
    hex(0xFF8A91A5), hex(0xFFE25C5C), hex(0xFF1FBF7E), hex(0xFFD49A1F),
    hex(0xFF6A5CF0), hex(0xFFD35BAE), hex(0xFF2AA6D1), hex(0xFFC8CCD8),
)

val VesperDark = VesperColors(
    isDark = true,
    bgApp = hex(0xFF08090D),
    bgWindow = hex(0xFF0D0F15),
    bgSidebar = hex(0xFF0A0C11),
    bgPanel = hex(0xFF0F1218),
    bgSurface = hex(0xFF12151D),
    bgInset = hex(0xFF10131A),
    bgElevated = hex(0xFF161A24),
    bgActive = hex(0xFF1D2230),
    borderSubtle = hex(0xFF181C27),
    borderDefault = hex(0xFF232838),
    borderStrong = hex(0xFF2A3041),
    textPrimary = hex(0xFFE7E9F0),
    textSecondary = hex(0xFF9AA1B5),
    textMuted = hex(0xFF5B6275),
    textCode = hex(0xFFB5BBCC),
    iconDefault = hex(0xFF8C93A8),
    accent = hex(0xFF9B8CFF),
    accentStrong = hex(0xFF7C6CFF),
    accentDeep = hex(0xFF5B4FD6),
    accentText = hex(0xFFC9C2FF),
    accentSoft = Color(0x297C6CFF),
    success = hex(0xFF4ADE9A),
    successSoft = Color(0x244ADE9A),
    warning = hex(0xFFF6C35B),
    danger = hex(0xFFF87171),
    info = hex(0xFF67D3F5),
    magenta = hex(0xFFF28FD0),
    ansi = darkAnsi,
)

val VesperLight = VesperColors(
    isDark = false,
    bgApp = hex(0xFFE4E7EE),
    bgWindow = hex(0xFFF7F8FA),
    bgSidebar = hex(0xFFF0F2F6),
    bgPanel = hex(0xFFFFFFFF),
    bgSurface = hex(0xFFFFFFFF),
    bgInset = hex(0xFFF0F2F6),
    bgElevated = hex(0xFFFFFFFF),
    bgActive = hex(0xFFE4E7EE),
    borderSubtle = hex(0xFFE6E8EE),
    borderDefault = hex(0xFFD9DCE5),
    borderStrong = hex(0xFFC8CCD8),
    textPrimary = hex(0xFF151821),
    textSecondary = hex(0xFF4D5468),
    textMuted = hex(0xFF8A91A5),
    textCode = hex(0xFF2A3041),
    iconDefault = hex(0xFF6B7287),
    accent = hex(0xFF6A5CF0),
    accentStrong = hex(0xFF5B4FD6),
    accentDeep = hex(0xFF5B4FD6),
    accentText = hex(0xFF5B4FD6),
    accentSoft = Color(0x1F6A5CF0),
    success = hex(0xFF16A36A),
    successSoft = Color(0x1F16A36A),
    warning = hex(0xFFB9800F),
    danger = hex(0xFFDC3D3D),
    info = hex(0xFF1B8FB8),
    magenta = hex(0xFFC2459A),
    ansi = lightAnsi,
)

val NordColors = VesperDark.copy(
    bgApp = hex(0xFF242933),
    bgWindow = hex(0xFF2E3440),
    bgSidebar = hex(0xFF2A303B),
    bgPanel = hex(0xFF2E3440),
    bgSurface = hex(0xFF3B4252),
    bgInset = hex(0xFF333A47),
    bgElevated = hex(0xFF3B4252),
    bgActive = hex(0xFF434C5E),
    borderSubtle = hex(0xFF3B4252),
    borderDefault = hex(0xFF434C5E),
    borderStrong = hex(0xFF4C566A),
    textPrimary = hex(0xFFECEFF4),
    textSecondary = hex(0xFFD8DEE9),
    textMuted = hex(0xFF7B88A1),
    textCode = hex(0xFFD8DEE9),
    iconDefault = hex(0xFF9AA5BA),
    success = hex(0xFFA3BE8C),
    successSoft = Color(0x24A3BE8C),
    warning = hex(0xFFEBCB8B),
    danger = hex(0xFFBF616A),
    info = hex(0xFF88C0D0),
    magenta = hex(0xFFB48EAD),
    ansi = listOf(
        hex(0xFF3B4252), hex(0xFFBF616A), hex(0xFFA3BE8C), hex(0xFFEBCB8B),
        hex(0xFF81A1C1), hex(0xFFB48EAD), hex(0xFF88C0D0), hex(0xFFE5E9F0),
        hex(0xFF4C566A), hex(0xFFBF616A), hex(0xFFA3BE8C), hex(0xFFEBCB8B),
        hex(0xFF81A1C1), hex(0xFFB48EAD), hex(0xFF8FBCBB), hex(0xFFECEFF4),
    ),
)

val SolarizedColors = VesperDark.copy(
    bgApp = hex(0xFF001E26),
    bgWindow = hex(0xFF002B36),
    bgSidebar = hex(0xFF00252F),
    bgPanel = hex(0xFF002B36),
    bgSurface = hex(0xFF073642),
    bgInset = hex(0xFF04313C),
    bgElevated = hex(0xFF073642),
    bgActive = hex(0xFF0B4452),
    borderSubtle = hex(0xFF073642),
    borderDefault = hex(0xFF0B4452),
    borderStrong = hex(0xFF175563),
    textPrimary = hex(0xFFEEE8D5),
    textSecondary = hex(0xFF93A1A1),
    textMuted = hex(0xFF586E75),
    textCode = hex(0xFF93A1A1),
    iconDefault = hex(0xFF839496),
    success = hex(0xFF859900),
    successSoft = Color(0x24859900),
    warning = hex(0xFFB58900),
    danger = hex(0xFFDC322F),
    info = hex(0xFF2AA198),
    magenta = hex(0xFFD33682),
    ansi = listOf(
        hex(0xFF073642), hex(0xFFDC322F), hex(0xFF859900), hex(0xFFB58900),
        hex(0xFF268BD2), hex(0xFFD33682), hex(0xFF2AA198), hex(0xFFEEE8D5),
        hex(0xFF586E75), hex(0xFFCB4B16), hex(0xFF93A1A1), hex(0xFF839496),
        hex(0xFF6C71C4), hex(0xFFD33682), hex(0xFF2AA198), hex(0xFFFDF6E3),
    ),
)

fun colorsFor(theme: ThemeId, accent: AccentId): VesperColors {
    val base = when (theme) {
        ThemeId.VesperDark -> VesperDark
        ThemeId.VesperLight -> VesperLight
        ThemeId.Nord -> NordColors
        ThemeId.Solarized -> SolarizedColors
    }
    if (accent == AccentId.Violet) return base
    val strong = accent.swatch
    return base.copy(
        accent = if (base.isDark) lerpWhite(strong, 0.25f) else strong,
        accentStrong = strong,
        accentDeep = strong,
        accentText = if (base.isDark) lerpWhite(strong, 0.6f) else strong,
        accentSoft = strong.copy(alpha = if (base.isDark) 0.16f else 0.12f),
    )
}

private fun lerpWhite(c: Color, t: Float) = Color(
    red = c.red + (1f - c.red) * t,
    green = c.green + (1f - c.green) * t,
    blue = c.blue + (1f - c.blue) * t,
    alpha = 1f,
)
