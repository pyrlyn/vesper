package scull.input

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val D = KittyFlags.DISAMBIGUATE
private val EVENTS = D + KittyFlags.REPORT_EVENTS
private val ALL = KittyFlags.REPORT_ALL_KEYS
private val ALL_TEXT = ALL + KittyFlags.REPORT_TEXT
private val ALTERNATES = D + KittyFlags.REPORT_ALTERNATES

class KittyTest {
    private fun encode(
        event: KeyEvent,
        flags: KittyFlags,
    ) = bytesOf { encodeKitty(event, flags, it) }

    private fun enc(
        flags: KittyFlags,
        key: Key,
        mods: Modifiers,
        text: String = "",
    ) = encode(event(key, mods, text), flags)

    private fun withAction(
        flags: KittyFlags,
        key: Key,
        mods: Modifiers,
        action: KeyAction,
    ) = encode(KeyEvent(key, action, mods), flags)

    @Test
    fun disambiguateReportsEscAndModifiedTextKeysAsCsiU() {
        val table =
            listOf(
                event(Key.Escape) to "\u001b[27u",
                event(Key.Escape, SHIFT) to "\u001b[27;2u",
                event(char('a'), CTRL, "a") to "\u001b[97;5u",
                event(char('a'), ALT, "a") to "\u001b[97;3u",
                event(char('a'), CTRL_SHIFT, "A") to "\u001b[97;6u",
                event(char('['), ALT, "[") to "\u001b[91;3u",
                event(char(' '), CTRL, " ") to "\u001b[32;5u",
                event(char('a'), Modifiers.SUPER, "a") to "\u001b[97;9u",
            )
        for ((e, expected) in table) assertEquals(expected, encode(e, D), "$e")
    }

    @Test
    fun disambiguateKeepsTextAndEnterTabBackspaceLegacy() {
        assertEquals("a", enc(D, char('a'), NONE, "a"))
        assertEquals("A", enc(D, char('a'), SHIFT, "A"))
        assertEquals("A", enc(D, char('a'), Modifiers.CAPS_LOCK, "A"))
        assertEquals("\r", enc(D, Key.Enter, NONE))
        assertEquals("\t", enc(D, Key.Tab, NONE))
        assertEquals("\u007f", enc(D, Key.Backspace, NONE))
        // Modified, they become reports (fixterms erratum: Shift+Tab is 9;2).
        assertEquals("\u001b[13;2u", enc(D, Key.Enter, SHIFT))
        assertEquals("\u001b[9;6u", enc(D, Key.Tab, CTRL_SHIFT))
        assertEquals("\u001b[127;5u", enc(D, Key.Backspace, CTRL))
    }

    @Test
    fun functionalKeysUseTheFunctionalTableAndIgnoreCursorMode() {
        val table =
            listOf(
                Triple(Key.Up, NONE, "\u001b[A"),
                Triple(Key.Up, CTRL, "\u001b[1;5A"),
                Triple(Key.Home, SHIFT, "\u001b[1;2H"),
                Triple(Key.F(1), NONE, "\u001b[P"),
                Triple(Key.F(1), ALT, "\u001b[1;3P"),
                Triple(Key.F(3), NONE, "\u001b[13~"),
                Triple(Key.F(5), CTRL, "\u001b[15;5~"),
                Triple(Key.Insert, NONE, "\u001b[2~"),
                Triple(Key.F(13), NONE, "\u001b[57376u"),
                Triple(Key.Media(MediaKey.Play), NONE, "\u001b[57428u"),
                Triple(Key.System(SystemKey.PrintScreen), NONE, "\u001b[57361u"),
                Triple(Key.System(SystemKey.Menu), NONE, "\u001b[57363u"),
                Triple(Key.Keypad(KeypadKey.Enter), NONE, "\u001b[57414u"),
                Triple(Key.Keypad(KeypadKey.Left), NONE, "\u001b[57417u"),
                Triple(Key.Keypad(KeypadKey.Begin), NONE, "\u001b[E"),
                Triple(Key.Keypad(KeypadKey.Digit1), CTRL, "\u001b[57400;5u"),
            )
        for ((key, mods, expected) in table) assertEquals(expected, enc(D, key, mods), "$key $mods")
        assertEquals("1", enc(D, Key.Keypad(KeypadKey.Digit1), NONE, "1"))
    }

    @Test
    fun lockStatesAreReportedForFunctionalKeysOnly() {
        assertEquals("\u001b[1;129A", enc(D, Key.Up, Modifiers.NUM_LOCK))
        assertEquals("\u001b[97;5u", enc(D, char('a'), Modifiers.NUM_LOCK + CTRL, "a"))
        assertEquals("\u001b[97;65u", enc(ALL, char('a'), Modifiers.CAPS_LOCK, "A"))
    }

    @Test
    fun modifierKeysAreReportedOnlyWithAllKeys() {
        val shift = Key.Modifier(ModifierKey.LeftShift)
        assertEquals("", enc(D, shift, SHIFT))
        assertEquals("", enc(D, Key.System(SystemKey.CapsLock), NONE))
        assertEquals("\u001b[57441;2u", enc(ALL, shift, SHIFT))
        assertEquals("\u001b[57441;1:3u", withAction(ALL + KittyFlags.REPORT_EVENTS, shift, NONE, KeyAction.Release))
    }

    @Test
    fun eventTypesAreReportedAsAModifierSubField() {
        val table =
            listOf(
                Triple(event(Key.Up), KeyAction.Press, "\u001b[A"),
                Triple(event(Key.Up), KeyAction.Repeat, "\u001b[1;1:2A"),
                Triple(event(Key.Up), KeyAction.Release, "\u001b[1;1:3A"),
                Triple(event(Key.Escape), KeyAction.Release, "\u001b[27;1:3u"),
                Triple(event(char('a'), CTRL), KeyAction.Release, "\u001b[97;5:3u"),
                Triple(event(Key.F(5), SHIFT), KeyAction.Repeat, "\u001b[15;2:2~"),
                // Text keys and Enter, Tab, Backspace have no release without all keys.
                Triple(event(char('a')), KeyAction.Release, ""),
                Triple(event(char('a'), SHIFT), KeyAction.Release, ""),
                Triple(event(Key.Enter), KeyAction.Release, ""),
                Triple(event(Key.Tab, CTRL), KeyAction.Release, ""),
            )
        for ((e, action, expected) in table) assertEquals(expected, encode(e.copy(action = action), EVENTS), "$e $action")
        assertEquals("a", encode(KeyEvent(char('a'), KeyAction.Repeat, text = "a"), EVENTS))
    }

    @Test
    fun releasesAreDroppedWithoutReportEvents() {
        assertEquals("", withAction(D, Key.Up, NONE, KeyAction.Release))
        assertEquals("\u001b[A", withAction(D, Key.Up, NONE, KeyAction.Repeat))
    }

    @Test
    fun reportAllKeysTurnsEveryKeyIntoAReport() {
        val table =
            listOf(
                event(char('a'), NONE, "a") to "\u001b[97u",
                event(char('a'), SHIFT, "A") to "\u001b[97;2u",
                event(Key.Enter) to "\u001b[13u",
                event(Key.Tab) to "\u001b[9u",
                event(Key.Backspace) to "\u001b[127u",
                event(Key.Escape) to "\u001b[27u",
                event(char(' '), NONE, " ") to "\u001b[32u",
            )
        for ((e, expected) in table) assertEquals(expected, encode(e, ALL), "$e")
        assertEquals("\u001b[13;1:3u", withAction(ALL + KittyFlags.REPORT_EVENTS, Key.Enter, NONE, KeyAction.Release))
    }

    @Test
    fun associatedTextRidesInTheThirdField() {
        val table =
            listOf(
                event(char('a'), NONE, "a") to "\u001b[97;;97u",
                event(char('a'), SHIFT, "A") to "\u001b[97;2;65u",
                event(char('e'), NONE, "é") to "\u001b[101;;233u",
                event(char('f'), NONE, "fi") to "\u001b[102;;102:105u",
                // Control characters are never text.
                event(Key.Enter, NONE, "\r") to "\u001b[13u",
            )
        for ((e, expected) in table) assertEquals(expected, encode(e, ALL_TEXT), "$e")
        val consumedAlt = KeyEvent(char('a'), mods = ALT, consumed = ALT, text = "å")
        assertEquals("\u001b[97;;229u", encode(consumedAlt, ALL_TEXT))
        // Text needs report-all; on its own it changes nothing.
        assertEquals("\u001b[97;5u", enc(D + KittyFlags.REPORT_TEXT, char('a'), CTRL, "a"))
        val release = KeyEvent(char('a'), KeyAction.Release, text = "a")
        assertEquals("\u001b[97;1:3u", encode(release, ALL_TEXT + KittyFlags.REPORT_EVENTS))
    }

    @Test
    fun alternateKeysReportShiftedAndBaseLayoutKeys() {
        assertEquals("\u001b[97:65;6u", enc(ALTERNATES, char('a'), CTRL_SHIFT, "A"))
        assertEquals("\u001b[97;5u", enc(ALTERNATES, char('a'), CTRL, "a"))
        assertEquals("a", enc(ALTERNATES, char('a'), NONE, "a"))
        // Cyrillic layout: key с (1089) sits on the US c (99).
        val cyrillic = KeyEvent(char('c'), mods = CTRL, text = "с", unshifted = 'с'.code)
        assertEquals("\u001b[1089::99;5u", encode(cyrillic, ALTERNATES))
        assertEquals("\u001b[1089:1057:99;6u", encode(cyrillic.copy(mods = CTRL_SHIFT, text = "С"), ALTERNATES))
        // Shift+= types + on a US layout: matches ctrl+plus shortcuts.
        assertEquals("\u001b[61:43;2u", enc(ALL + KittyFlags.REPORT_ALTERNATES, char('='), SHIFT, "+"))
        // Functional keys have no alternates.
        assertEquals("\u001b[1;2A", enc(ALTERNATES, Key.Up, SHIFT))
    }

    @Test
    fun stackPushPopAndQueryFollowTheSpecification() {
        val stack = KittyFlagStack()
        assertEquals(KittyFlags.NONE, stack.current)
        stack.push(1)
        stack.push(0b11)
        assertEquals(0b11, stack.current.bits)
        stack.pop(0)
        assertEquals(D, stack.current)
        stack.pop(1)
        assertEquals(KittyFlags.NONE, stack.current)
        stack.pop(1)
        assertEquals(0, stack.depth)
        stack.push(0b101)
        assertEquals("\u001b[?5u", bytesOf { stack.writeQueryReply(it) })
    }

    @Test
    fun poppingMoreThanTheStackHoldsResetsAllFlags() {
        val stack = KittyFlagStack()
        stack.push(1)
        stack.push(2)
        stack.pop(Int.MAX_VALUE)
        assertEquals(KittyFlags.NONE, stack.current)
        assertEquals(0, stack.depth)
    }

    @Test
    fun fullStackEvictsTheOldestEntryAndNeverGrows() {
        val stack = KittyFlagStack()
        for (flags in 0 until KITTY_FLAG_STACK_DEPTH + 2) {
            stack.push(flags % 32)
            assertTrue(stack.depth <= KITTY_FLAG_STACK_DEPTH)
        }
        assertEquals(KITTY_FLAG_STACK_DEPTH, stack.depth)
        stack.pop(KITTY_FLAG_STACK_DEPTH - 1)
        // Entries 0 and 1 were evicted, so the bottom is now entry 2.
        assertEquals(2, stack.current.bits)
        repeat(10_000) { stack.push(1) }
        assertEquals(KITTY_FLAG_STACK_DEPTH, stack.depth)
    }

    @Test
    fun setReplacesUnionsOrClearsTheActiveFlags() {
        val stack = KittyFlagStack()
        stack.set(0b101, 0)
        assertEquals(0b101, stack.current.bits)
        stack.set(0b010, 2)
        assertEquals(0b111, stack.current.bits)
        stack.set(0b001, 3)
        assertEquals(0b110, stack.current.bits)
        stack.set(0b001, 1)
        assertEquals(0b001, stack.current.bits)
        stack.set(0b11111, 4)
        assertEquals(0b001, stack.current.bits, "unknown mode is ignored")
        stack.pop(1)
        assertEquals(KittyFlags.NONE, stack.current)
    }

    @Test
    fun undefinedFlagBitsFromThePtyAreDropped() {
        val stack = KittyFlagStack()
        stack.push(0xFFFF)
        assertEquals(KittyFlags.ALL, stack.current)
        assertEquals(KittyFlags.NONE, KittyFlags.of(0b10_0000))
    }

    @Test
    fun mainAndAlternateScreensKeepSeparateStacks() {
        val keyboard = KittyKeyboard()
        keyboard.stack(alternateScreen = true).push(0b11111)
        assertEquals(KittyFlags.NONE, keyboard.stack(alternateScreen = false).current)
        keyboard.main.push(1)
        keyboard.alternate.clear()
        assertEquals(D, keyboard.main.current)
        assertEquals(0, keyboard.alternate.depth)
        keyboard.clear()
        assertEquals(0, keyboard.main.depth)
    }

    @Test
    fun onlyTheEscapingFlagsLeaveLegacyMode() {
        assertTrue(KittyFlags.NONE.isLegacy())
        assertTrue((KittyFlags.REPORT_ALTERNATES + KittyFlags.REPORT_TEXT).isLegacy())
        for (flags in listOf(D, KittyFlags.REPORT_EVENTS, ALL)) assertFalse(flags.isLegacy(), "$flags")
    }
}
