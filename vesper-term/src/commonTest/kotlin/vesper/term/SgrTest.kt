package vesper.term

import vesper.grid.Attrs
import vesper.grid.Color
import vesper.grid.Style
import vesper.grid.Underline
import vesper.parser.Csi
import vesper.parser.Handler
import vesper.parser.Parser
import kotlin.test.Test
import kotlin.test.assertEquals

private fun sgr(
    params: String,
    start: Style = Style.DEFAULT,
): Style {
    var style = start
    val capture =
        object : Handler {
            override fun csiDispatch(csi: Csi) {
                style = applySgr(style, csi.params)
            }
        }
    Parser().feed("$CSI${params}m".encodeToByteArray(), capture)
    return style
}

class SgrTest {
    @Test
    fun emptyAndZeroResetEverything() {
        val styled = sgr("1;3;31;44;4:3;58;5;1")
        assertEquals(Style.DEFAULT, sgr("", styled))
        assertEquals(Style.DEFAULT, sgr("0", styled))
    }

    @Test
    fun attributesSetAndClearInPairs() {
        val on = sgr("1;2;3;5;7;8;9;53")
        val all = Attrs.BOLD or Attrs.DIM or Attrs.ITALIC or Attrs.BLINK or Attrs.INVERSE or Attrs.HIDDEN or Attrs.STRIKE or Attrs.OVERLINE
        assertEquals(all, on.attrs)
        assertEquals(0, sgr("22;23;25;27;28;29;55", on).attrs)
        assertEquals(Attrs.BLINK, sgr("6").attrs)
    }

    @Test
    fun sixteenColoursMapToPaletteIndices() {
        var s = sgr("31;42")
        assertEquals(Color.indexed(1) to Color.indexed(2), s.fg to s.bg)
        s = sgr("97;100")
        assertEquals(Color.indexed(15) to Color.indexed(8), s.fg to s.bg)
        s = sgr("39;49", s)
        assertEquals(Color.DEFAULT to Color.DEFAULT, s.fg to s.bg)
    }

    @Test
    fun semicolonAndColonFormsGiveTheSameColours() {
        val expected = Color.indexed(200) to Color.rgb(1, 2, 3)
        for (params in listOf("38;5;200;48;2;1;2;3", "38:5:200;48:2::1:2:3", "38:5:200;48:2:1:2:3", "38:5:200;48:2:0:1:2:3")) {
            val s = sgr(params)
            assertEquals(expected, s.fg to s.bg, params)
        }
    }

    @Test
    fun blackIsAColourNotTheDefault() {
        val s = sgr("38;2;0;0;0;48:5:0")
        assertEquals(Color.rgb(0, 0, 0) to Color.indexed(0), s.fg to s.bg)
    }

    @Test
    fun parametersAfterASemicolonColourStillApply() {
        val s = sgr("38;2;10;20;30;1;4")
        assertEquals(Color.rgb(10, 20, 30), s.fg)
        assertEquals(Attrs.BOLD, s.attrs)
        assertEquals(Underline.Single, s.underline)
    }

    @Test
    fun outOfRangeColoursAreIgnored() {
        assertEquals(Color.indexed(1), sgr("31;38;5;256").fg)
        assertEquals(Color.indexed(1), sgr("31;38:2::1:2:300").fg)
        assertEquals(Color.indexed(1), sgr("31;38;5").fg, "truncated colour")
        assertEquals(Color.indexed(1), sgr("31;38;2;1;2").fg, "truncated direct colour")
        assertEquals(Color.indexed(1), sgr("31;38:5").fg, "colon form without an index")
        assertEquals(Color.indexed(1), sgr("31;38:2:1:2").fg, "colon form with two components")
    }

    @Test
    fun aBadSemicolonColourStillTakesItsParameters() {
        // The 1 is the red of a colour that is out of range, not bold.
        val s = sgr("38;2;300;1;1;3")
        assertEquals(Color.DEFAULT, s.fg)
        assertEquals(Attrs.ITALIC, s.attrs)
        assertEquals(Attrs.BOLD, sgr("38;7;1").attrs, "an unknown colour space takes only itself")
    }

    @Test
    fun underlineShapesAndColour() {
        assertEquals(Underline.Curly, sgr("4:3").underline)
        assertEquals(Underline.Dashed, sgr("4:5").underline)
        assertEquals(Underline.Double, sgr("21").underline)
        assertEquals(Underline.None, sgr("4:3;4:0").underline)
        assertEquals(Underline.Dotted, sgr("4:4;4:9").underline, "unknown shape")
        assertEquals(Underline.None, sgr("4;24").underline)
        val s = sgr("58:2::255:0:0")
        assertEquals(Color.rgb(255, 0, 0), s.underlineColor)
        assertEquals(Color.DEFAULT, sgr("59", s).underlineColor)
        assertEquals(Color.indexed(9), sgr("58;5;9").underlineColor)
    }

    @Test
    fun unknownCodesAreIgnored() {
        assertEquals(Attrs.BOLD or Attrs.ITALIC, sgr("1;12345;3").attrs)
    }
}
