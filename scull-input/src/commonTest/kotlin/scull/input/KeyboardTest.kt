package scull.input

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Seeded so a failure reproduces.
private const val SEED = 0x5C011
private const val CASES = 20_000
private const val MAX_CODE_POINT = 0x110000

private val KEYS =
    listOf(
        Key.Escape,
        Key.Enter,
        Key.Tab,
        Key.Backspace,
        Key.Up,
        Key.Home,
        Key.Delete,
        Key.Media(MediaKey.Record),
        Key.Modifier(ModifierKey.RightMeta),
        Key.System(SystemKey.Menu),
        Key.System(SystemKey.NumLock),
    )

class KeyboardTest {
    private fun encode(
        event: KeyEvent,
        modes: KeyModes,
    ) = bytesOf { encodeKey(event, modes, it) }

    @Test
    fun composingEventsSendNothingInAnyMode() {
        val event = KeyEvent(char('a'), text = "a", composing = true)
        for (modes in listOf(KeyModes(), KeyModes(kitty = KittyFlags.ALL), KeyModes(win32Input = true))) {
            assertEquals("", encode(event, modes), "$modes")
        }
    }

    @Test
    fun win32InputModeWinsOverKittyFlags() {
        val modes = KeyModes(win32Input = true, kitty = KittyFlags.ALL)
        assertEquals("\u001b[65;30;97;1;0;1_", encode(event(char('a'), text = "a"), modes))
    }

    @Test
    fun alternatesAndTextFlagsAloneKeepLegacyEncoding() {
        val modes = KeyModes(kitty = KittyFlags.REPORT_ALTERNATES + KittyFlags.REPORT_TEXT)
        assertEquals("\u001b", encode(event(Key.Escape), modes))
    }

    @Test
    fun aSharedSinkIsReusedAfterClear() {
        val out = ByteSink(capacity = 1)
        encodeKey(event(Key.F(5), CTRL), KeyModes(), out)
        out.clear()
        encodeKey(event(Key.Up), KeyModes(), out)
        assertEquals("\u001b[A", out.toByteArray().decodeToString())
    }

    @Test
    fun noKeyEventThrowsInAnyMode() {
        val random = Random(SEED)
        val out = ByteSink()
        repeat(CASES) {
            val event =
                KeyEvent(
                    key = randomKey(random),
                    action = KeyAction.entries.random(random),
                    mods = Modifiers.of(random.nextInt(0x100)),
                    consumed = Modifiers.of(random.nextInt(0x100)),
                    text = CharArray(random.nextInt(5)) { random.nextInt(0x10000).toChar() }.concatToString(),
                    unshifted = if (random.nextBoolean()) random.nextInt(MAX_CODE_POINT) else null,
                )
            val modes =
                KeyModes(
                    applicationCursor = random.nextBoolean(),
                    applicationKeypad = random.nextBoolean(),
                    modifyOtherKeys = ModifyOtherKeys.entries.random(random),
                    kitty = KittyFlags.of(random.nextInt(0x10000)),
                    win32Input = random.nextBoolean(),
                )
            out.clear()
            encodeKey(event, modes, out)
        }
    }

    @Test
    fun flagStackNeverExceedsItsCap() {
        val random = Random(SEED)
        repeat(CASES / 64) {
            val stack = KittyFlagStack()
            repeat(random.nextInt(64)) {
                val a = random.nextInt(0x10000)
                when (random.nextInt(4)) {
                    0 -> stack.push(a)
                    1 -> stack.pop(a)
                    2 -> stack.set(a, random.nextInt(0x10000))
                    else -> stack.clear()
                }
                assertTrue(stack.depth <= KITTY_FLAG_STACK_DEPTH)
                assertTrue(stack.current in KittyFlags.ALL)
            }
        }
    }

    private fun randomKey(random: Random): Key =
        when (random.nextInt(4)) {
            0 -> Key.Character(random.nextInt(MAX_CODE_POINT))
            1 -> Key.F(random.nextInt(0x100))
            2 -> Key.Keypad(KeypadKey.entries.random(random))
            else -> KEYS.random(random)
        }
}
