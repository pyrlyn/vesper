package app.vesper.ui.theme

import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import org.jetbrains.compose.resources.Font
import vesper.composeapp.generated.resources.Res
import vesper.composeapp.generated.resources.inter_variable
import vesper.composeapp.generated.resources.jetbrains_mono_bold
import vesper.composeapp.generated.resources.jetbrains_mono_medium
import vesper.composeapp.generated.resources.jetbrains_mono_regular
import vesper.composeapp.generated.resources.jetbrains_mono_semibold

/** Text styles named after the Figma text styles (UI/… and Mono/…). */
@Immutable
data class VesperType(
    val ui: FontFamily,
    val mono: FontFamily,
    val display: TextStyle,
    val heading: TextStyle,
    val title: TextStyle,
    val body: TextStyle,
    val bodyMedium: TextStyle,
    val bodySmall: TextStyle,
    val caption: TextStyle,
    val micro: TextStyle,
    val overline: TextStyle,
    val code: TextStyle,
    val codeSmall: TextStyle,
    val command: TextStyle,
    val prompt: TextStyle,
    val meta: TextStyle,
    val kbd: TextStyle,
    val metric: TextStyle,
)

@Composable
private fun interFamily(): FontFamily {
    val regular = Font(Res.font.inter_variable, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400)))
    val medium = Font(Res.font.inter_variable, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500)))
    val semi = Font(Res.font.inter_variable, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600)))
    val bold = Font(Res.font.inter_variable, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700)))
    return remember(regular, medium, semi, bold) { FontFamily(regular, medium, semi, bold) }
}

@Composable
private fun jetbrainsMonoFamily(): FontFamily {
    val regular = Font(Res.font.jetbrains_mono_regular, FontWeight.Normal)
    val medium = Font(Res.font.jetbrains_mono_medium, FontWeight.Medium)
    val semi = Font(Res.font.jetbrains_mono_semibold, FontWeight.SemiBold)
    val bold = Font(Res.font.jetbrains_mono_bold, FontWeight.Bold)
    return remember(regular, medium, semi, bold) { FontFamily(regular, medium, semi, bold) }
}

/** [codeScale] lets the user change terminal font size (1.0 = design default of 12.5sp). */
@Composable
fun rememberVesperType(monoFamilyName: String, codeScale: Float, ligatures: Boolean = false): VesperType {
    val ui = interFamily()
    val bundledMono = jetbrainsMonoFamily()
    val mono = remember(monoFamilyName, bundledMono) { resolveMonoFamily(monoFamilyName) ?: bundledMono }
    return remember(ui, mono, codeScale, ligatures) {
        val features = if (ligatures) null else "calt 0, liga 0"
        fun uiStyle(size: Float, line: Float, weight: FontWeight = FontWeight.Normal, spacing: Float = 0f) =
            TextStyle(fontFamily = ui, fontSize = size.sp, lineHeight = line.sp, fontWeight = weight, letterSpacing = spacing.em)

        fun monoStyle(size: Float, line: Float, weight: FontWeight = FontWeight.Normal, spacing: Float = 0f) =
            TextStyle(fontFamily = mono, fontSize = size.sp, lineHeight = line.sp, fontWeight = weight, letterSpacing = spacing.em, fontFeatureSettings = features)
        VesperType(
            ui = ui,
            mono = mono,
            display = uiStyle(22f, 28f, FontWeight.SemiBold, -0.01f),
            heading = uiStyle(16f, 22f, FontWeight.SemiBold, -0.005f),
            title = uiStyle(13f, 18f, FontWeight.SemiBold),
            body = uiStyle(13f, 18f),
            bodyMedium = uiStyle(13f, 18f, FontWeight.Medium),
            bodySmall = uiStyle(12.5f, 17f),
            caption = uiStyle(11.5f, 16f),
            micro = uiStyle(11f, 14f),
            overline = uiStyle(10.5f, 13f, FontWeight.SemiBold, 0.08f),
            code = monoStyle(12.5f * codeScale, 20f * codeScale),
            codeSmall = monoStyle(11.5f, 16f),
            command = monoStyle(13f * codeScale, 17f * codeScale),
            prompt = monoStyle(13.5f * codeScale, 20f * codeScale),
            meta = monoStyle(10.5f, 14f),
            kbd = monoStyle(10.5f, 14f, FontWeight.Medium),
            metric = monoStyle(20f, 26f, FontWeight.Medium, -0.01f),
        )
    }
}

/** Optional system font chosen in settings; null falls back to the bundled JetBrains Mono. */
expect fun resolveMonoFamily(name: String): FontFamily?

val LocalColors = staticCompositionLocalOf { VesperDark }
val LocalType = staticCompositionLocalOf<VesperType> { error("VesperTheme not set") }

object Vesper {
    val colors: VesperColors @Composable get() = LocalColors.current
    val type: VesperType @Composable get() = LocalType.current
}

@Composable
fun VesperTheme(colors: VesperColors, type: VesperType, content: @Composable () -> Unit) {
    val selection = remember(colors) { TextSelectionColors(colors.accentStrong, colors.accentStrong.copy(alpha = 0.35f)) }
    CompositionLocalProvider(
        LocalColors provides colors,
        LocalType provides type,
        LocalTextSelectionColors provides selection,
        content = content,
    )
}
