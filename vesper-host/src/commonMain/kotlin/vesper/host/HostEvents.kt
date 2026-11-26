package vesper.host

import vesper.term.Event
import vesper.term.TitleTarget

/** The title a window shows for this event; null for an icon name, which no host shows anywhere. */
public val Event.Title.windowTitle: String?
    get() = text.takeUnless { target == TitleTarget.Icon }

/**
 * The text an OSC 52 write puts on the clipboard; null when the child's bytes are not UTF-8.
 * They need not be text at all, and a clipboard of replacement characters helps no one.
 */
public val Event.ClipboardWrite.clipboardText: String?
    get() =
        try {
            data.decodeToString(throwOnInvalidSequence = true)
        } catch (_: CharacterCodingException) {
            null
        }
