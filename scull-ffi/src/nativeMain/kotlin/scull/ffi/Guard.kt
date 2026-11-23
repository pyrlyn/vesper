package scull.ffi

import scull.ffi.c.TT_INVALID
import scull.ffi.c.TT_PANIC
import scull.ffi.c.TT_POISONED

// The boundary every export goes through. An exception reaching a C caller would terminate the
// whole process, every tab with it, so each export catches everything; what it broke is unknown, so
// the terminal it touched is poisoned and only freeing it is left. Other terminals carry on.
//
// Statuses are `UInt` because cinterop gives the enum's constants that type; it is the same 32 bits
// as the header's `int32_t`.

internal const val OK: UInt = 0u

/** Runs [body] for [term]: `TT_INVALID` without one, `TT_POISONED` once poisoned, the status of a [Refused], `TT_PANIC` (and poisoned from then on) for any other throw. */
internal inline fun guarded(
    term: TermHandle?,
    body: (TermHandle) -> UInt,
): UInt {
    if (term == null) return TT_INVALID
    if (term.isPoisoned) return TT_POISONED
    return try {
        body(term)
    } catch (refused: Refused) {
        refused.status
    } catch (_: Throwable) {
        term.poison()
        TT_PANIC
    }
}

/** Runs [body] where no terminal exists yet: an exception is `TT_PANIC` and nothing is poisoned. */
internal inline fun guardedCreate(body: () -> UInt): UInt =
    try {
        body()
    } catch (refused: Refused) {
        refused.status
    } catch (_: Throwable) {
        TT_PANIC
    }
