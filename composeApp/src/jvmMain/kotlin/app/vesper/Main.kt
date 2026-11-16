package app.vesper

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import app.vesper.app.AppState
import app.vesper.platform.HostOs
import app.vesper.platform.MacTitleBar
import app.vesper.platform.JvmServices
import app.vesper.terminal.LoginEnvironment
import app.vesper.ui.VesperApp
import app.vesper.ui.WindowChrome
import app.vesper.ui.components.VIcon
import app.vesper.ui.icons.Icons
import app.vesper.ui.requestQuit
import app.vesper.ui.theme.ThemeId
import app.vesper.ui.theme.Vesper
import app.vesper.ui.theme.VesperTheme
import app.vesper.ui.theme.colorsFor
import app.vesper.ui.theme.rememberVesperType
import java.awt.Cursor
import java.awt.Desktop
import java.awt.Dimension
import java.awt.MouseInfo
import java.awt.Rectangle
import java.awt.desktop.QuitStrategy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

fun main() {
    System.setProperty("apple.awt.application.name", "Vesper")
    // Resolve the login PATH early so the first shell does not wait for it.
    Thread { LoginEnvironment.path }.apply { isDaemon = true }.start()

    application {
        val services = remember { JvmServices() }
        val scope = rememberCoroutineScope()
        val state = remember { AppState(services, scope) }
        val os = HostOs.current
        val quit: () -> Unit = {
            state.shutdown()
            exitApplication()
        }
        LaunchedEffect(Unit) { services.monitor.start(scope) }
        DisposableEffect(Unit) {
            val hook = Thread { state.shutdown() }
            Runtime.getRuntime().addShutdownHook(hook)
            if (os == HostOs.MacOS && Desktop.isDesktopSupported()) {
                runCatching {
                    val desktop = Desktop.getDesktop()
                    desktop.setQuitStrategy(QuitStrategy.CLOSE_ALL_WINDOWS)
                    desktop.setQuitHandler { _, response ->
                        response.cancelQuit()
                        javax.swing.SwingUtilities.invokeLater { requestQuit(state, quit) }
                    }
                }
            }
            onDispose { runCatching { Runtime.getRuntime().removeShutdownHook(hook) } }
        }

        val windowState = rememberWindowState(width = 1280.dp, height = 820.dp, position = WindowPosition(Alignment.Center))
        val settings = state.settings
        var systemDark by remember { mutableStateOf(true) }
        LaunchedEffect(settings.followSystem) {
            while (settings.followSystem) {
                systemDark = withContext(Dispatchers.IO) { services.systemPrefersDark() }
                delay(3000)
            }
        }
        val theme = if (settings.followSystem) (if (systemDark) ThemeId.VesperDark else ThemeId.VesperLight) else settings.theme
        val colors = remember(theme, settings.accent) { colorsFor(theme, settings.accent) }

        Window(
            onCloseRequest = { requestQuit(state, quit) },
            state = windowState,
            title = "Vesper",
            undecorated = os != HostOs.MacOS,
            icon = remember { VesperIconPainter(colors.accentStrong) },
        ) {
            LaunchedEffect(Unit) {
                window.minimumSize = Dimension(900, 560)
                if (os == HostOs.MacOS) {
                    window.rootPane.putClientProperty("apple.awt.fullWindowContent", true)
                    window.rootPane.putClientProperty("apple.awt.transparentTitleBar", true)
                    window.rootPane.putClientProperty("apple.awt.windowTitleVisible", false)
                    MacTitleBar.install(window.title, MacTitleBar.Style.Unified)
                }
            }
            LaunchedEffect(colors.isDark) {
                if (os == HostOs.MacOS) {
                    window.rootPane.putClientProperty("apple.awt.windowAppearance", if (colors.isDark) "NSAppearanceNameDarkAqua" else "NSAppearanceNameAqua")
                }
                window.background = java.awt.Color(colors.bgWindow.red, colors.bgWindow.green, colors.bgWindow.blue)
            }
            val type = rememberVesperType(settings.fontFamily, settings.fontScale, settings.ligatures)
            val chrome = remember(os) { chromeFor(os, windowState, quit = { requestQuit(state, quit) }) }
            VesperTheme(colors, type) {
                Box(Modifier.fillMaxSize()) {
                    VesperApp(state, chrome, quit)
                    if (os != HostOs.MacOS && windowState.placement == WindowPlacement.Floating) ResizeEdges(window)
                }
            }
        }
    }
}

private fun FrameWindowScope.chromeFor(os: HostOs, windowState: WindowState, quit: () -> Unit): WindowChrome {
    val toggleMaximize = {
        windowState.placement = if (windowState.placement == WindowPlacement.Maximized) WindowPlacement.Floating else WindowPlacement.Maximized
    }
    val drag: @Composable (Modifier, @Composable () -> Unit) -> Unit = { m, content ->
        WindowDraggableArea(m.pointerInput(Unit) { detectTapGestures(onDoubleTap = { toggleMaximize() }) }) { content() }
    }
    return when (os) {
        HostOs.MacOS -> WindowChrome(leadingInset = 72.dp, titleBarHeight = 52.dp, dragArea = drag)
        HostOs.Windows -> WindowChrome(dragArea = drag, controls = { WindowsControls(windowState, toggleMaximize, quit) })
        HostOs.Linux -> WindowChrome(dragArea = drag, controls = { LinuxControls(windowState, toggleMaximize, quit) })
    }
}

@Composable
private fun WindowsControls(windowState: WindowState, toggleMaximize: () -> Unit, close: () -> Unit) {
    Row {
        CaptionButton(Icons.Minus, hoverColor = null) { windowState.isMinimized = true }
        CaptionButton(if (windowState.placement == WindowPlacement.Maximized) Icons.Copy else Icons.Square, hoverColor = null, iconSize = 11) { toggleMaximize() }
        CaptionButton(Icons.X, hoverColor = Color(0xFFE81123)) { close() }
    }
}

@Composable
private fun CaptionButton(icon: androidx.compose.ui.graphics.vector.ImageVector, hoverColor: Color?, iconSize: Int = 13, onClick: () -> Unit) {
    val c = Vesper.colors
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    Box(
        Modifier
            .width(44.dp)
            .height(32.dp)
            .hoverable(source)
            .background(if (hovered) hoverColor ?: c.bgActive else Color.Transparent)
            .pointerInput(Unit) { detectTapGestures(onTap = { onClick() }) },
        contentAlignment = Alignment.Center,
    ) {
        VIcon(icon, iconSize.dp, if (hovered && hoverColor != null) Color.White else c.textSecondary)
    }
}

@Composable
private fun LinuxControls(windowState: WindowState, toggleMaximize: () -> Unit, close: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        RoundButton(Icons.Minus) { windowState.isMinimized = true }
        RoundButton(if (windowState.placement == WindowPlacement.Maximized) Icons.Copy else Icons.Square, 10) { toggleMaximize() }
        RoundButton(Icons.X) { close() }
    }
}

@Composable
private fun RoundButton(icon: androidx.compose.ui.graphics.vector.ImageVector, iconSize: Int = 12, onClick: () -> Unit) {
    val c = Vesper.colors
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    Box(
        Modifier
            .size(24.dp)
            .hoverable(source)
            .background(if (hovered) c.bgActive else c.bgElevated, CircleShape)
            .pointerInput(Unit) { detectTapGestures(onTap = { onClick() }) },
        contentAlignment = Alignment.Center,
    ) {
        VIcon(icon, iconSize.dp, c.textPrimary)
    }
}

/** Invisible borders that resize an undecorated window. */
@Composable
private fun ResizeEdges(window: java.awt.Window) {
    val edge = 5.dp
    Box(Modifier.fillMaxSize()) {
        ResizeHandle(window, Modifier.fillMaxHeight().width(edge).align(Alignment.CenterStart), Cursor.W_RESIZE_CURSOR, left = true)
        ResizeHandle(window, Modifier.fillMaxHeight().width(edge).align(Alignment.CenterEnd), Cursor.E_RESIZE_CURSOR, right = true)
        ResizeHandle(window, Modifier.fillMaxWidth().height(edge).align(Alignment.TopCenter), Cursor.N_RESIZE_CURSOR, top = true)
        ResizeHandle(window, Modifier.fillMaxWidth().height(edge).align(Alignment.BottomCenter), Cursor.S_RESIZE_CURSOR, bottom = true)
        ResizeHandle(window, Modifier.size(10.dp).align(Alignment.TopStart), Cursor.NW_RESIZE_CURSOR, left = true, top = true)
        ResizeHandle(window, Modifier.size(10.dp).align(Alignment.TopEnd), Cursor.NE_RESIZE_CURSOR, right = true, top = true)
        ResizeHandle(window, Modifier.size(10.dp).align(Alignment.BottomStart), Cursor.SW_RESIZE_CURSOR, left = true, bottom = true)
        ResizeHandle(window, Modifier.size(10.dp).align(Alignment.BottomEnd), Cursor.SE_RESIZE_CURSOR, right = true, bottom = true)
    }
}

@Composable
private fun ResizeHandle(
    window: java.awt.Window,
    modifier: Modifier,
    cursor: Int,
    left: Boolean = false,
    right: Boolean = false,
    top: Boolean = false,
    bottom: Boolean = false,
) {
    Box(
        modifier
            .pointerHoverIcon(PointerIcon(Cursor(cursor)))
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val down = awaitPointerEvent()
                        if (down.type != PointerEventType.Press) continue
                        val start = MouseInfo.getPointerInfo()?.location ?: continue
                        val bounds = window.bounds
                        val min = window.minimumSize
                        while (true) {
                            val e = awaitPointerEvent()
                            if (e.type == PointerEventType.Release) break
                            if (e.type != PointerEventType.Move) continue
                            val p = MouseInfo.getPointerInfo()?.location ?: continue
                            val dx = p.x - start.x
                            val dy = p.y - start.y
                            val r = Rectangle(bounds)
                            if (right) r.width = (bounds.width + dx).coerceAtLeast(min.width)
                            if (bottom) r.height = (bounds.height + dy).coerceAtLeast(min.height)
                            if (left) {
                                r.width = (bounds.width - dx).coerceAtLeast(min.width)
                                r.x = bounds.x + bounds.width - r.width
                            }
                            if (top) {
                                r.height = (bounds.height - dy).coerceAtLeast(min.height)
                                r.y = bounds.y + bounds.height - r.height
                            }
                            window.bounds = r
                            e.changes.forEach { it.consume() }
                        }
                    }
                }
            },
    )
}

/** App icon: accent tile with a prompt glyph. */
private class VesperIconPainter(private val accent: Color) : Painter() {
    override val intrinsicSize: Size = Size(256f, 256f)

    override fun DrawScope.onDraw() {
        val s = size.minDimension
        drawRoundRect(Color(0xFF0D0F15), size = Size(s, s), cornerRadius = androidx.compose.ui.geometry.CornerRadius(s * 0.22f))
        drawRoundRect(
            accent,
            topLeft = Offset(s * 0.04f, s * 0.04f),
            size = Size(s * 0.92f, s * 0.92f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(s * 0.19f),
            style = Stroke(s * 0.03f),
        )
        val w = s * 0.075f
        val chevron = Path().apply {
            moveTo(s * 0.28f, s * 0.34f)
            lineTo(s * 0.46f, s * 0.5f)
            lineTo(s * 0.28f, s * 0.66f)
        }
        drawPath(chevron, accent, style = Stroke(w, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
        drawLine(Color(0xFFE7E9F0), Offset(s * 0.54f, s * 0.68f), Offset(s * 0.74f, s * 0.68f), w, StrokeCap.Round)
    }
}
