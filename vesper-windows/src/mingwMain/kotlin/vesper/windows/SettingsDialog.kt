package vesper.windows

import kotlinx.cinterop.UShortVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.toKStringFromUtf16
import kotlinx.cinterop.toLong
import kotlinx.cinterop.wcstr
import platform.windows.CBN_SELCHANGE
import platform.windows.CBS_DROPDOWNLIST
import platform.windows.CB_ADDSTRING
import platform.windows.CB_GETCURSEL
import platform.windows.CB_SETCURSEL
import platform.windows.COLOR_BTNFACE
import platform.windows.CreateFontIndirectW
import platform.windows.CreateWindowExW
import platform.windows.DeleteObject
import platform.windows.EN_KILLFOCUS
import platform.windows.ES_AUTOHSCROLL
import platform.windows.GetFocus
import platform.windows.GetLastError
import platform.windows.GetModuleHandleW
import platform.windows.GetSysColor
import platform.windows.GetSysColorBrush
import platform.windows.GetWindowRect
import platform.windows.GetWindowTextLengthW
import platform.windows.GetWindowTextW
import platform.windows.HDC__
import platform.windows.HFONT
import platform.windows.HMENU__
import platform.windows.HWND
import platform.windows.IDCANCEL
import platform.windows.IDOK
import platform.windows.IsWindowVisible
import platform.windows.NONCLIENTMETRICSW
import platform.windows.RECT
import platform.windows.SPI_GETNONCLIENTMETRICS
import platform.windows.SWP_NOSIZE
import platform.windows.SWP_NOZORDER
import platform.windows.SW_HIDE
import platform.windows.SW_SHOW
import platform.windows.SendMessageW
import platform.windows.SetBkColor
import platform.windows.SetFocus
import platform.windows.SetForegroundWindow
import platform.windows.SetTextColor
import platform.windows.SetWindowPos
import platform.windows.SetWindowTextW
import platform.windows.ShowWindow
import platform.windows.SystemParametersInfoW
import platform.windows.WM_CLOSE
import platform.windows.WM_COMMAND
import platform.windows.WM_CTLCOLORSTATIC
import platform.windows.WM_SETFONT
import platform.windows.WS_CAPTION
import platform.windows.WS_CHILD
import platform.windows.WS_EX_CLIENTEDGE
import platform.windows.WS_SYSMENU
import platform.windows.WS_TABSTOP
import platform.windows.WS_VISIBLE
import platform.windows.WS_VSCROLL
import vesper.config.LiveConfig
import vesper.config.Scheme
import vesper.config.Snapshot
import vesper.host.SettingField

// The layout in DIPs, scaled to the monitor's DPI when the dialog is made.
private const val MARGIN = 12
private const val LABEL_WIDTH = 120
private const val GAP = 8
private const val FIELD_WIDTH = 220
private const val ROW_HEIGHT = 24
private const val ROW_SPACING = 8
private const val STATUS_HEIGHT = 48
private const val DROPDOWN_HEIGHT = 200
private const val WIDTH = MARGIN + LABEL_WIDTH + GAP + FIELD_WIDTH + MARGIN
private const val TEXT_POINTS = 9
private const val POINTS_PER_INCH = 72

/** The first field's control id, the rest following in [SettingField] order; above IDOK and IDCANCEL, which IsDialogMessageW sends for Enter and Escape. */
internal const val FIRST_SETTING_ID = 100

// COLORREF is 0x00BBGGRR.
private const val ERROR_RED = 0x0000_00C8u

// Edit_SetCueBannerText; an edit without comctl32 6 ignores it.
private const val EM_SETCUEBANNER = 0x1501

/**
 * The settings a person changes most, each written to the config file the moment it is committed:
 * Enter, leaving the field, or picking a scheme. The file stays the truth: a refused value shows why
 * in red and changes nothing, and an edit made in the file meanwhile shows up here. Closing only
 * hides the dialog, which [owner]'s window keeps until it closes.
 */
internal class SettingsDialog(
    private val live: LiveConfig,
    owner: HWND,
) : WindowListener,
    AutoCloseable {
    // Null while CreateWindowExW sends its first messages, before there are controls.
    private var controls: Map<SettingField, HWND>? = null
    private var status: HWND? = null
    private var font: HFONT? = null

    val window =
        Window(
            "Settings",
            WIDTH,
            MARGIN + SettingField.entries.size * (ROW_HEIGHT + ROW_SPACING) + STATUS_HEIGHT + MARGIN,
            this,
            style = WS_CAPTION or WS_SYSMENU,
            owner = owner,
            canvas = false,
        )

    init {
        val dpi = window.dpi

        fun px(dips: Int) = dips * dpi / DEFAULT_DPI
        font = messageFont(dpi)
        val made = LinkedHashMap<SettingField, HWND>()
        for ((row, field) in SettingField.entries.withIndex()) {
            val top = px(MARGIN + row * (ROW_HEIGHT + ROW_SPACING))
            child("STATIC", field.label, 0, px(MARGIN), top + px(4), px(LABEL_WIDTH), px(ROW_HEIGHT), 0)
            val left = px(MARGIN + LABEL_WIDTH + GAP)
            made[field] =
                if (field == SettingField.ColorScheme) {
                    val style = WS_TABSTOP or WS_VSCROLL or CBS_DROPDOWNLIST
                    child("COMBOBOX", "", style, left, top, px(FIELD_WIDTH), px(DROPDOWN_HEIGHT), FIRST_SETTING_ID + row).also { combo ->
                        Scheme.entries.forEach { scheme ->
                            memScoped {
                                SendMessageW(
                                    combo,
                                    CB_ADDSTRING.convert(),
                                    0u,
                                    scheme.id.wcstr.ptr
                                        .toLong(),
                                )
                            }
                        }
                    }
                } else {
                    child(
                        "EDIT",
                        "",
                        WS_TABSTOP or ES_AUTOHSCROLL,
                        left,
                        top,
                        px(FIELD_WIDTH),
                        px(ROW_HEIGHT),
                        FIRST_SETTING_ID + row,
                        WS_EX_CLIENTEDGE,
                    )
                }
        }
        // An empty family means the platform's font; the cue says which instead of leaving a blank.
        made[SettingField.FontFamily]?.let { family ->
            memScoped { SendMessageW(family, EM_SETCUEBANNER.convert(), 1u, "Consolas".wcstr.ptr.toLong()) }
        }
        val statusTop = px(MARGIN + SettingField.entries.size * (ROW_HEIGHT + ROW_SPACING))
        status = child("STATIC", "", 0, px(MARGIN), statusTop, px(WIDTH - 2 * MARGIN), px(STATUS_HEIGHT), 0)
        controls = made
        Window.dialogs += window.hwnd
        show(live.snapshot())
    }

    /** The field's control; for tests. */
    internal fun control(field: SettingField): HWND = controls!!.getValue(field)

    /** The status line's text; for tests. */
    internal val statusText: String get() = status?.let(::textOf).orEmpty()

    /** Brings the dialog up over its owner with what the file holds now. */
    fun present(owner: HWND) {
        show(live.snapshot())
        if (IsWindowVisible(window.hwnd) == 0) centre(owner)
        ShowWindow(window.hwnd, SW_SHOW)
        SetForegroundWindow(window.hwnd)
        SetFocus(control(SettingField.FontFamily))
    }

    /** Shows [snapshot], leaving alone the field being typed in. */
    fun show(snapshot: Snapshot) {
        val controls = controls ?: return
        val settings = snapshot.settings
        val focus = GetFocus()
        for ((field, control) in controls) {
            if (field == SettingField.ColorScheme) {
                SendMessageW(control, CB_SETCURSEL.convert(), Scheme.entries.indexOf(settings.scheme).convert(), 0)
            } else if (control != focus) {
                SetWindowTextW(control, field.shown(settings))
            }
        }
        status?.let { SetWindowTextW(it, snapshot.error.orEmpty()) }
    }

    /** Writes [field]'s text to the file, or shows why the file refused it. */
    fun commit(field: SettingField) {
        val control = controls?.get(field) ?: return
        val text =
            if (field == SettingField.ColorScheme) {
                Scheme.entries.getOrNull(SendMessageW(control, CB_GETCURSEL.convert(), 0u, 0).toInt())?.id ?: return
            } else {
                textOf(control)
            }
        val refused = field.commit(text, live.snapshot().settings, live::set)
        if (refused == null) show(live.snapshot()) else status?.let { SetWindowTextW(it, refused) }
    }

    override fun message(
        message: Int,
        wParam: ULong,
        lParam: Long,
    ): Boolean {
        val controls = controls ?: return false
        when (message) {
            WM_COMMAND -> {
                val id = loWord(wParam.toLong())
                val code = hiWord(wParam.toLong())
                val field = SettingField.entries.getOrNull(id - FIRST_SETTING_ID)
                when {
                    field != null && (code == EN_KILLFOCUS || code == CBN_SELCHANGE) -> commit(field)

                    // Enter commits the field it was pressed in, which keeps the focus.
                    id == IDOK -> controls.entries.firstOrNull { it.value == GetFocus() }?.let { commit(it.key) }

                    id == IDCANCEL -> ShowWindow(window.hwnd, SW_HIDE)

                    else -> return false
                }
                return true
            }

            WM_CLOSE -> {
                ShowWindow(window.hwnd, SW_HIDE)
                return true
            }
        }
        return false
    }

    override fun reply(
        message: Int,
        wParam: ULong,
        lParam: Long,
    ): Long? {
        val status = status
        if (message != WM_CTLCOLORSTATIC || status == null || lParam != status.toLong()) return super.reply(message, wParam, lParam)
        val hdc = wParam.toLong().toCPointer<HDC__>()
        SetTextColor(hdc, ERROR_RED)
        SetBkColor(hdc, GetSysColor(COLOR_BTNFACE))
        return GetSysColorBrush(COLOR_BTNFACE).toLong()
    }

    override fun close() {
        // Destroying an edit takes its focus, and a commit then would write a half-typed value.
        controls = null
        Window.dialogs -= window.hwnd
        window.close()
        font?.let { DeleteObject(it) }
        font = null
    }

    private fun child(
        kind: String,
        text: String,
        style: Int,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        id: Int,
        exStyle: Int = 0,
    ): HWND {
        val control =
            CreateWindowExW(
                exStyle.convert(),
                kind,
                text,
                (WS_CHILD or WS_VISIBLE or style).convert(),
                x,
                y,
                width,
                height,
                window.hwnd,
                id.toLong().toCPointer<HMENU__>(),
                GetModuleHandleW(null),
                null,
            ) ?: throw WindowsException("CreateWindowExW $kind", GetLastError().toInt())
        font?.let { SendMessageW(control, WM_SETFONT.convert(), it.toLong().toULong(), 0) }
        return control
    }

    private fun centre(owner: HWND) {
        memScoped {
            val outer = alloc<RECT>()
            val own = alloc<RECT>()
            GetWindowRect(owner, outer.ptr)
            GetWindowRect(window.hwnd, own.ptr)
            val x = (outer.left + outer.right - (own.right - own.left)) / 2
            val y = (outer.top + outer.bottom - (own.bottom - own.top)) / 2
            SetWindowPos(window.hwnd, null, x, y, 0, 0, (SWP_NOSIZE or SWP_NOZORDER).convert())
        }
    }
}

/** The face Windows uses in its own dialogs, at [dpi]; null leaves the controls in the system font. */
private fun messageFont(dpi: Int): HFONT? =
    memScoped {
        val metrics = alloc<NONCLIENTMETRICSW> { cbSize = sizeOf<NONCLIENTMETRICSW>().convert() }
        if (SystemParametersInfoW(SPI_GETNONCLIENTMETRICS.convert(), metrics.cbSize, metrics.ptr, 0u) == 0) return@memScoped null
        // The metrics are for the DPI the process started at; the height is set for this monitor's.
        metrics.lfMessageFont.lfHeight = -(TEXT_POINTS * dpi / POINTS_PER_INCH)
        CreateFontIndirectW(metrics.lfMessageFont.ptr)
    }

/** [control]'s text. */
internal fun textOf(control: HWND): String =
    memScoped {
        val length = GetWindowTextLengthW(control) + 1
        val buffer = allocArray<UShortVar>(length)
        GetWindowTextW(control, buffer, length)
        buffer.toKStringFromUtf16()
    }
