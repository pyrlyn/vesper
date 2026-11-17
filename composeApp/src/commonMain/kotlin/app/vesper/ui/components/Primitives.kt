package app.vesper.ui.components

import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.vesper.ui.icons.Icons
import app.vesper.ui.theme.Vesper

val RadiusXs = RoundedCornerShape(3.dp)
val RadiusSm = RoundedCornerShape(5.dp)
val RadiusMd = RoundedCornerShape(6.dp)
val RadiusLg = RoundedCornerShape(8.dp)
val RadiusXl = RoundedCornerShape(10.dp)
val Radius2xl = RoundedCornerShape(14.dp)
val RadiusPill = RoundedCornerShape(percent = 50)

@Composable
fun VIcon(icon: ImageVector, size: Dp, tint: Color = Vesper.colors.iconDefault, modifier: Modifier = Modifier) {
    Image(icon, contentDescription = null, modifier = modifier.size(size), colorFilter = ColorFilter.tint(tint))
}

@Composable
fun VText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    maxLines: Int = 1,
    align: androidx.compose.ui.text.style.TextAlign? = null,
) {
    BasicText(
        text = text,
        style = if (align != null) style.copy(color = color, textAlign = align) else style.copy(color = color),
        modifier = modifier,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        softWrap = maxLines > 1,
    )
}

/** Small uppercase section label (UI/Overline). */
@Composable
fun Overline(text: String, modifier: Modifier = Modifier, color: Color = Vesper.colors.textMuted) {
    VText(text.uppercase(), Vesper.type.overline, color, modifier)
}

@Composable
fun Kbd(label: String, modifier: Modifier = Modifier) {
    val c = Vesper.colors
    Box(
        modifier
            .background(c.bgElevated, RadiusXs)
            .border(1.dp, c.borderStrong, RadiusXs)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        VText(label, Vesper.type.kbd, c.textSecondary)
    }
}

@Composable
fun KeyHint(key: String, label: String, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Kbd(key)
        VText(label, Vesper.type.micro, Vesper.colors.textMuted)
    }
}

enum class Tone { Accent, Success, Warning, Danger, Info, Neutral }

@Composable
fun toneColors(tone: Tone): Pair<Color, Color> {
    val c = Vesper.colors
    return when (tone) {
        Tone.Accent -> c.accentSoft to c.accentText
        Tone.Success -> c.successSoft to c.success
        Tone.Warning -> c.warningSoft to c.warning
        Tone.Danger -> c.dangerSoft to c.danger
        Tone.Info -> c.infoSoft to c.info
        Tone.Neutral -> c.bgActive to c.textSecondary
    }
}

@Composable
fun Badge(label: String, tone: Tone = Tone.Accent, modifier: Modifier = Modifier) {
    val (bg, fg) = toneColors(tone)
    Box(modifier.background(bg, RadiusXs).padding(horizontal = 6.dp, vertical = 2.dp)) {
        VText(label, Vesper.type.meta, fg)
    }
}

@Composable
fun StatusDot(color: Color, size: Dp = 7.dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size).background(color, CircleShape))
}

@Composable
fun Chip(
    label: String,
    icon: ImageVector? = null,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val c = Vesper.colors
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    Row(
        modifier
            .background(if (hovered && onClick != null) c.bgElevated else c.bgSurface, RadiusSm)
            .border(1.dp, c.borderDefault, RadiusSm)
            .then(if (onClick != null) Modifier.clip(RadiusSm).clickable(source, null, onClick = onClick).pointerHoverIcon(PointerIcon.Hand) else Modifier.hoverable(source))
            .padding(horizontal = 9.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) VIcon(icon, 11.dp, c.iconDefault)
        VText(label, Vesper.type.meta, c.textSecondary)
    }
}

enum class ButtonKind { Secondary, Accent, Primary, Danger, Ghost }

@Composable
fun VButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    kind: ButtonKind = ButtonKind.Secondary,
    enabled: Boolean = true,
    large: Boolean = false,
) {
    val c = Vesper.colors
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val (bg, border, fg, iconTint) = when (kind) {
        ButtonKind.Secondary -> Quad(if (hovered) c.bgActive else c.bgElevated, c.borderStrong, c.textPrimary, c.danger)
        ButtonKind.Accent -> Quad(if (hovered) c.accentStrong.copy(alpha = 0.26f) else c.accentSoft, c.accentStrong, c.accentText, c.accentText)
        ButtonKind.Primary -> Quad(if (hovered) c.accentDeep else c.accentStrong, c.accentStrong, c.textOnAccent, c.textOnAccent)
        ButtonKind.Danger -> Quad(if (hovered) c.danger.copy(alpha = 0.22f) else c.dangerSoft, c.danger.copy(alpha = 0.7f), c.danger, c.danger)
        ButtonKind.Ghost -> Quad(if (hovered) c.bgActive else Color.Transparent, Color.Transparent, c.textSecondary, c.iconDefault)
    }
    Row(
        modifier
            .clip(RadiusMd)
            .background(bg)
            .border(1.dp, border, RadiusMd)
            .clickable(source, null, enabled = enabled, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = if (large) 12.dp else 10.dp, vertical = if (large) 7.dp else 5.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) VIcon(icon, 12.dp, if (kind == ButtonKind.Secondary) c.iconDefault else iconTint)
        VText(label, if (large) Vesper.type.bodySmall else Vesper.type.caption, if (enabled) fg else c.textMuted)
    }
}

private data class Quad(val a: Color, val b: Color, val c: Color, val d: Color)

@Composable
fun IconButton(
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 16.dp,
    tint: Color = Vesper.colors.iconDefault,
    active: Boolean = false,
    padding: Dp = 7.dp,
) {
    val c = Vesper.colors
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    Box(
        modifier
            .clip(RadiusMd)
            .background(if (hovered || active) c.bgActive else Color.Transparent)
            .clickable(source, null, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(padding),
    ) {
        VIcon(icon, size, if (hovered || active) c.textPrimary else tint)
    }
}

/** Segmented control (Monitor tabs). */
@Composable
fun <T> Segmented(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    val c = Vesper.colors
    Row(
        modifier.background(c.bgInset, RadiusMd).border(1.dp, c.borderSubtle, RadiusMd).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEach { option ->
            val active = option == selected
            Box(
                Modifier
                    .clip(RadiusSm)
                    .background(if (active) c.bgActive else Color.Transparent)
                    .clickable { onSelect(option) }
                    .pointerHoverIcon(PointerIcon.Hand)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
                VText(label(option), Vesper.type.caption, if (active) c.textPrimary else c.textSecondary)
            }
        }
    }
}

@Composable
fun Toggle(checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val c = Vesper.colors
    Box(
        modifier
            .size(width = 22.dp, height = 13.dp)
            .clip(CircleShape)
            .background(if (checked) c.accentStrong else c.borderStrong)
            .clickable { onChange(!checked) }
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(2.dp),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(Modifier.size(9.dp).background(if (checked) Color.White else c.iconDefault, CircleShape))
    }
}

/** Horizontal slider for [value] in [range]; [onChange] fires while dragging or on click. */
@Composable
fun VSlider(value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit, modifier: Modifier = Modifier) {
    val c = Vesper.colors
    val latest by androidx.compose.runtime.rememberUpdatedState(onChange)
    val fraction = ((value - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
    val density = androidx.compose.ui.platform.LocalDensity.current
    val inset = with(density) { 7.dp.toPx() } // thumb radius: the track runs between the thumb centres
    fun emit(x: Float, width: Int) {
        val track = width - 2 * inset
        if (track > 0) latest(range.start + ((x - inset) / track).coerceIn(0f, 1f) * (range.endInclusive - range.start))
    }
    Box(
        modifier
            .size(width = 200.dp, height = 16.dp)
            .pointerHoverIcon(PointerIcon.Hand)
            .pointerInput(range) {
                // Press jumps to the spot, then the value follows the pointer (no touch slop).
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    emit(down.position.x, size.width)
                    horizontalDrag(down.id) { change ->
                        change.consume()
                        emit(change.position.x, size.width)
                    }
                }
            }
            .drawBehind {
                val y = size.height / 2
                val thumb = 7.dp.toPx()
                val track = 4.dp.toPx()
                val x = thumb + fraction * (size.width - 2 * thumb)
                drawLine(c.borderStrong, Offset(thumb, y), Offset(size.width - thumb, y), track, StrokeCap.Round)
                drawLine(c.accentStrong, Offset(thumb, y), Offset(x, y), track, StrokeCap.Round)
                drawCircle(Color.Black.copy(alpha = 0.3f), thumb + 1.dp.toPx(), Offset(x, y + 1.dp.toPx()))
                drawCircle(Color.White, thumb, Offset(x, y))
            },
    )
}

@Composable
fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    shape: Shape = RadiusMd,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val c = Vesper.colors
    Row(
        modifier
            .background(c.bgInset, shape)
            .border(1.dp, c.borderDefault, shape)
            .padding(start = 11.dp, end = 5.dp, top = 5.dp, bottom = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VIcon(Icons.Search, 14.dp)
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) VText(placeholder, Vesper.type.bodySmall, c.textMuted)
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = Vesper.type.bodySmall.copy(color = c.textPrimary),
                cursorBrush = SolidColor(c.accentStrong),
                modifier = if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier,
            )
        }
        trailing()
    }
}

/** Card container used by Monitor tiles, settings groups, etc. */
@Composable
fun Panel(modifier: Modifier = Modifier, padding: Dp = 14.dp, content: @Composable () -> Unit) {
    val c = Vesper.colors
    Box(modifier.background(c.bgSurface, RadiusXl).border(1.dp, c.borderSubtle, RadiusXl).padding(padding)) { content() }
}

/** Popover surface with the design's Shadow/Popover. */
fun Modifier.popover(colors: app.vesper.ui.theme.VesperColors, shape: RoundedCornerShape = RadiusLg): Modifier =
    this.shadow(30.dp, shape, ambientColor = Color.Black, spotColor = Color.Black.copy(alpha = 0.55f))
        .background(colors.bgElevated, shape)
        .border(1.dp, colors.borderStrong, shape)

@Composable
fun HSpace(w: Dp) = Spacer(Modifier.width(w))

/** Dropdown menu anchored to its parent's bottom-right corner. */
@Composable
fun MenuPopup(onDismiss: () -> Unit, alignEnd: Boolean = true, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    val c = Vesper.colors
    androidx.compose.ui.window.Popup(
        alignment = if (alignEnd) Alignment.TopEnd else Alignment.TopStart,
        offset = androidx.compose.ui.unit.IntOffset(0, 70),
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.PopupProperties(focusable = true),
    ) {
        androidx.compose.foundation.layout.Column(
            Modifier.padding(8.dp).width(androidx.compose.foundation.layout.IntrinsicSize.Max).popover(c).padding(5.dp),
            content = content,
        )
    }
}

@Composable
fun MenuItem(label: String, onClick: () -> Unit, icon: ImageVector? = null, shortcut: String? = null, danger: Boolean = false) {
    val c = Vesper.colors
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RadiusSm)
            .background(if (hovered) c.bgActive else Color.Transparent)
            .clickable(source, null, onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) VIcon(icon, 14.dp, if (danger) c.danger else c.iconDefault)
        VText(label, Vesper.type.bodySmall, if (danger) c.danger else c.textPrimary, Modifier.weight(1f))
        if (shortcut != null) {
            HSpace(18.dp)
            VText(shortcut, Vesper.type.meta, c.textMuted)
        }
    }
}

@Composable
fun MenuDivider() {
    Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(1.dp).background(Vesper.colors.borderSubtle))
}

@Composable
fun Checkbox(checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = Vesper.colors
    Box(
        modifier.size(16.dp)
            .background(if (checked) c.accentStrong else Color.Transparent, RadiusXs)
            .border(1.dp, if (checked) c.accentStrong else c.borderStrong, RadiusXs)
            .then(if (enabled) Modifier.clickable { onChange(!checked) } else Modifier),
        contentAlignment = Alignment.Center,
    ) { if (checked) VIcon(Icons.Check, 11.dp, Color.White) }
}

/** Checkbox with a label; the whole row toggles. */
@Composable
fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    androidx.compose.foundation.layout.Row(
        modifier.clickable(interactionSource = null, indication = null) { onChange(!checked) },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked, onChange)
        VText(label, Vesper.type.bodySmall, Vesper.colors.textPrimary)
    }
}

/**
 * Modal dialog over a dimmed window: a title row with an optional icon and close button, a body,
 * and an optional footer band. A click on the scrim dismisses it.
 */
@Composable
fun VDialog(
    title: String,
    onDismiss: () -> Unit,
    width: Dp = 440.dp,
    icon: ImageVector? = null,
    iconTint: Color = Vesper.colors.iconDefault,
    footer: (@Composable androidx.compose.foundation.layout.RowScope.() -> Unit)? = null,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val c = Vesper.colors
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)).clickable(interactionSource = null, indication = null, onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.layout.Column(
            Modifier.width(width).popover(c, RadiusXl).clickable(interactionSource = null, indication = null) {},
        ) {
            androidx.compose.foundation.layout.Row(
                Modifier.fillMaxWidth().padding(start = 18.dp, end = 10.dp, top = 14.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (icon != null) VIcon(icon, 18.dp, iconTint)
                VText(title, Vesper.type.heading, c.textPrimary, Modifier.weight(1f))
                IconButton(Icons.X, onDismiss, size = 14.dp)
            }
            androidx.compose.foundation.layout.Column(
                Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = content,
            )
            if (footer != null) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(c.borderSubtle))
                androidx.compose.foundation.layout.Row(
                    Modifier.fillMaxWidth().background(c.bgInset, androidx.compose.foundation.shape.RoundedCornerShape(bottomStart = 10.dp, bottomEnd = 10.dp))
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    content = footer,
                )
            }
        }
    }
}

/** Toggle chip for filters: accent when active, red for a danger filter. */
@Composable
fun FilterChip(label: String, active: Boolean, danger: Boolean = false, onClick: () -> Unit) {
    val c = Vesper.colors
    val (bg, fg) = when {
        !active -> c.bgActive to c.textSecondary
        danger -> c.dangerSoft to c.danger
        else -> c.accentSoft to c.accentText
    }
    Box(
        Modifier.background(bg, RadiusSm).clickable(onClick = onClick).pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 7.dp, vertical = 3.dp),
    ) { VText(label, Vesper.type.codeSmall, fg) }
}
