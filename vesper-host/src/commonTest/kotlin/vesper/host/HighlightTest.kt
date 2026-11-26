package vesper.host

import vesper.grid.Style
import vesper.term.FrameCell
import kotlin.test.Test
import kotlin.test.assertEquals

class HighlightTest {
    private val palette = Palette()

    @Test
    fun theCurrentMatchBeatsTheSelectionWhichBeatsAMatch() {
        assertEquals(palette.background, palette.colors(Style.DEFAULT).bg)
        assertEquals(palette.match, palette.colors(Style.DEFAULT, FrameCell.MATCH).bg)
        assertEquals(palette.selection, palette.colors(Style.DEFAULT, FrameCell.SELECTED or FrameCell.MATCH).bg)
        val current = FrameCell.CURRENT_MATCH or FrameCell.MATCH or FrameCell.SELECTED
        assertEquals(palette.currentMatch, palette.colors(Style.DEFAULT, current).bg)
        assertEquals(palette.foreground, palette.colors(Style.DEFAULT, current).fg, "the text keeps its colour")
        assertEquals(null, palette.highlight(FrameCell.CLUSTER))
    }
}
