package scull.term

import scull.parser.Csi
import scull.parser.Esc
import scull.parser.Params

// The parser's actions mapped onto state changes: C0 controls, escape sequences and control
// sequences. Separate so the whole vocabulary reads as one table, with the semantics in the files
// it calls.
//
// Sequence names and defaults are xterm's ctlseqs. A parameter of 0 means the default (1 for
// counts and positions), as in ECMA-48; out-of-range values are clamped by the callee, never
// rejected. Sequences outside this table are ignored; so are DCS and APC, and every OSC but the
// titles (Events.kt).

private const val BEL = 0x07
private const val BS = 0x08
private const val HT = 0x09
private const val LF = 0x0A
private const val VT = 0x0B
private const val FF = 0x0C
private const val CR = 0x0D
private const val SO = 0x0E
private const val SI = 0x0F

/** Character-set slots. */
private const val G0 = 0
private const val G1 = 1
private const val G2 = 2
private const val G3 = 3

/** TBC parameters. */
private const val TBC_AT_CURSOR = 0
private const val TBC_ALL = 3

/** The DA1 and DA2 parameter that asks for the report; any other is ignored (xterm). */
private const val DA_REQUEST = 0

private const val PRIVATE_DEC = '?'.code
private const val PRIVATE_SECONDARY = '>'.code
private const val PRIVATE_LESS = '<'.code
private const val PRIVATE_EQUALS = '='.code

/** The flat index of parameter [index], sub-parameters skipped, or -1. */
internal fun paramAt(
    params: Params,
    index: Int,
): Int {
    var seen = -1
    for (k in 0 until params.size) {
        if (!params.isSubParameter(k) && ++seen == index) return k
    }
    return -1
}

/** Parameter [index], with 0 or a missing value read as [default]. */
private fun arg(
    params: Params,
    index: Int,
    default: Int,
): Int {
    val at = paramAt(params, index)
    val value = if (at < 0) 0 else params[at]
    return if (value == 0) default else value
}

/** A count: parameter [index], at least 1. */
private fun count(
    params: Params,
    index: Int,
): Int = arg(params, index, 1)

/** A 1-based position as a 0-based index. */
private fun position(
    params: Params,
    index: Int,
): Int = count(params, index) - 1

/** Whether the sequence has at most one parameter, sub-parameters aside. */
private fun atMostOneParam(params: Params): Boolean = paramAt(params, 1) < 0

internal fun State.executeControl(byte: Int) {
    endCluster()
    when (byte) {
        BEL -> events.ring()
        BS -> back(1)
        HT -> tab(1)
        LF, VT, FF -> linefeed()
        CR -> carriageReturn()
        SO -> charsets.lockShift(G1)
        SI -> charsets.lockShift(G0)
    }
}

internal fun State.dispatchEsc(esc: Esc) {
    endCluster()
    val final = esc.finalByte.toChar()
    when (esc.intermediates.size) {
        0 -> {
            escPlain(final)
        }

        1 -> {
            val intermediate = esc.intermediates[0].toChar()
            if (intermediate == '#') {
                if (final == '8') alignmentTest()
            } else {
                val slot = Charsets.slotFor(intermediate)
                val set = Charset.fromFinal(final)
                if (slot >= 0 && set != null) charsets.designate(slot, set)
            }
        }
    }
}

private fun State.escPlain(final: Char) {
    when (final) {
        '7' -> {
            saveCursor()
        }

        '8' -> {
            restoreCursor()
        }

        '=' -> {
            modes.keypadApp = true
        }

        '>' -> {
            modes.keypadApp = false
        }

        'D' -> {
            index()
        }

        'E' -> {
            index()
            carriageReturn()
        }

        'H' -> {
            tabs.set(col)
        }

        'M' -> {
            reverseIndex()
        }

        'N' -> {
            charsets.singleShift(G2)
        }

        'O' -> {
            charsets.singleShift(G3)
        }

        'c' -> {
            fullReset()
        }

        'n' -> {
            charsets.lockShift(G2)
        }

        'o' -> {
            charsets.lockShift(G3)
        }
    }
}

internal fun State.dispatchCsi(csi: Csi) {
    endCluster()
    val p = csi.params
    val final = csi.finalByte.toChar()
    when (csi.intermediates.size) {
        0 -> {
            when (csi.private) {
                0 -> csiAnsi(csi)
                PRIVATE_DEC -> csiDec(csi)
                PRIVATE_SECONDARY -> csiSecondary(csi)
                PRIVATE_LESS -> if (final == 'u') kittyPop(p)
                PRIVATE_EQUALS -> if (final == 'u') kittySet(p)
            }
        }

        1 -> {
            if (final != 'p') return
            when (csi.intermediates[0].toChar()) {
                '$' -> {
                    when (csi.private) {
                        0 -> reportAnsiMode(arg(p, 0, 0))
                        PRIVATE_DEC -> reportDecMode(arg(p, 0, 0))
                    }
                }

                '!' -> {
                    if (csi.private == 0) softReset()
                }
            }
        }
    }
}

/** Control sequences with no private marker or intermediate. */
private fun State.csiAnsi(csi: Csi) {
    val p = csi.params
    when (val final = csi.finalByte.toChar()) {
        '@' -> {
            insertChars(count(p, 0))
        }

        'A' -> {
            up(count(p, 0))
        }

        'B' -> {
            down(count(p, 0))
        }

        'C' -> {
            forward(count(p, 0))
        }

        'D' -> {
            back(count(p, 0))
        }

        'E' -> {
            down(count(p, 0))
            carriageReturn()
        }

        'F' -> {
            up(count(p, 0))
            carriageReturn()
        }

        'G', '`' -> {
            gotoCol(position(p, 0))
        }

        'H', 'f' -> {
            gotoOrigin(position(p, 0), position(p, 1))
        }

        'I' -> {
            tab(count(p, 0))
        }

        'J' -> {
            eraseDisplay(arg(p, 0, ERASE_TO_END))
        }

        'K' -> {
            eraseLine(arg(p, 0, ERASE_TO_END))
        }

        'L' -> {
            insertLines(count(p, 0))
        }

        'M' -> {
            deleteLines(count(p, 0))
        }

        'P' -> {
            deleteChars(count(p, 0))
        }

        'S' -> {
            scrollUp(count(p, 0))
        }

        // With more parameters `CSI T` is xterm's mouse highlight tracking.
        'T' -> {
            if (atMostOneParam(p)) scrollDown(count(p, 0))
        }

        'X' -> {
            eraseChars(count(p, 0))
        }

        'Z' -> {
            backTab(count(p, 0))
        }

        'a' -> {
            relative(0, count(p, 0))
        }

        'b' -> {
            repeatLast(count(p, 0))
        }

        'd' -> {
            gotoRow(position(p, 0))
        }

        'e' -> {
            relative(count(p, 0), 0)
        }

        'g' -> {
            when (arg(p, 0, TBC_AT_CURSOR)) {
                TBC_AT_CURSOR -> tabs.clear(col)
                TBC_ALL -> tabs.clearAll()
            }
        }

        'c' -> {
            if (arg(p, 0, DA_REQUEST) == DA_REQUEST) primaryAttributes()
        }

        'h', 'l' -> {
            for (k in 0 until p.size) if (!p.isSubParameter(k)) setAnsiMode(p[k], final == 'h')
        }

        'm' -> {
            pen.set(applySgr(pen.style, p))
        }

        'n' -> {
            deviceStatus(arg(p, 0, 0), private = false)
        }

        'r' -> {
            setTopBottom(p)
        }

        // With DECLRMM set, `CSI s` is DECSLRM; otherwise SCOSC (xterm).
        's' -> {
            if (modes.leftRightMargins) setLeftRight(p) else saveCursor()
        }

        'u' -> {
            restoreCursor()
        }
    }
}

/** Control sequences with the `>` marker and no intermediate. */
private fun State.csiSecondary(csi: Csi) {
    val p = csi.params
    when (csi.finalByte.toChar()) {
        'c' -> if (arg(p, 0, DA_REQUEST) == DA_REQUEST) secondaryAttributes()
        'm' -> setKeyModifierOptions(p)
        'n' -> disableKeyModifierOptions(p)
        'u' -> kittyPush(p)
    }
}

/** Control sequences with the `?` marker and no intermediate. */
private fun State.csiDec(csi: Csi) {
    val p = csi.params
    when (val final = csi.finalByte.toChar()) {
        'h', 'l' -> {
            for (k in 0 until p.size) if (!p.isSubParameter(k)) setDecMode(p[k], final == 'h')
        }

        // DECSED and DECSEL: nothing is protected, so they are ED and EL.
        'J' -> {
            eraseDisplay(arg(p, 0, ERASE_TO_END))
        }

        'K' -> {
            eraseLine(arg(p, 0, ERASE_TO_END))
        }

        'n' -> {
            deviceStatus(arg(p, 0, 0), private = true)
        }

        'u' -> {
            kittyQuery()
        }
    }
}

/**
 * DECSLRM: left and right margins, 1-based, defaulting to the screen. Like DECSTBM, a region
 * narrower than two columns is refused and a valid one homes the cursor.
 */
private fun State.setLeftRight(p: Params) {
    val newLeft = position(p, 0)
    val newRight = arg(p, 1, cols).coerceAtMost(cols) - 1
    if (newLeft < newRight) {
        left = newLeft
        right = newRight
        gotoOrigin(0, 0)
    }
}

/**
 * DECSTBM: top and bottom margins, 1-based, defaulting to the screen. A region of fewer than two
 * rows is refused (xterm); a valid one homes the cursor to the origin.
 */
private fun State.setTopBottom(p: Params) {
    val newTop = position(p, 0)
    val newBottom = arg(p, 1, rows).coerceAtMost(rows) - 1
    if (newTop < newBottom) {
        top = newTop
        bottom = newBottom
        gotoOrigin(0, 0)
    }
}
