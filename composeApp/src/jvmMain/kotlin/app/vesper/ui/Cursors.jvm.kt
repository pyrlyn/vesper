package app.vesper.ui

import androidx.compose.ui.input.pointer.PointerIcon
import java.awt.Cursor

actual fun resizeHorizontalCursor(): PointerIcon = PointerIcon(Cursor(Cursor.E_RESIZE_CURSOR))

actual fun resizeVerticalCursor(): PointerIcon = PointerIcon(Cursor(Cursor.N_RESIZE_CURSOR))
